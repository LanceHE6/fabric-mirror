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
 *
 * 独立进程镜像服方案：
 * - mirror.dir: 镜像服根目录（相对主服运行目录）
 * - public_address: 镜像服公网地址（Transfer goto 目标，内网穿透场景填穿透域名）
 * - main_public_address/main_port: 主服公网地址（Transfer return 目标）
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
                // 补全新字段并写回（旧配置自动迁移到新格式）
                INSTANCE.saveConfig();
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

    // ===== Delegating accessors =====

    @JsonIgnore
    public boolean isEnabled() { return mirror.enabled; }
    @JsonIgnore
    public String getMirrorDir() { return mirror.dir; }
    @JsonIgnore
    public int getPort() { return mirror.port; }
    @JsonIgnore
    public String getPublicAddress() { return mirror.publicAddress; }
    @JsonIgnore
    public String getMainPublicAddress() { return mirror.mainPublicAddress; }
    @JsonIgnore
    public int getMainPort() { return mirror.mainPort; }
    @JsonIgnore
    public int getMaxPlayers() { return mirror.maxPlayers; }
    @JsonIgnore
    public boolean isAutoClone() { return mirror.autoClone; }

    @JsonIgnore
    public List<String> getSyncDimensions() { return mirror.sync.dimensions; }
    @JsonIgnore
    public boolean isBackupBeforeSync() { return mirror.sync.backupBeforeSync; }

    @JsonIgnore
    public int getMirrorViewDistance() { return performance.mirrorViewDistance; }
    @JsonIgnore
    public int getMirrorSimulationDistance() { return performance.mirrorSimulationDistance; }

    /**
     * 判断当前运行的是否为镜像服（通过 JVM 属性 -Dmirror.instance=true）。
     */
    @JsonIgnore
    public static boolean isMirrorInstance() {
        return Boolean.getBoolean("mirror.instance");
    }

    // ===== Section classes =====

    public static class MirrorSection {
        @JsonProperty("enabled")
        private boolean enabled = true;

        /** 镜像服根目录（相对主服运行目录） */
        @JsonProperty("dir")
        private String dir = "mirror";

        /** 镜像服监听端口 */
        @JsonProperty("port")
        private int port = 25566;

        /** 镜像服公网地址（Transfer goto 目标，内网穿透填穿透域名） */
        @JsonProperty("public_address")
        private String publicAddress = "127.0.0.1";

        /** 主服公网地址（Transfer return 目标） */
        @JsonProperty("main_public_address")
        private String mainPublicAddress = "127.0.0.1";

        /** 主服公网端口 */
        @JsonProperty("main_port")
        private int mainPort = 25565;

        @JsonProperty("max_players")
        private int maxPlayers = 3;

        /** 首次 /mirror start 时自动克隆主服 */
        @JsonProperty("auto_clone")
        private boolean autoClone = true;

        @JsonProperty("sync")
        private SyncSection sync = new SyncSection();
    }

    public static class SyncSection {
        @JsonProperty("dimensions")
        private List<String> dimensions = Arrays.asList("overworld", "the_nether", "the_end");

        @JsonProperty("backup_before_sync")
        private boolean backupBeforeSync = true;
    }

    public static class PerformanceSection {
        @JsonProperty("mirror_view_distance")
        private int mirrorViewDistance = 8;

        @JsonProperty("mirror_simulation_distance")
        private int mirrorSimulationDistance = 4;
    }
}
