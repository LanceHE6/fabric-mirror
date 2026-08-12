package cn.hycer.mirror.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Mirror 主配置，对应 config/mirror/mirror.json
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MirrorConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String CONFIG_FILE_NAME = "mirror/mirror.json";
    private static MirrorConfig INSTANCE;

    @JsonProperty("mirror")
    private MirrorSection mirror = new MirrorSection();

    @JsonProperty("performance")
    private PerformanceSection performance = new PerformanceSection();

    private MirrorConfig() {}

    public static MirrorConfig getInstance() {
        if (INSTANCE != null) return INSTANCE;

        Path configDir = FabricLoader.getInstance().getConfigDir();
        File file = configDir.resolve(CONFIG_FILE_NAME).toFile();

        if (file.exists()) {
            try {
                INSTANCE = OBJECT_MAPPER.readValue(file, MirrorConfig.class);
                LOGGER.info("[Mirror] Config loaded from {}", file.getAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("[Mirror] Failed to load config, using defaults", e);
                INSTANCE = new MirrorConfig();
                INSTANCE.saveConfig();
            }
        } else {
            INSTANCE = new MirrorConfig();
            INSTANCE.saveConfig();
            LOGGER.info("[Mirror] Created default config at {}", file.getAbsolutePath());
        }
        return INSTANCE;
    }

    public void saveConfig() {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            File file = configDir.resolve(CONFIG_FILE_NAME).toFile();
            file.getParentFile().mkdirs();
            OBJECT_MAPPER.writeValue(file, this);
        } catch (IOException e) {
            LOGGER.error("[Mirror] Failed to save config", e);
        }
    }

    // Delegating accessors — @JsonIgnore prevents serializing as flat properties

    @JsonIgnore
    public boolean isEnabled() { return mirror.enabled; }
    @JsonIgnore
    public void setEnabled(boolean v) { mirror.enabled = v; saveConfig(); }

    @JsonIgnore
    public String getWorldPath() { return mirror.worldPath; }
    @JsonIgnore
    public String getConfigPath() { return mirror.configPath; }
    @JsonIgnore
    public int getPort() { return mirror.port; }
    @JsonIgnore
    public String getBindAddress() { return mirror.bindAddress; }
    @JsonIgnore
    public int getMaxPlayers() { return mirror.maxPlayers; }
    @JsonIgnore
    public List<String> getSyncDimensions() { return mirror.sync.dimensions; }
    @JsonIgnore
    public boolean isBackupBeforeSync() { return mirror.sync.backupBeforeSync; }
    @JsonIgnore
    public boolean isPauseAutosaveDuringSync() { return mirror.sync.pauseAutosaveDuringSync; }
    @JsonIgnore
    public int getMirrorViewDistance() { return performance.mirrorViewDistance; }
    @JsonIgnore
    public int getMirrorSimulationDistance() { return performance.mirrorSimulationDistance; }
    @JsonIgnore
    public boolean isLimitChunkLoadingRate() { return performance.limitChunkLoadingRate; }

    // Section classes

    public static class MirrorSection {
        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("world_path")
        private String worldPath = "mirror_world";

        @JsonProperty("config_path")
        private String configPath = "mirror_config";

        @JsonProperty("port")
        private int port = 25566;

        @JsonProperty("bind_address")
        private String bindAddress = "127.0.0.1";

        @JsonProperty("max_players")
        private int maxPlayers = 3;

        @JsonProperty("sync")
        private SyncSection sync = new SyncSection();
    }

    public static class SyncSection {
        @JsonProperty("dimensions")
        private List<String> dimensions = Arrays.asList("overworld", "the_nether", "the_end");

        @JsonProperty("backup_before_sync")
        private boolean backupBeforeSync = true;

        @JsonProperty("pause_autosave_during_sync")
        private boolean pauseAutosaveDuringSync = true;
    }

    public static class PerformanceSection {
        @JsonProperty("mirror_view_distance")
        private int mirrorViewDistance = 8;

        @JsonProperty("mirror_simulation_distance")
        private int mirrorSimulationDistance = 4;

        @JsonProperty("limit_chunk_loading_rate")
        private boolean limitChunkLoadingRate = true;
    }
}
