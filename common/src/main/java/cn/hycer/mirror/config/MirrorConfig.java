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

/**
 * Mirror 主配置，对应 config/mirror/mirror.json
 *
 * 独立进程镜像服方案，仅保留必要的控制配置：
 * - mirror_dir: 镜像服根目录（相对主服运行目录）
 * - mirror_port: 镜像服内网监听端口
 * - mirror_public_address: 镜像服公网地址（Transfer goto 目标，内网穿透填穿透域名）
 * - mirror_public_port: 镜像服公网端口（goto 的 Transfer 目标端口，0=回退 mirror_port）
 * - main_public_address/main_port: 主服公网地址（Transfer return 目标）
 * - mirror_transfer_host: 镜像服 transfer 地址（代理层 goto 用，留空则直连镜像服公网地址）
 *
 * 其余 server.properties 项（view-distance、max-players、gamemode 等）
 * 均继承主服配置，不在此重复配置。
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
    public String getMirrorDir() { return mirror.mirrorDir; }

    @JsonIgnore
    public int getPort() { return mirror.mirrorPort; }

    @JsonIgnore
    public String getPublicAddress() { return mirror.mirrorPublicAddress; }

    @JsonIgnore
    public String getMainPublicAddress() { return mirror.mainPublicAddress; }

    @JsonIgnore
    public int getMainPort() { return mirror.mainPort; }

    /** 镜像服 transfer 地址（goto 时 Transfer 目标，A 记录指向主服公网入口，用于主服识别 goto） */
    @JsonIgnore
    public String getMirrorTransferHost() { return mirror.mirrorTransferHost; }

    /** Mirror public port for the goto Transfer target (0 = fallback to local listen port) */
    @JsonIgnore
    public int getMirrorPublicPort() { return mirror.mirrorPublicPort > 0 ? mirror.mirrorPublicPort : mirror.mirrorPort; }

    @JsonIgnore
    public boolean isAutoClone() { return mirror.autoClone; }

    /**
     * 判断当前运行的是否为镜像服（通过 JVM 属性 -Dmirror.instance=true）。
     */
    @JsonIgnore
    public static boolean isMirrorInstance() {
        return Boolean.getBoolean("mirror.instance");
    }

    // ===== Section class =====

    public static class MirrorSection {
        @JsonProperty("enabled")
        private boolean enabled = true;

        /** 镜像服根目录（相对主服运行目录） */
        @JsonProperty("mirror_dir")
        private String mirrorDir = "mirror";

        /** 镜像服监听端口 */
        @JsonProperty("mirror_port")
        private int mirrorPort = 25566;

        /** 镜像服公网地址（Transfer goto 目标，内网穿透填穿透域名） */
        @JsonProperty("mirror_public_address")
        private String mirrorPublicAddress = "127.0.0.1";

        /** 主服公网地址（Transfer return 目标） */
        @JsonProperty("main_public_address")
        private String mainPublicAddress = "127.0.0.1";

        /** 主服公网端口 */
        @JsonProperty("main_port")
        private int mainPort = 25565;

        /** 镜像服 transfer 地址（goto 时 Transfer 目标，A 记录指向主服公网入口） */
        @JsonProperty("mirror_transfer_host")
        private String mirrorTransferHost = "";

        /** Mirror public port (frp-mapped public port used as the goto Transfer target port; 0 = fallback to "port") */
        @JsonProperty("mirror_public_port")
        private int mirrorPublicPort = 0;

        /** 首次 /mirror start 时自动克隆主服 */
        @JsonProperty("auto_clone")
        private boolean autoClone = true;
    }
}
