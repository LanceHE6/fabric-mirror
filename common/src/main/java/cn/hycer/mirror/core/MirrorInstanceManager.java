package cn.hycer.mirror.core;

import cn.hycer.mirror.config.MirrorConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 镜像实例生命周期管理器。
 * 管理 MirrorServer 的启动/停止/状态查询。
 */
public class MirrorInstanceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");
    private static final MirrorInstanceManager INSTANCE = new MirrorInstanceManager();

    private final AtomicReference<MirrorServer.State> state =
            new AtomicReference<>(MirrorServer.State.STOPPED);
    private volatile MirrorServer mirrorServer;

    public enum MirrorState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }

    private MirrorInstanceManager() {}

    public static MirrorInstanceManager getInstance() { return INSTANCE; }

    public void init() {
        MirrorConfig config = MirrorConfig.getInstance();
        if (!config.isEnabled()) {
            LOGGER.info("[Mirror] Mirror instance is disabled");
            return;
        }
        LOGGER.info("[Mirror] MirrorInstanceManager initialized");
    }

    public boolean start(MinecraftServer mainServer) {
        MirrorConfig config = MirrorConfig.getInstance();
        mirrorServer = new MirrorServer(mainServer, config);
        boolean ok = mirrorServer.start();
        if (ok) {
            state.set(MirrorServer.State.RUNNING);
            LOGGER.info("[Mirror] MirrorServer started on port {}", config.getPort());
        } else {
            state.set(MirrorServer.State.ERROR);
            LOGGER.error("[Mirror] MirrorServer failed to start");
        }
        return ok;
    }

    public boolean stop() {
        if (mirrorServer == null) return false;
        boolean ok = mirrorServer.stop();
        state.set(ok ? MirrorServer.State.STOPPED : MirrorServer.State.ERROR);
        return ok;
    }

    public MirrorState getState() {
        return MirrorState.valueOf(state.get().name());
    }

    public int getOnlinePlayerCount() {
        return mirrorServer != null ? mirrorServer.getOnlinePlayerCount() : 0;
    }

    public double getTPS() {
        return mirrorServer != null && state.get() == MirrorServer.State.RUNNING
                ? mirrorServer.getTPS() : 0.0;
    }

    public MirrorServer getMirrorServer() {
        return mirrorServer;
    }
}
