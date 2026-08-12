# Mirror — Fabric 单服务端伪独立镜像实例

在单个 Fabric 服务端进程中运行一个独立的从属 Minecraft 服务端实例作为镜像实验服。玩家通过指令透明切换，无需客户端断开重连，无需外部群组代理。

## 架构

```
┌──────────────────────────────────────────────────────┐
│                    JVM 进程                          │
│                                                      │
│  ┌──────────────────┐    ┌──────────────────────┐   │
│  │   主服务端        │    │   镜像实例 (MirrorServer)│   │
│  │   端口: 25565     │    │   端口: 25566          │   │
│  │   世界: world/    │    │   世界: mirror_world/  │   │
│  │   配置: config/   │    │   配置: config/mirror/ │   │
│  └────────┬─────────┘    └───────────┬──────────┘   │
│           │                          │               │
│           │  WorldSyncManager        │               │
│           │  (文件复制+热重载) ──────▶│               │
│           │                          │               │
│           │  Transfer 包             │               │
│           │◀────────────────────────▶│               │
│                                                      │
│  共享: BuiltInRegistries, DataFixer, Services        │
│  隔离: Thread, ServerLevel, PlayerList, Network       │
└──────────────────────────────────────────────────────┘
```

## 快速开始

### 构建

```bash
# JDK 25 + Gradle 9.5.0
export JAVA_HOME=/opt/jdk-25
./gradlew build
# JAR 输出: mc-26.2/build/libs/mirror-mc26.2-0.1.0-Alpha.jar
```

### 安装

将 JAR 放入服务端 `mods/` 目录，启动服务端。

首次启动后会自动生成配置文件 `config/mirror/mirror.json`。

### 启用

配置文件 `config/mirror/mirror.json` 中设置 `enabled: true`（默认），或使用指令控制：

```
/mirror start     # 启动镜像实例
/mirror stop      # 停止镜像实例
/mirror status    # 查看状态
```

## 指令参考

| 指令 | 权限 | 说明 |
|------|------|------|
| `/mirror status` | 所有人 | 查看镜像实例状态（运行态/玩家数/TPS） |
| `/mirror start` | OP Lv3 | 启动镜像实例（加载世界+网络监听） |
| `/mirror stop` | OP Lv3 | 安全停止镜像实例 |
| `/mirror sync` | 所有人 | 预览世界同步信息 |
| `/mirror sync confirm` | 所有人 | 完整同步主服世界→镜像+热重载 |
| `/mirror sync incremental` | 所有人 | 增量同步（仅变更的 region 文件） |
| `/mirror goto` | 所有人 | 传送到镜像实例 |
| `/mirror return` | 所有人 | 从镜像返回主服 |
| `/mirror test` | OP Lv3 | Phase 1 诊断工具 |

## 配置文件

### config/mirror/mirror.json

```json
{
  "mirror": {
    "enabled": true,
    "world_path": "mirror_world",
    "config_path": "mirror_config",
    "port": 25566,
    "bind_address": "127.0.0.1",
    "max_players": 3,
    "sync": {
      "dimensions": ["overworld", "the_nether", "the_end"],
      "backup_before_sync": true,
      "pause_autosave_during_sync": true
    }
  },
  "performance": {
    "mirror_view_distance": 8,
    "mirror_simulation_distance": 4,
    "limit_chunk_loading_rate": true
  }
}
```

### config/mirror_config/mirror_server.properties（可选）

镜像实例的独立 `server.properties`。如不存在则使用默认值。

```properties
gamemode=creative
difficulty=peaceful
allow-flight=true
max-players=3
online-mode=false
```

## 玩家使用流程

```
管理员                        玩家
  │                            │
  │ /mirror sync confirm       │
  │ (主服世界 → 镜像)            │
  │                            │
  │                            │ /mirror goto
  │                            │ (客户端自动重连到镜像)
  │                            │
  │                            │ 在镜像世界游玩...
  │                            │
  │                            │ /mirror return
  │                            │ (客户端自动重连回主服)
  │                            │ 实验数据不保留
```

## 模块结构

```
common/src/main/java/cn/hycer/mirror/
├── Mirror.java                       # ModInitializer 入口
├── command/MirrorCommands.java       # /mirror 指令树
├── config/MirrorConfig.java          # Jackson 嵌套 JSON 配置
├── core/
│   ├── MirrorServer.java             # 轻量级镜像服务端
│   ├── MirrorInstanceManager.java    # 生命周期管理
│   ├── AutoValidator.java            # Phase 1 自动验证器
│   └── ValidationCommands.java       # /mirror test 诊断命令
├── network/MirrorNetworkHandler.java # 网络监听 + Transfer 包
└── sync/WorldSyncManager.java        # 世界文件复制（全量/增量）
```

## 技术要点

### 轻量级 MirrorServer

MC 26.2 的 `DedicatedServer` 构造器需要 `WorldStem` 对象，该对象无公开工厂方法。采用轻量级方案：

- 共享主服 `BuiltInRegistries`、`DataFixer`、`Services`
- 通过 `LevelStorageSource.createDefault(Path).createAccess(levelName)` 创建独立世界存储
- 通过 `registryAccess().lookupOrThrow(LEVEL_STEM)` 获取维度 LevelStem
- 反射构造 3 个 `ServerLevel`（overworld/nether/end）
- 独立 Tick 循环线程（20 TPS）
- 独立网络监听（`ServerConnectionListener`）

### 世界同步

- 使用 `FileChannel.transferTo()` 高效复制 region 文件
- 支持完整同步和增量同步（按文件修改时间）
- 同步完成后触发 `MirrorServer.reloadWorlds()` 热重载

### 玩家转移

- MC 1.20.5+ Cookie-based Transfer 机制
- `ClientboundTransferPacket(host, port)` 重定向客户端

## 构建环境

| 组件 | 版本 |
|------|------|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.154.0+26.2 |
| Fabric Loom | 1.17.x |
| Gradle | 9.5.0 |
| JDK | 25 |
| Mappings | 无（intermediary 自动生成） |

## JVM 参数建议

```
-XX:+UseZGC -Xms8G -Xmx16G
```

ZGC 下 GC 暂停 < 1ms，主服基本无感知镜像实例的 GC 活动。
