package cn.hycer.mirror.command;

import cn.hycer.mirror.core.MirrorInstanceManager;
import cn.hycer.mirror.core.ValidationCommands;
import cn.hycer.mirror.network.MirrorNetworkHandler;
import cn.hycer.mirror.sync.WorldSyncManager;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

import static net.minecraft.commands.Commands.literal;

/**
 * 镜像实例指令模块。
 */
public class MirrorCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var mirror = literal("mirror")
                    .requires(src -> Commands.LEVEL_ALL.check(src.permissions()))
                    .then(literal("status").executes(MirrorCommands::showStatus))
                    .then(literal("start")
                            .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                            .executes(MirrorCommands::startMirror))
                    .then(literal("stop")
                            .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                            .executes(MirrorCommands::stopMirror))
                    .then(literal("sync")
                            .executes(MirrorCommands::previewSync)
                            .then(literal("confirm").executes(MirrorCommands::executeSync))
                            .then(literal("incremental").executes(MirrorCommands::executeIncrementalSync)))
                    .then(literal("goto").executes(MirrorCommands::transferToMirror))
                    .then(literal("return").executes(MirrorCommands::transferToMain));

            ValidationCommands.addToNode(mirror);
            dispatcher.register(mirror);
        });
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var mgr = MirrorInstanceManager.getInstance();
        var state = mgr.getState();
        src.sendSystemMessage(Component.literal("§6===== Mirror 镜像实例状态 ====="));
        src.sendSystemMessage(Component.literal("  状态: §e" + state.name()));
        src.sendSystemMessage(Component.literal("  在线玩家: §e" + mgr.getOnlinePlayerCount()));
        if (state == MirrorInstanceManager.MirrorState.RUNNING) {
            src.sendSystemMessage(Component.literal("  TPS: §e" + String.format("%.1f", mgr.getTPS())));
        }
        return 1;
    }

    private static int startMirror(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var mgr = MirrorInstanceManager.getInstance();
        if (mgr.getState() != MirrorInstanceManager.MirrorState.STOPPED) {
            src.sendSystemMessage(Component.literal("§c镜像实例已在运行中。当前状态: " + mgr.getState()));
            return 0;
        }
        src.sendSystemMessage(Component.literal("§6正在启动镜像实例..."));
        new Thread(() -> {
            boolean ok = mgr.start(src.getServer());
            src.sendSystemMessage(ok ? Component.literal("§a镜像实例启动成功！")
                    : Component.literal("§c镜像实例启动失败！查看日志。"));
        }, "Mirror-Start-Thread").start();
        return 1;
    }

    private static int stopMirror(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var mgr = MirrorInstanceManager.getInstance();
        if (mgr.getState() != MirrorInstanceManager.MirrorState.RUNNING) {
            src.sendSystemMessage(Component.literal("§c镜像实例未运行。当前状态: " + mgr.getState()));
            return 0;
        }
        src.sendSystemMessage(Component.literal("§6正在停止镜像实例..."));
        boolean ok = mgr.stop();
        src.sendSystemMessage(ok ? Component.literal("§a镜像实例已停止。")
                : Component.literal("§c停止失败！"));
        return 1;
    }

    private static int previewSync(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSystemMessage(Component.literal("§6镜像世界同步将复制主服世界到镜像实例。\n§7使用 §e/mirror sync confirm §7确认。"));
        return 1;
    }

    private static int executeSync(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var mgr = MirrorInstanceManager.getInstance();

        if (mgr.getState() != MirrorInstanceManager.MirrorState.RUNNING) {
            src.sendSystemMessage(Component.literal("§c镜像实例未运行，无法同步。"));
            return 0;
        }

        var server = src.getServer();
        Path mainWorld = server.getServerDirectory().resolve("world");
        Path mirrorWorld = Path.of("mirror_world");
        var config = cn.hycer.mirror.config.MirrorConfig.getInstance();
        var syncMgr = new WorldSyncManager(mainWorld, mirrorWorld, config);

        long estSize = syncMgr.estimateSyncSize();
        src.sendSystemMessage(Component.literal("§6正在同步世界数据... (约 " 
                + (estSize / 1024 / 1024) + " MB)"));
        
        syncMgr.syncWorlds().thenAccept(result -> {
            if (result.success) {
                src.sendSystemMessage(Component.literal("§a同步完成！" 
                        + (result.bytesCopied / 1024 / 1024) + " MB, "
                        + result.durationMs + "ms"));
                // Trigger hot reload on mirror
                var mirror = mgr.getMirrorServer();
                if (mirror != null) {
                    src.sendSystemMessage(Component.literal("§6正在热重载镜像世界..."));
                    mirror.reloadWorlds();
                    src.sendSystemMessage(Component.literal("§a镜像世界已重载！"));
                }
            } else {
                src.sendSystemMessage(Component.literal("§c同步失败: " + result.errorMessage));
            }
        });
        return 1;
    }

    private static int executeIncrementalSync(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var mgr = MirrorInstanceManager.getInstance();
        if (mgr.getState() != MirrorInstanceManager.MirrorState.RUNNING) {
            src.sendSystemMessage(Component.literal("§c镜像实例未运行。"));
            return 0;
        }
        var server = src.getServer();
        Path mainWorld = server.getServerDirectory().resolve("world");
        var config = cn.hycer.mirror.config.MirrorConfig.getInstance();
        var syncMgr = new WorldSyncManager(mainWorld, Path.of("mirror_world"), config);

        src.sendSystemMessage(Component.literal("§6正在增量同步..."));
        syncMgr.syncWorldsIncremental().thenAccept(result -> {
            if (result.success) {
                src.sendSystemMessage(Component.literal("§a增量同步完成！" 
                        + (result.bytesCopied / 1024) + " KB, " + result.durationMs + "ms"));
                var mirror = mgr.getMirrorServer();
                if (mirror != null) mirror.reloadWorlds();
            } else {
                src.sendSystemMessage(Component.literal("§c增量同步失败: " + result.errorMessage));
            }
        });
        return 1;
    }

    private static int transferToMirror(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendSystemMessage(Component.literal("§c此命令只能由玩家执行。"));
            return 0;
        }
        var mgr = MirrorInstanceManager.getInstance();
        if (mgr.getState() != MirrorInstanceManager.MirrorState.RUNNING) {
            src.sendSystemMessage(Component.literal("§c镜像实例未运行。请先 /mirror start"));
            return 0;
        }
        boolean ok = MirrorNetworkHandler.transferToMirror(player);
        if (!ok) {
            src.sendSystemMessage(Component.literal("§c传输失败，请查看日志。"));
        }
        return ok ? 1 : 0;
    }

    private static int transferToMain(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendSystemMessage(Component.literal("§c此命令只能由玩家执行。"));
            return 0;
        }
        boolean ok = MirrorNetworkHandler.transferToMain(player);
        if (!ok) {
            src.sendSystemMessage(Component.literal("§c返回失败，请查看日志。"));
        }
        return ok ? 1 : 0;
    }
}
