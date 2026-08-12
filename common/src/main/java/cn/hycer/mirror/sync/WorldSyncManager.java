package cn.hycer.mirror.sync;

import cn.hycer.mirror.config.MirrorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 世界同步模块。
 * 将主服世界数据复制到镜像实例世界目录。
 */
public class WorldSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    private final Path mainWorldPath;
    private final Path mirrorWorldPath;
    private final MirrorConfig config;

    public WorldSyncManager(Path mainWorldPath, Path mirrorWorldPath, MirrorConfig config) {
        this.mainWorldPath = mainWorldPath;
        this.mirrorWorldPath = mirrorWorldPath;
        this.config = config;
    }

    /**
     * 完整同步：复制所有维度的 region 文件。
     */
    public CompletableFuture<SyncResult> syncWorlds() {
        return CompletableFuture.supplyAsync(() -> {
            SyncResult result = new SyncResult();
            long startTime = System.currentTimeMillis();

            try {
                LOGGER.info("[WorldSync] Starting full world sync...");
                LOGGER.info("[WorldSync] Source: {}", mainWorldPath);
                LOGGER.info("[WorldSync] Target: {}", mirrorWorldPath);

                List<String> dimensions = config.getSyncDimensions();
                long totalBytes = 0;

                for (String dim : dimensions) {
                    Path srcDim, dstDim;
                    if ("overworld".equals(dim)) {
                        srcDim = mainWorldPath;
                        dstDim = mirrorWorldPath;
                    } else if ("the_nether".equals(dim)) {
                        srcDim = mainWorldPath.resolve("DIM-1");
                        dstDim = mirrorWorldPath.resolve("DIM-1");
                    } else if ("the_end".equals(dim)) {
                        srcDim = mainWorldPath.resolve("DIM1");
                        dstDim = mirrorWorldPath.resolve("DIM1");
                    } else {
                        LOGGER.warn("[WorldSync] Unknown dimension: {}", dim);
                        continue;
                    }

                    long bytes = copyRegionFiles(srcDim, dstDim);
                    totalBytes += bytes;
                    LOGGER.info("[WorldSync] {} copied {} bytes", dim, bytes);
                }

                result.success = true;
                result.bytesCopied = totalBytes;
                result.durationMs = System.currentTimeMillis() - startTime;
                LOGGER.info("[WorldSync] Sync complete: {} bytes in {}ms",
                        totalBytes, result.durationMs);

            } catch (Exception e) {
                result.success = false;
                result.errorMessage = e.getMessage();
                LOGGER.error("[WorldSync] Sync failed", e);
            }

            return result;
        });
    }

    /**
     * 增量同步：仅复制自上次同步以来变更的 region 文件。
     */
    public CompletableFuture<SyncResult> syncWorldsIncremental() {
        return CompletableFuture.supplyAsync(() -> {
            SyncResult result = new SyncResult();
            long startTime = System.currentTimeMillis();

            try {
                LOGGER.info("[WorldSync] Starting incremental sync...");
                long totalBytes = 0;

                for (String dim : config.getSyncDimensions()) {
                    Path srcDim, dstDim;
                    if ("overworld".equals(dim)) {
                        srcDim = mainWorldPath;
                        dstDim = mirrorWorldPath;
                    } else if ("the_nether".equals(dim)) {
                        srcDim = mainWorldPath.resolve("DIM-1");
                        dstDim = mirrorWorldPath.resolve("DIM-1");
                    } else if ("the_end".equals(dim)) {
                        srcDim = mainWorldPath.resolve("DIM1");
                        dstDim = mirrorWorldPath.resolve("DIM1");
                    } else {
                        continue;
                    }

                    long bytes = copyRegionFilesIncremental(srcDim, dstDim);
                    totalBytes += bytes;
                    LOGGER.info("[WorldSync] {} copied {} bytes (incremental)", dim, bytes);
                }

                result.success = true;
                result.bytesCopied = totalBytes;
                result.durationMs = System.currentTimeMillis() - startTime;
                LOGGER.info("[WorldSync] Incremental sync complete: {} bytes in {}ms",
                        totalBytes, result.durationMs);

            } catch (Exception e) {
                result.success = false;
                result.errorMessage = e.getMessage();
                LOGGER.error("[WorldSync] Incremental sync failed", e);
            }

            return result;
        });
    }

    /**
     * 复制所有 region 文件（完整模式）。
     * 目标中的旧 .mca 文件会被先删除。
     */
    private long copyRegionFiles(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            LOGGER.warn("[WorldSync] Source does not exist: {}", source);
            return 0;
        }

        // Ensure target directory exists
        Path regionDir = target.resolve("region");
        Files.createDirectories(regionDir);

        // Delete old .mca files in target
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path old : stream) {
                Files.delete(old);
            }
        } catch (IOException ignored) {}

        // Find entities/poi directories if they exist
        List<Path> sourceDirs = new ArrayList<>();
        sourceDirs.add(source.resolve("region"));
        if (Files.exists(source.resolve("entities"))) {
            sourceDirs.add(source.resolve("entities"));
        }
        if (Files.exists(source.resolve("poi"))) {
            sourceDirs.add(source.resolve("poi"));
        }

        long totalBytes = 0;
        for (Path srcDir : sourceDirs) {
            if (!Files.exists(srcDir)) continue;
            Path dstDir = target.resolve(srcDir.getFileName());
            Files.createDirectories(dstDir);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(srcDir)) {
                for (Path srcFile : stream) {
                    if (!Files.isRegularFile(srcFile)) continue;
                    Path dstFile = dstDir.resolve(srcFile.getFileName());
                    long bytes = copyFile(srcFile, dstFile);
                    totalBytes += bytes;
                }
            }
        }

        return totalBytes;
    }

    /**
     * 增量复制：仅当源文件比目标文件新时才复制。
     */
    private long copyRegionFilesIncremental(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return 0;

        Path regionDir = target.resolve("region");
        Files.createDirectories(regionDir);

        Path srcRegion = source.resolve("region");
        if (!Files.exists(srcRegion)) return 0;

        long totalBytes = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(srcRegion, "*.mca")) {
            for (Path srcFile : stream) {
                if (!Files.isRegularFile(srcFile)) continue;
                Path dstFile = regionDir.resolve(srcFile.getFileName());

                if (Files.exists(dstFile)) {
                    long srcTime = Files.getLastModifiedTime(srcFile).toMillis();
                    long dstTime = Files.getLastModifiedTime(dstFile).toMillis();
                    if (srcTime <= dstTime) continue; // skip unchanged
                }

                long bytes = copyFile(srcFile, dstFile);
                totalBytes += bytes;
            }
        }

        return totalBytes;
    }

    /**
     * 使用 FileChannel.transferTo 高效复制单个文件。
     */
    private long copyFile(Path source, Path target) throws IOException {
        try (FileChannel src = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(target,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            long size = src.size();
            long transferred = 0;
            while (transferred < size) {
                transferred += src.transferTo(transferred, size - transferred, dst);
            }
            return size;
        }
    }

    /**
     * 检查同步前可用磁盘空间（简单估算）。
     */
    public long estimateSyncSize() {
        long total = 0;
        try {
            total += estimateDirSize(mainWorldPath.resolve("region"));
            total += estimateDirSize(mainWorldPath.resolve("DIM-1").resolve("region"));
            total += estimateDirSize(mainWorldPath.resolve("DIM1").resolve("region"));
        } catch (Exception e) {
            LOGGER.warn("[WorldSync] Error estimating size", e);
        }
        return total;
    }

    private long estimateDirSize(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path f : stream) {
                if (Files.isRegularFile(f)) {
                    total += Files.size(f);
                }
            }
        }
        return total;
    }

    public static class SyncResult {
        public long bytesCopied;
        public long durationMs;
        public boolean success;
        public String errorMessage;
    }
}
