# Mirror — Fabric 独立进程镜像服

在主服务端进程中运行一个模组，由模组克隆并控制一个**完全独立的镜像服务端进程**。玩家通过 Minecraft 原生 Transfer 包在主服和镜像服之间无缝切换。

## 架构

```
┌─────────────────────────┐        ┌──────────────────────────────┐
│  主服 JVM (主服务端)      │        │  镜像服 JVM (独立进程)        │
│                         │        │                              │
│  mirror 模组 (主服模式)  │        │  mirror 模组 (镜像模式)        │
│  ├ MirrorCloner  克隆   │──复制──▶│  server.jar + mods/ + config/ │
│  ├ MirrorProcess 控制   │─stdin──▶│  :25566                      │
│  │                 stdout◀─日志──│                              │
│  ├ /mirror goto (Transfer)──────▶│  仅 /mirror return            │
│  └ WorldSyncManager 同步 │        │                              │
└─────────────────────────┘        └──────────────────────────────┘
```

镜像服是一个**标准 Fabric 服务端**，运行在 `mirror/` 目录，监听独立端口，由主服模组通过 stdin/stdout 完全控制。

## 快速开始

### 构建

```bash
export JAVA_HOME=/opt/jdk-25
./gradlew build
# JAR: mc-26.2/build/libs/mirror-mc26.2-0.1.0-Alpha.jar
```

### 安装

将 JAR 放入主服 `mods/` 目录，启动主服。

### 使用

```
/mirror start              # 首次自动克隆主服 + 启动镜像服
/mirror goto               # 玩家进入镜像服（Transfer 包）
/mirror return             # 玩家返回主服（镜像服内执行）
/mirror sync map           # 同步地图（复制世界 + 重启镜像服）
/mirror sync config        # 同步配置/模组（重新克隆 + 重启）
/mirror stop               # 停止镜像服
/mirror status             # 查看状态
```

## 指令参考

| 指令 | 位置 | 权限 | 说明 |
|------|------|------|------|
| `/mirror status` | 主服 | 所有人 | 查看镜像实例状态 |
| `/mirror start` | 主服 | OP Lv3 | 启动镜像服（首次自动克隆） |
| `/mirror stop` | 主服 | OP Lv3 | 停止镜像服 |
| `/mirror goto` | 主服 | 所有人 | Transfer 到镜像服 |
| `/mirror sync` | 主服 | 所有人 | 预览同步命令 |
| `/mirror sync map` | 主服 | 所有人 | 同步地图 |
| `/mirror sync config` | 主服 | 所有人 | 同步配置/模组 |
| `/mirror return` | 镜像服 | 所有人 | Transfer 回主服 |

## 配置

### config/mirror/mirror.json

```json
{
  "mirror": {
    "enabled": true,
    "mirror_dir": "mirror",
    "mirror_port": 25566,
    "mirror_public_address": "127.0.0.1",
    "mirror_public_port": 25566,
    "main_public_address": "127.0.0.1",
    "main_port": 25565,
    "auto_clone": true
  }
}
```

关键配置项：
- `mirror_dir`: 镜像服根目录（相对主服运行目录）
- `mirror_port`: 镜像服内网监听端口
- `mirror_public_address`: 镜像服公网地址（**内网穿透场景填穿透域名**，Transfer goto 目标）
- `mirror_public_port`: 镜像服公网端口（goto 的 Transfer 目标端口，0=回退 mirror_port）
- `main_public_address`/`main_port`: 主服公网地址（Transfer return 目标）
- `mirror_transfer_host`: 镜像服 transfer 地址（代理层 goto 用，留空则直连镜像服公网地址）

其余 server.properties 项（online-mode、max-players、view-distance、gamemode 等）均**继承主服配置**，无需在此重复配置。

## 两种运行模式

同一份 mirror.jar，通过 JVM 参数自识别身份：

| 功能 | 主服模式 | 镜像模式（`-Dmirror.instance=true`） |
|------|---------|--------------------------------------|
| `/mirror start/stop` | ✅ | ❌ |
| `/mirror sync` | ✅ | ❌ |
| `/mirror goto` | ✅ | ❌ |
| `/mirror return` | ❌ | ✅ |
| `/mirror status` | ✅ | ❌ |

镜像服进程由主服的 `MirrorProcess` 通过 `-Dmirror.instance=true` 启动，自动进入镜像模式。

## 玩家转移（Transfer 包）

使用 MC 1.20.5+ 官方 `ClientboundTransferPacket(host, port)`：

- `/mirror goto`：主服发 Transfer 包 → 客户端自动重连镜像服公网地址
- `/mirror return`：镜像服发 Transfer 包 → 客户端自动重连主服公网地址

**验证模式**：镜像服 `online-mode` 继承主服设置（不强制覆盖），保证两者验证模式一致。正版验证下玩家重连时重新走 Mojang 验证，UUID 一致；离线模式下玩家直接进入。keypair 为会话级临时密钥，每次启动随机生成，无需共享。

## 世界同步

- **sync map**：停止镜像服 → 复制主服 `world/` → 重启镜像服
- **sync config**：停止镜像服 → 重新克隆配置/模组 → 重启镜像服
- 同步前会停止镜像服，避免玩家在线时数据覆盖

## 模块结构

```
common/src/main/java/cn/hycer/mirror/
├── Mirror.java                    # ModInitializer（主服/镜像模式分发）
├── command/MirrorCommands.java    # /mirror 指令（主服侧 + 镜像侧）
├── config/MirrorConfig.java       # JSON 配置 + 镜像模式自识别
├── core/
│   ├── MirrorCloner.java          # 克隆主服 → mirror/ 目录
│   ├── MirrorProcess.java         # ProcessBuilder 进程控制
│   └── MirrorInstanceManager.java # 生命周期管理
├── network/
│   └── PlayerTransferManager.java # Transfer 包玩家转移
└── sync/
    └── WorldSyncManager.java      # 世界同步（map/config）
```

## 构建环境

| 组件 | 版本 |
|------|------|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.154.0+26.2 |
| Fabric Loom | 1.17.x |
| Gradle | 9.5.0 |
| JDK | 25 |

## 发布

推送 `V*` 格式 tag 自动触发 GitHub Actions 构建 + git-cliff 更新日志 + Release。

## JVM 参数建议

主服：
```
-XX:+UseZGC -Xms8G -Xmx16G
```

镜像服由模组启动，内存通过 `MirrorProcess` 配置（默认 `-Xms2G -Xmx4G`）。
