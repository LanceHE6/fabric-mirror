package cn.hycer.mirror.sync;

import cn.hycer.mirror.config.MirrorConfig;
import cn.hycer.mirror.core.MirrorInstanceManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;

/**
 * 世界同步模块（独立进程方案）。
 *
 * sync map: 踢回镜像服玩家 → 复制主服世界 → 镜像服热重载（或重启）
 * sync config: 踢回玩家 → 复制配置/模组 → 重启镜像服进程
 */
public class WorldSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    /**
     * 仅同步地图：复制主服世界文件到镜像服，然后重启镜像服（简单可靠）。
     */
    public static void syncMap(CommandSourceStack src) {
        var mgr = MirrorInstanceManager.getInstance();
        var cloner = mgr.getCloner();
        if (cloner == null) {
            src.sendSystemMessage(Component.literal("§c镜像实例未初始化。"));
            return;
        }

        src.sendSystemMessage(Component.literal("§6正在同步地图..."));

        new Thread(() -> {
            var server = src.getServer();
            try {
                // 0. 主服线程强制刷盘 + 暂停自动保存（避免复制期间主服写文件导致锁冲突）
                src.sendSystemMessage(Component.literal("§7保存主服世界..."));
                CompletableFuture<Void> saveFuture = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        server.saveEverything(false, true, true);
                        server.setAutoSave(false);
                    } finally {
                        saveFuture.complete(null);
                    }
                });
                saveFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);

                try {
                    // 1. 若镜像服运行，先同步停止（确保进程退出、世界文件解锁）
                    if (mgr.isRunning()) {
                        src.sendSystemMessage(Component.literal("§7停止镜像服以同步地图..."));
                        mgr.stopAndWait();
                    }

                    // 2. 复制主服世界 → 镜像服
                    Path mainWorld = server.getServerDirectory().resolve("world");
                    Path mirrorWorld = cloner.getMirrorDir().resolve("world");

                    src.sendSystemMessage(Component.literal("§7复制世界文件..."));
                    long bytes = copyWorld(mainWorld, mirrorWorld);

                    src.sendSystemMessage(Component.literal("§a地图同步完成！(§e"
                            + (bytes / 1024 / 1024) + " MB§a)"));

                    // 3. 重启镜像服（等 Done 后提示完成）
                    src.sendSystemMessage(Component.literal("§7重启镜像服..."));
                    mgr.start(
                            () -> server.execute(() ->
                                    src.sendSystemMessage(Component.literal("§a镜像服已重启完成！"))),
                            () -> server.execute(() ->
                                    src.sendSystemMessage(Component.literal("§c镜像服重启失败，查看日志。")))
                    );
                } finally {
                    // 恢复主服自动保存
                    server.execute(() -> server.setAutoSave(true));
                }

            } catch (Exception e) {
                LOGGER.error("[Sync] Map sync failed", e);
                src.sendSystemMessage(Component.literal("§c同步失败: " + e.getMessage()));
            }
        }, "Mirror-Sync-Map-Thread").start();
    }

    /**
     * 同步配置/模组：重新克隆配置和模组，重启镜像服。
     */
    public static void syncConfig(CommandSourceStack src) {
        var mgr = MirrorInstanceManager.getInstance();
        var cloner = mgr.getCloner();
        if (cloner == null) {
            src.sendSystemMessage(Component.literal("§c镜像实例未初始化。"));
            return;
        }

        src.sendSystemMessage(Component.literal("§6正在同步配置..."));

        new Thread(() -> {
            var server = src.getServer();
            try {
                if (mgr.isRunning()) {
                    mgr.stopAndWait();
                }
                // 只同步 config 目录（模组配置），不碰 jar/mods/world/server.properties
                boolean synced = cloner.syncConfigOnly();
                if (!synced) {
                    src.sendSystemMessage(Component.literal("§c配置同步失败。"));
                    return;
                }
                src.sendSystemMessage(Component.literal("§a配置已同步，重启镜像服..."));
                // 等 Done 后提示重启完成
                mgr.start(
                        () -> server.execute(() ->
                                src.sendSystemMessage(Component.literal("§a镜像服已重启完成！"))),
                        () -> server.execute(() ->
                                src.sendSystemMessage(Component.literal("§c镜像服重启失败，查看日志。")))
                );
            } catch (Exception e) {
                LOGGER.error("[Sync] Config sync failed", e);
                src.sendSystemMessage(Component.literal("§c同步失败: " + e.getMessage()));
            }
        }, "Mirror-Sync-Config-Thread").start();
    }

    /**
     * 复制主服世界目录到镜像服。
     * 跳过 session.lock（主服独占锁定，镜像服启动时会自己生成）。
     */
    private static long copyWorld(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return 0;
        Files.createDirectories(target);

        long[] total = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Files.createDirectories(target.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                // session.lock 被主服 DirectoryLock 独占锁定，且镜像服启动时会自己生成，跳过
                if (name.equals("session.lock")) {
                    return FileVisitResult.CONTINUE;
                }
                Path rel = source.relativize(file);
                Path dst = target.resolve(rel.toString());
                try {
                    total[0] += copyFile(file, dst);
                } catch (IOException e) {
                    // 文件被主服锁定（正在写入），跳过并记录，避免整体失败
                    LOGGER.warn("[Sync] Skip locked file: {}", file.getFileName());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private static long copyFile(Path src, Path dst) throws IOException {
        try (FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(dst,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            long size = in.size();
            long pos = 0;
            while (pos < size) {
                pos += in.transferTo(pos, size - pos, out);
            }
            return size;
        }
    }
}
