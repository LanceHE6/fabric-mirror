package cn.hycer.mirror.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mirror 主配置，对应 config/mirror/mirror.json
 *
 * 独立进程镜像服方案，仅保留必要的控制配置：
 * - mirror_dir: 镜像服根目录（相对主服运行目录）
 * - mirror_port: 镜像服内网监听端口
 * - mirror_public_address: 镜像服公网地址（Transfer goto 目标，内网穿透填穿透域名）
 * - mirror_public_port: 镜像服公网端口（goto 的 Transfer 目标端口，0=回退 mirror_port）
 * - main_public_address: 主服公网地址（Transfer return 目标）
 * - main_port: 主服本地监听端口
 * - main_public_port: 主服公网端口（return 的 Transfer 目标端口，0=回退 main_port）
 *
 * 其余 server.properties 项（view-distance、max-players、gamemode 等）
 * 均继承主服配置，不在此重复配置。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MirrorConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(JsonParser.Feature.ALLOW_COMMENTS) // 容忍配置里的注释
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String CONFIG_FILE_NAME = "mirror/mirror.json";
    private static MirrorConfig INSTANCE;

    /**
     * 首次生成的默认配置模板，带注释解释每个配置项的默认值和作用。
     * 注意：加载时会用 Jackson 反序列化（容忍注释），字段缺失时用字段默认值补齐。
     */
    private static final String DEFAULT_CONFIG = """
            {
              "mirror": {
                // 是否启用 Mirror 模组（默认 true）
                "enabled": true,

                // 镜像服根目录，相对主服运行目录（默认 "mirror"）
                "mirror_dir": "mirror",

                // 镜像服内网监听端口（默认 25566）
                "mirror_port": 25566,

                // 镜像服公网地址，Transfer goto 目标；内网穿透填穿透域名（默认 "127.0.0.1"）
                "mirror_public_address": "127.0.0.1",

                // 镜像服公网端口，goto 的 Transfer 目标端口；0 = 回退 mirror_port（默认 0）
                "mirror_public_port": 0,

                // 主服公网地址，Transfer return 目标（默认 "127.0.0.1"）
                "main_public_address": "127.0.0.1",

                // 主服本地监听端口（默认 25565）
                "main_port": 25565,

                // 主服公网端口，return 的 Transfer 目标端口；0 = 回退 main_port（默认 0）
                "main_public_port": 0,

                // 首次 /mirror start 时自动克隆主服（默认 true）
                "auto_clone": true
              }
            }
            """;

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
                LOGGER.info("[Mirror] Config loaded from {}", file.getAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("[Mirror] Failed to load config, using defaults", e);
                INSTANCE = new MirrorConfig();
            }
        } else {
            // 首次生成带注释的默认配置（字段缺失时 Jackson 用字段默认值补齐，无需重写）
            try {
                file.getParentFile().mkdirs();
                Files.writeString(file.toPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
                INSTANCE = OBJECT_MAPPER.readValue(file, MirrorConfig.class);
                LOGGER.info("[Mirror] Created default config at {}", file.getAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("[Mirror] Failed to create default config", e);
                INSTANCE = new MirrorConfig();
            }
        }
        return INSTANCE;
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

    /** Main public port for the return Transfer target (0 = fallback to local listen port) */
    @JsonIgnore
    public int getMainPublicPort() { return mirror.mainPublicPort > 0 ? mirror.mainPublicPort : mirror.mainPort; }

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

        /** 镜像服公网端口（goto 的 Transfer 目标端口，0=回退 mirror_port） */
        @JsonProperty("mirror_public_port")
        private int mirrorPublicPort = 0;

        /** 主服本地监听端口 */
        @JsonProperty("main_port")
        private int mainPort = 25565;

        /** 主服公网地址（Transfer return 目标） */
        @JsonProperty("main_public_address")
        private String mainPublicAddress = "127.0.0.1";

        /** 主服公网端口（return 的 Transfer 目标端口，0=回退 main_port） */
        @JsonProperty("main_public_port")
        private int mainPublicPort = 0;

        /** 首次 /mirror start 时自动克隆主服 */
        @JsonProperty("auto_clone")
        private boolean autoClone = true;
    }
}
