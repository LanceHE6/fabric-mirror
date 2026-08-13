package cn.hycer.mirror;

import cn.hycer.mirror.config.MirrorConfig;
import cn.hycer.mirror.command.MirrorCommands;
import cn.hycer.mirror.core.MirrorInstanceManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mirror implements ModInitializer {

    public static final String MOD_ID = "mirror";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        MirrorConfig config = MirrorConfig.getInstance();
        if (config == null) {
            LOGGER.error("[Mirror] Failed to load config");
            return;
        }

        if (MirrorConfig.isMirrorInstance()) {
            // ===== 镜像服模式 =====
            // 只注册 /mirror return，不提供主服侧管理指令
            MirrorCommands.registerMirrorSide();
            LOGGER.info("[Mirror] Running as MIRROR instance (only /mirror return)");
            return;
        }

        // ===== 主服模式 =====
        if (!config.isEnabled()) {
            LOGGER.info("[Mirror] Mirror mod disabled in config");
            return;
        }

        MirrorCommands.registerMainSide();

        // 服务端启动后初始化镜像实例管理器（缓存运行目录）
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MirrorInstanceManager.getInstance().init(server);
            // 确保主服接受 Transfer（玩家从镜像服 return 时，主服是 transfer 目标）
            if (server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated) {
                if (!dedicated.getProperties().acceptsTransfers.get()) {
                    dedicated.setAcceptsTransfers(true);
                    LOGGER.warn("[Mirror] 主服 accepts-transfers 已自动设为 true（否则玩家无法从镜像服返回）");
                }
            }
        });

        // 主服关闭时同步关闭镜像服（避免镜像服变孤儿进程继续运行）
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (MirrorInstanceManager.getInstance().isRunning()) {
                LOGGER.info("[Mirror] Main server stopping, shutting down mirror...");
                MirrorInstanceManager.getInstance().forceKill();
            }
        });

        // 兜底：JVM 退出时确保镜像服进程被清理（覆盖异常崩溃等未走 SERVER_STOPPING 的场景）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (MirrorInstanceManager.getInstance().isRunning()) {
                LOGGER.info("[Mirror] JVM shutdown, force killing mirror...");
                MirrorInstanceManager.getInstance().forceKill();
            }
        }, "Mirror-Shutdown-Hook"));

        LOGGER.info("[Mirror] Mirror mod initialized (main side). Use /mirror start");
    }
}
