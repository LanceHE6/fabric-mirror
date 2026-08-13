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
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                MirrorInstanceManager.getInstance().init(server));

        LOGGER.info("[Mirror] Mirror mod initialized (main side). Use /mirror start");
    }
}
