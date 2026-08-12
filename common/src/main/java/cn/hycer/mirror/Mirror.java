package cn.hycer.mirror;

import cn.hycer.mirror.config.MirrorConfig;
import cn.hycer.mirror.core.MirrorInstanceManager;
import cn.hycer.mirror.command.MirrorCommands;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mirror implements ModInitializer {

    public static final String MOD_ID = "mirror";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        MirrorConfig config = MirrorConfig.getInstance();
        if (config == null) {
            LOGGER.error("Failed to load Mirror config, mod will not initialize");
            return;
        }

        if (!config.isEnabled()) {
            LOGGER.info("[Mirror] Mirror instance is disabled in config");
            return;
        }

        MirrorCommands.register();
        MirrorInstanceManager.getInstance().init();

        LOGGER.info("[Mirror] Mirror mod initialized. Use /mirror start to launch mirror instance.");
    }
}
