# Mirror — Fabric 单服务端伪独立镜像实例

在单个 Fabric 服务端进程中运行独立的镜像世界实例。玩家通过指令在服务端内部直接切换世界，无需断开连接、无需额外端口。

## 架构

```
┌──────────────────────────────────────────────────────┐
│                    JVM 进程                          │
│                                                      │
│  ┌──────────────────┐    ┌──────────────────────┐   │
│  │   主服世界         │    │   镜像世界             │   │
│  │   minecraft:      │    │   mirror:overworld   │   │
│  │   overworld       │    │   mirror:the_nether  │   │
│  │   the_nether      │    │   mirror:the_end     │   │
│  │   the_end         │    │                      │   │
│  │   数据: world/    │    │   数据: mirror_world/│   │
│  └────────┬─────────┘    └───────────┬──────────┘   │
│           │                          │               │
│           │  teleportTo (进程内传送)  │               │
│           │◀────────────────────────▶│               │
│           │                          │               │
│           │  WorldSyncManager        │               │
│           │  (FileChannel 复制)      │               │
│           │  + reloadWorlds 热重载   │               │
│                                                      │
│  共享: BuiltInRegistries, DataFixer, Services        │
│  隔离: Thread, ServerLevel, 世界数据                  │
│  Mixin: PortalRedirect (传送门), LevelTick (独立tick) │
└──────────────────────────────────────────────────────┘
```

## 快速开始

### 构建

```bash
export JAVA_HOME=/opt/jdk-25
./gradlew build
# JAR: mc-26.2/build/libs/mirror-mc26.2-0.1.0-Alpha.jar
```

### 安装

将 JAR 放入 `mods/` 目录，启动服务端。首次启动自动生成 `config/mirror/mirror.json`。

### 基本使用

```
/mirror start              # 启动镜像实例
/mirror sync confirm       # 同步主服世界 → 镜像
/mirror goto               # 进入镜像世界
/mirror return             # 返回主服
/mirror stop               # 停止镜像实例
```

## 指令参考

| 指令 | 权限 | 说明 |
|------|------|------|
| `/mirror status` | 所有人 | 查看镜像实例状态 |
| `/mirror start` | OP Lv3 | 启动镜像实例 |
| `/mirror stop` | OP Lv3 | 停止镜像实例 |
| `/mirror sync` | 所有人 | 预览同步信息 |
| `/mirror sync confirm` | 所有人 | 同步 + 热重载 |
| `/mirror sync incremental` | 所有人 | 增量同步（仅变更文件） |
| `/mirror goto` | 所有人 | 进入镜像世界（进程内传送） |
| `/mirror return` | 所有人 | 返回主服 |
| `/mirror test` | OP Lv3 | 诊断工具 |

## 配置文件

### config/mirror/mirror.json

```json
{
  "mirror": {
    "enabled": true,
    "world_path": "mirror_world",
    "sync": {
      "dimensions": ["overworld", "the_nether", "the_end"]
    }
  },
  "performance": {
    "mirror_view_distance": 8,
    "mirror_simulation_distance": 4
  }
}
```

## 玩家使用流程

```
管理员                          玩家
  │                              │
  │ /mirror sync confirm         │
  │ (世界数据 → mirror_world/)   │
  │                              │
  │                              │ /mirror goto
  │                              │ (进程内传送到镜像世界)
  │                              │
  │                              │ 在镜像世界游玩
  │                              │ 三个维度互通（独立传送门）
  │                              │
  │                              │ /mirror return
  │                              │ (传回主服原位置)
```

## 模块结构

```
common/src/main/java/cn/hycer/mirror/
├── Mirror.java                         # ModInitializer
├── command/MirrorCommands.java         # /mirror 指令
├── config/MirrorConfig.java            # JSON 配置
├── core/
│   ├── MirrorServer.java               # 镜像服务端（世界加载+独立tick）
│   └── MirrorInstanceManager.java      # 生命周期管理
├── network/
│   └── PlayerTransferManager.java      # 进程内玩家传送
├── sync/
│   └── WorldSyncManager.java           # 世界文件同步
└── mixin/
    ├── MirrorPortalRedirectMixin.java   # 传送门维度重定向
    └── MirrorLevelTickMixin.java        # 防止主服 tick 镜像世界
```

## 技术要点

### 镜像世界实现

MC 26.2 不支持构造第二个 `DedicatedServer`。采用轻量级方案：

- 共享主服 `BuiltInRegistries`、`DataFixer`、`Services`
- 通过 `LevelStorageSource.createAccess("mirror_world")` 创建独立世界存储
- 通过 `registryAccess().lookupOrThrow(LEVEL_STEM)` 获取维度模板
- 反射构造 3 个 `ServerLevel`（`mirror:overworld` / `mirror:the_nether` / `mirror:the_end`）
- 注册到主服 `levels` Map（`teleportTo` 可找到），由独立线程 tick

### 玩家传送

- `/mirror goto`：调用 `player.teleportTo(mirrorWorld)` 进程内切换维度
- 玩家连接不断开，背包/经验跟随
- 返回时恢复原坐标

### 传送门隔离

- `MirrorPortalRedirectMixin` 拦截 `Entity.teleportTo`
- 玩家在 `mirror:xxx` 维度时，自动将目标 `minecraft:xxx` 替换为 `mirror:xxx`
- 镜像内部三个维度传送门完全独立，不与主服串

### 独立 Tick

- `MirrorLevelTickMixin` 阻止主服 tick 循环处理镜像世界
- 镜像世界由 `MirrorServer` 的 `Mirror-Tick-Thread` 独立驱动（20 TPS）

### 世界同步

- `FileChannel.transferTo()` 高效复制 region 文件
- MC 26.x 路径：`dimensions/minecraft/<维度>/region/`
- 同步前自动执行 `saveEverything()` 强制刷盘
- 同步后 `MirrorServer.reloadWorlds()` 热重载

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

## 发布

推送 `V*` 格式的 tag 自动触发 GitHub Actions 构建 + git-cliff 生成更新日志 + Release。

```bash
git tag V0.1.0-Alpha
git push origin V0.1.0-Alpha
```

## JVM 参数建议

```
-XX:+UseZGC -Xms8G -Xmx16G
```
