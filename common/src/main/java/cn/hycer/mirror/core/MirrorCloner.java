package cn.hycer.mirror.core;

import cn.hycer.mirror.config.MirrorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 镜像服克隆器。
 * 首次 /mirror start 时，把主服的 server 核心、mods、config 克隆到 mirror/ 目录。
 */
public class MirrorCloner {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    private final Path mainServerDir;
    private final Path mirrorDir;
    private final MirrorConfig config;

    public MirrorCloner(Path mainServerDir, MirrorConfig config) {
        this.mainServerDir = mainServerDir;
        this.config = config;
        this.mirrorDir = mainServerDir.resolve(config.getMirrorDir()).normalize();
    }

    public Path getMirrorDir() { return mirrorDir; }

    /**
     * 判断镜像服目录是否已克隆过（存在核心 jar 且存在 eula.txt）。
     */
    public boolean isCloned() {
        return hasServerJar(mirrorDir) && Files.exists(mirrorDir.resolve("eula.txt"));
    }

    /**
     * 执行克隆（不复制世界）。
     * 复制：server 核心 jar、mods/、config/、eula.txt
     * 生成：镜像服 server.properties（改端口、改 level-name）
     */
    public boolean cloneServer() {
        return cloneServer(false);
    }

    /**
     * 执行克隆。
     *
     * @param includeWorld 是否连主服世界一起复制（首次克隆应传 true，
     *                     否则镜像服启动时生成空白世界；sync config 传 false 保持不碰世界）
     * 复制：server 核心 jar、mods/、config/、eula.txt（includeWorld=true 时含 world/）
     * 生成：镜像服 server.properties（改端口、改 level-name）
     */
    public boolean cloneServer(boolean includeWorld) {
        try {
            LOGGER.info("[Cloner] Cloning main server → {}", mirrorDir);
            Files.createDirectories(mirrorDir);

            // 1. 复制 server 核心 jar（启动器 + game jar 都要复制，启动器依赖 game jar）
            //    例：fabric-server-launch.jar（启动器）+ server.jar（game jar）
            if (!copyServerJars()) {
                LOGGER.error("[Cloner] No server jar found in {}", mainServerDir);
                return false;
            }

            // 1.1 复制启动器配置（fabric-server-launcher.properties 里 serverJar=xxx）
            Path launcherProps = mainServerDir.resolve("fabric-server-launcher.properties");
            if (Files.exists(launcherProps)) {
                Files.copy(launcherProps, mirrorDir.resolve("fabric-server-launcher.properties"),
                        StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[Cloner] Copied fabric-server-launcher.properties");
            }

            // 2. 复制 versions/（server 核心）、libraries/（依赖库）、.fabric/（fabric 配置）
            //    这样镜像服启动时无需重新下载，内网离线环境也能启动
            copyDirIfExists(mainServerDir.resolve("versions"), mirrorDir.resolve("versions"));
            copyDirIfExists(mainServerDir.resolve("libraries"), mirrorDir.resolve("libraries"));
            copyDirIfExists(mainServerDir.resolve(".fabric"), mirrorDir.resolve(".fabric"));

            // 3. 复制 mods/
            copyDirIfExists(mainServerDir.resolve("mods"), mirrorDir.resolve("mods"));

            // 4. 复制 config/
            copyDirIfExists(mainServerDir.resolve("config"), mirrorDir.resolve("config"));

            // 5. 复制 eula.txt
            Path mainEula = mainServerDir.resolve("eula.txt");
            if (Files.exists(mainEula)) {
                Files.copy(mainEula, mirrorDir.resolve("eula.txt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.writeString(mirrorDir.resolve("eula.txt"), "eula=true\n");
            }

            // 5.1 连世界一起复制（首次克隆）
            // 注意：调用方需先 saveEverything + 暂停主服自动保存，否则复制到的是内存中未落盘的旧世界
            if (includeWorld) {
                copyWorld(mainServerDir.resolve("world"), mirrorDir.resolve("world"));
            }

            // 6. 生成镜像服 server.properties
            generateMirrorServerProperties();

            LOGGER.info("[Cloner] Clone complete");
            return true;
        } catch (IOException e) {
            LOGGER.error("[Cloner] Clone failed", e);
            return false;
        }
    }

    /**
     * 生成镜像服的 server.properties。
     * 继承主服配置，但改端口和 level-name。
     */
    private void generateMirrorServerProperties() throws IOException {
        Path mainProps = mainServerDir.resolve("server.properties");
        Path mirrorProps = mirrorDir.resolve("server.properties");

        List<String> lines = new ArrayList<>();
        if (Files.exists(mainProps)) {
            lines = new ArrayList<>(Files.readAllLines(mainProps));
        }

        // 仅覆盖必要项。
        // accepts-transfers 必须为 true，否则镜像服拒绝接受 Transfer 过来的玩家。
        lines = replaceOrAdd(lines, "server-port", String.valueOf(config.getPort()));
        lines = replaceOrAdd(lines, "level-name", "world");
        lines = replaceOrAdd(lines, "server-ip", ""); // 监听所有地址
        lines = replaceOrAdd(lines, "accepts-transfers", "true");
        // 镜像服关闭 RCON：克隆会继承主服的 enable-rcon/rcon.port 设置，
        // 若主服开启了 RCON，镜像服会尝试绑定同一端口导致 BindException（非致命但日志报错）
        lines = replaceOrAdd(lines, "enable-rcon", "false");
        // 在线模式：镜像服继承主服的 online-mode / enforce-secure-profile（不覆盖，保持在线认证）。
        // 压缩暂禁用，避免字节透传下 setupCompression 时序问题（后续可恢复测试）。
        lines = replaceOrAdd(lines, "network-compression-threshold", "-1");

        Files.write(mirrorProps, lines);
        LOGGER.info("[Cloner] Generated mirror server.properties (port={})", config.getPort());
    }

    private static List<String> replaceOrAdd(List<String> lines, String key, String value) {
        String prefix = key + "=";
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith(prefix) || line.startsWith("#" + prefix)) {
                lines.set(i, prefix + value);
                replaced = true;
                break;
            }
        }
        if (!replaced) lines.add(prefix + value);
        return lines;
    }

    private static void copyDirIfExists(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) {
            LOGGER.info("[Cloner] Skip (not found): {}", src.getFileName());
            return;
        }
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = dst.resolve(src.relativize(dir).toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dst.resolve(src.relativize(file).toString());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        LOGGER.info("[Cloner] Copied {}", src.getFileName());
    }

    /**
     * 复制主服世界到镜像服。
     * 跳过 session.lock（主服 DirectoryLock 独占锁定，镜像服启动时会自己生成）；
     * 单文件被锁/写入中则跳过并记录，避免整个克隆失败。
     */
    private static void copyWorld(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) {
            LOGGER.info("[Cloner] No world to copy, mirror will generate a fresh world");
            return;
        }
        long[] total = {0};
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = dst.resolve(src.relativize(dir).toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("session.lock")) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = dst.resolve(src.relativize(file).toString());
                try {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    total[0] += Files.size(file);
                } catch (IOException e) {
                    // 主服正在写该文件（锁冲突），跳过并继续
                    LOGGER.warn("[Cloner] Skip locked file: {}", file.getFileName());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        LOGGER.info("[Cloner] Copied world ({} MB)", total[0] / 1024 / 1024);
    }

    /**
     * 复制主服根目录的所有 server 核心 jar（启动器 + game jar）。
     * fabric-server-launch.jar（启动器）依赖 server.jar（game jar），两者都要复制。
     * 排除 mirror 自己的 jar（那是模组，放在 mods/ 里）。
     *
     * @return 是否复制了至少一个 jar
     */
    private boolean copyServerJars() throws IOException {
        if (!Files.exists(mainServerDir)) return false;
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mainServerDir, "*.jar")) {
            for (Path jar : stream) {
                String name = jar.getFileName().toString();
                // 排除 mirror 模组自己的 jar（不是 server 核心）
                if (name.startsWith("mirror-")) continue;
                Files.copy(jar, mirrorDir.resolve(name),
                        StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[Cloner] Copied server jar: {}", name);
                count++;
            }
        }
        return count > 0;
    }

    private static boolean hasServerJar(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}
