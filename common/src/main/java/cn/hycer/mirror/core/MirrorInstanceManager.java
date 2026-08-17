package cn.hycer.mirror.core;

import cn.hycer.mirror.config.MirrorConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 镜像实例生命周期管理器（独立进程方案）。
 * 管理镜像服的克隆、启动、停止、状态查询。
 */
public class MirrorInstanceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");
    private static final MirrorInstanceManager INSTANCE = new MirrorInstanceManager();

    private MirrorProcess process;
    private MirrorCloner cloner;
    private volatile boolean started = false;
    /** 主服引用（克隆世界前需在服务端线程 save-all） */
    private MinecraftServer server;

    private MirrorInstanceManager() {}

    public static MirrorInstanceManager getInstance() { return INSTANCE; }

    /**
     * 初始化，缓存主服运行目录。
     */
    public void init(MinecraftServer server) {
        MirrorConfig config = MirrorConfig.getInstance();
        this.server = server;
        Path serverDir = server.getServerDirectory().toAbsolutePath().normalize();
        this.cloner = new MirrorCloner(serverDir, config);
        LOGGER.info("[Mirror] MirrorInstanceManager initialized (mirror dir: {})",
                cloner.getMirrorDir());
    }

    /**
     * 启动镜像服（首次自动克隆）。
     */
    public boolean start() {
        return start(null, null);
    }

    /**
     * 启动镜像服（首次自动克隆）。
     *
     * @param onReady 镜像服 MC 启动完成（Done）后回调
     * @param onFail  启动失败（克隆失败或进程退出但未 Done）后回调
     */
    public boolean start(Runnable onReady, Runnable onFail) {
        MirrorConfig config = MirrorConfig.getInstance();
        if (cloner == null) {
            LOGGER.error("[Mirror] Not initialized");
            if (onFail != null) onFail.run();
            return false;
        }

        // 首次启动自动克隆（连世界一起复制）
        if (config.isAutoClone() && !cloner.isCloned()) {
            LOGGER.info("[Mirror] First start, cloning main server...");
            // 主服线程强制刷盘 + 暂停自动保存（否则复制到的是内存中未落盘的旧世界，且复制期间主服写文件会锁冲突）
            if (!saveWorldAndPauseAutosave()) {
                LOGGER.error("[Mirror] Save world failed, abort clone");
                if (onFail != null) onFail.run();
                return false;
            }
            try {
                if (!cloner.cloneServer(true)) {
                    LOGGER.error("[Mirror] Clone failed");
                    if (onFail != null) onFail.run();
                    return false;
                }
            } finally {
                // 恢复主服自动保存
                server.execute(() -> server.setAutoSave(true));
            }
        }

        if (process == null) {
            process = new MirrorProcess(cloner.getMirrorDir(), config);
        }
        process.setReadyCallback(onReady);
        process.setFailCallback(onFail);
        boolean ok = process.start();
        if (!ok && onFail != null) {
            onFail.run();
        }
        started = ok;
        return ok;
    }

    /**
     * 主服线程强制刷盘（saveEverything）+ 暂停自动保存。
     * 必须在后台线程调用（内部通过 server.execute 调度到服务端线程，并阻塞等待完成），
     * 供克隆/同步世界前使用，避免复制到未落盘数据或复制期间主服写文件。
     *
     * @return 是否成功；调用后即使失败也应交由调用方恢复自动保存
     */
    private boolean saveWorldAndPauseAutosave() {
        if (server == null) return false;
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        server.execute(() -> {
            try {
                server.saveEverything(false, true, true);
                server.setAutoSave(false);
                f.complete(true);
            } catch (Exception e) {
                LOGGER.error("[Mirror] saveEverything failed", e);
                // 未暂停自动保存，无需恢复
                f.complete(false);
            }
        });
        try {
            return f.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.error("[Mirror] Timeout waiting for save", e);
            return false;
        }
    }

    /**
     * 停止镜像服。
     */
    public boolean stop() {
        return stop(null);
    }

    /**
     * 停止镜像服，进程退出后触发回调。
     */
    public boolean stop(Runnable onStopped) {
        if (process == null) return false;
        process.setStopCallback(onStopped);
        process.stop();
        started = false;
        return true;
    }

    /**
     * 同步停止镜像服（等待进程真正退出）。
     * 供 sync 等在后台线程执行的场景使用。
     */
    public void stopAndWait() {
        if (process == null) return;
        process.stopAndWait();
        started = false;
    }

    /**
     * 强制杀死镜像服进程。
     */
    public void forceKill() {
        if (process != null) process.forceKill();
        started = false;
    }

    /**
     * 发送命令到镜像服。
     */
    public boolean sendCommand(String command) {
        return process != null && process.sendCommand(command);
    }

    public MirrorProcess getProcess() { return process; }
    public MirrorCloner getCloner() { return cloner; }

    public boolean isRunning() {
        return started && process != null && process.isRunning();
    }

    /**
     * 镜像服 MC 是否已启动完成（Done），可以接受玩家连接。
     */
    public boolean isReady() {
        return started && process != null && process.isReady();
    }

    public MirrorProcess.State getState() {
        return process != null ? process.getState() : MirrorProcess.State.STOPPED;
    }
}
