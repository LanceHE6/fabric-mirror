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
     * 执行克隆。
     * 复制：server 核心 jar、mods/、config/、eula.txt
     * 生成：镜像服 server.properties（改端口、改 level-name）
     */
    public boolean cloneServer() {
        try {
            LOGGER.info("[Cloner] Cloning main server → {}", mirrorDir);
            Files.createDirectories(mirrorDir);

            // 1. 复制 server 核心 jar
            Path mainJar = findServerJar(mainServerDir);
            if (mainJar == null) {
                LOGGER.error("[Cloner] Cannot find server jar in {}", mainServerDir);
                return false;
            }
            Files.copy(mainJar, mirrorDir.resolve(mainJar.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[Cloner] Copied server jar: {}", mainJar.getFileName());

            // 2. 复制 mods/
            copyDirIfExists(mainServerDir.resolve("mods"), mirrorDir.resolve("mods"));

            // 3. 复制 config/
            copyDirIfExists(mainServerDir.resolve("config"), mirrorDir.resolve("config"));

            // 4. 复制 eula.txt
            Path mainEula = mainServerDir.resolve("eula.txt");
            if (Files.exists(mainEula)) {
                Files.copy(mainEula, mirrorDir.resolve("eula.txt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.writeString(mirrorDir.resolve("eula.txt"), "eula=true\n");
            }

            // 5. 生成镜像服 server.properties
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

        // 覆盖关键项
        lines = replaceOrAdd(lines, "server-port", String.valueOf(config.getPort()));
        lines = replaceOrAdd(lines, "level-name", "world");
        lines = replaceOrAdd(lines, "online-mode", "true");
        lines = replaceOrAdd(lines, "server-ip", ""); // 监听所有地址
        lines = replaceOrAdd(lines, "max-players", String.valueOf(config.getMaxPlayers()));

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
     * 在主服目录中查找 server 核心 jar。
     * 优先级：fabric-server-launch.jar > server.jar > 任意 *.jar
     */
    private static Path findServerJar(Path dir) throws IOException {
        if (!Files.exists(dir)) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            List<Path> jars = new ArrayList<>();
            for (Path p : stream) jars.add(p);

            for (Path j : jars) {
                if (j.getFileName().toString().contains("fabric-server-launch")) return j;
            }
            for (Path j : jars) {
                if (j.getFileName().toString().equals("server.jar")) return j;
            }
            // 排除 mirror 自己的 jar，取第一个非 mirror jar
            for (Path j : jars) {
                if (!j.getFileName().toString().startsWith("mirror")) return j;
            }
            return jars.isEmpty() ? null : jars.get(0);
        }
    }

    private static boolean hasServerJar(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}
