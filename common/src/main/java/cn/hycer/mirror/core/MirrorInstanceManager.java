package cn.hycer.mirror.core;

import cn.hycer.mirror.config.MirrorConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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

    private MirrorInstanceManager() {}

    public static MirrorInstanceManager getInstance() { return INSTANCE; }

    /**
     * 初始化，缓存主服运行目录。
     */
    public void init(MinecraftServer server) {
        MirrorConfig config = MirrorConfig.getInstance();
        Path serverDir = server.getServerDirectory().toAbsolutePath().normalize();
        this.cloner = new MirrorCloner(serverDir, config);
        LOGGER.info("[Mirror] MirrorInstanceManager initialized (mirror dir: {})",
                cloner.getMirrorDir());
    }

    /**
     * 启动镜像服（首次自动克隆）。
     */
    public boolean start() {
        MirrorConfig config = MirrorConfig.getInstance();
        if (cloner == null) {
            LOGGER.error("[Mirror] Not initialized");
            return false;
        }

        // 首次启动自动克隆
        if (config.isAutoClone() && !cloner.isCloned()) {
            LOGGER.info("[Mirror] First start, cloning main server...");
            if (!cloner.cloneServer()) {
                LOGGER.error("[Mirror] Clone failed");
                return false;
            }
        }

        if (process == null) {
            process = new MirrorProcess(cloner.getMirrorDir(), config);
        }
        boolean ok = process.start();
        started = ok;
        return ok;
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
