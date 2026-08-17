# Mirror 项目交接文档

> 面向接手开发的 agent。本文档记录项目目标、当前实现状态、测试方法、实现细节，以及踩过的坑。

---

## 1. 项目概述

**项目**：mirror —— 一个 Fabric MC 26.2 服务端模组（mod_id=`mirror`，包名 `cn.hycer.mirror`）。

**目标**：在主服之外提供一个"镜像服"，玩家在游戏中通过 `/mirror goto` / `/mirror return` 无缝切换主服和镜像服：
- 玩家**无需手动断开重连**（Transfer 包自动切换）
- Xaero 地图数据不分裂（**实测发现 Xaero 数据跟玩家进入服务器的 SRV 域名绑定，不随 Transfer 地址变化**，所以直连方案即可满足，无需代理层）

**技术栈**：
- JDK 25（`/opt/jdk-25`，MC 26.2 硬性要求 Java 25）
- Gradle 9.5.0，Fabric Loom 1.17-SNAPSHOT
- MC 26.2 使用 Yarn v2 映射（类名是 intermediary 名，不是 Mojang 名）
- 构建产物：`mc-26.2/build/libs/mirror-mc26.2-0.2.0-Alpha.jar`

**开发机**：Linux `/home/hml/code/mirror/`
**用户生产/测试环境**：Windows `D:\MCCC\`（已解压一份到 `/home/hml/code/mirror/MCCC/` 用于本地测试，该目录已被 .gitignore 忽略）

---

## 2. 架构演进（重要背景）

| 阶段 | 方案 | 状态 |
|------|------|------|
| Phase 1-5 | 同进程双 ServerLevel（反射构造镜像世界） | ❌ 废弃（主服 tick 崩溃） |
| standalone-mirror | 独立进程镜像服（克隆 + ProcessBuilder） | ✅ 已完成，基本可用 |
| transfer-proxy | 代理层（主服识别 TRANSFER 并字节流透传到镜像服） | ❌ 废弃（实测发现直连方案即可，Xaero 跟 SRV 域名绑定） |

**当前分支**：
- `feat/standalone-mirror` — 独立进程镜像服（已稳定）
- `feat/transfer-proxy` — 代理层开发分支（代理层已废弃，代码已移除）

---

## 3. 当前架构（独立进程镜像服 + 直连 Transfer）

### 3.1 独立进程镜像服

主服通过模组克隆并控制一个**完全独立的镜像服 JVM 进程**：

```
主服 JVM                          镜像服 JVM（独立进程）
┌─────────────────┐               ┌─────────────────────┐
│ mirror 模组(完整)│               │ mirror 模组(镜像模式) │
│  克隆模块        │──复制──▶      │ 仅 /mirror return    │
│  进程控制        │─stdin/stdout─▶│ :25566              │
│  Transfer(goto) │               │                     │
└─────────────────┘               └─────────────────────┘
```

- 克隆：首次 `/mirror start` 时，把主服根目录的 jar（启动器+game jar）、`versions/`、`libraries/`、`.fabric/`、`mods/`、`config/`、`eula.txt` 复制到 `mirror/` 目录
- 生成镜像服 `server.properties`：仅覆盖 `server-port`、`level-name`、`server-ip`、`accepts-transfers=true`，其余（online-mode、view-distance、max-players 等）继承主服
- 进程控制：`ProcessBuilder` 启动 `java -Dmirror.instance=true -jar fabric-server-launch.jar nogui`，stdin 发命令、stdout 读日志
- 镜像模式自识别：`-Dmirror.instance=true` 时只注册 `/mirror return`

### 3.2 玩家切换（直连 Transfer）

```
goto:  主服 → Transfer(mirror_public_address:mirror_public_port) → 客户端直连镜像服
return: 镜像服 → Transfer(main_public_address:main_port) → 客户端直连主服
```

不需要代理层。玩家通过 SRV 域名进入主服，Xaero 数据跟该域名绑定，goto/return 不会改变 Xaero 的数据归属。

---

## 4. 代码结构（关键文件）

```
common/src/main/java/cn/hycer/mirror/
├── Mirror.java                    # 入口（init + 主服 accepts-transfers 自动开启）
├── config/MirrorConfig.java       # 配置（mirror.json）
├── command/MirrorCommands.java    # /mirror 指令（主服侧 + 镜像侧）
├── core/
│   ├── MirrorCloner.java          # 克隆主服到 mirror/ 目录
│   ├── MirrorProcess.java         # 进程控制（start/stop/sendCommand/isReady）
│   └── MirrorInstanceManager.java # 生命周期管理
├── network/
│   └── PlayerTransferManager.java # goto/return 发 Transfer 包
├── sync/WorldSyncManager.java     # sync map / sync config
└── mixin/MirrorOfflineProfileMixin.java # 镜像服离线模式玩家验证
```

---

## 5. 配置说明（config/mirror/mirror.json）

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
    "main_public_port": 25565,
    "auto_clone": true
  }
}
```

| 字段 | 说明 |
|------|------|
| `mirror_dir` | 镜像服根目录（相对主服运行目录） |
| `mirror_port` | 镜像服内网监听端口 |
| `mirror_public_address` | 镜像服公网地址（Transfer goto 目标，内网穿透填穿透域名） |
| `mirror_public_port` | 镜像服公网端口（goto 的 Transfer 目标端口，0=回退 mirror_port） |
| `main_public_address` | 主服公网地址（return 的 Transfer 目标） |
| `main_port` | 主服本地监听端口 |
| `main_public_port` | 主服公网端口（return 的 Transfer 目标端口，0=回退 main_port） |
| `auto_clone` | 首次 /mirror start 自动克隆 |

**用户生产环境**（已实测通过）：
- 主服域名 `play.mc.hycer.cn`，镜像服域名 `play.mirror.hycer.cn`
- 两个域名都通过内网穿透（frp）+ SRV 解析
- **关键**：Transfer 包不走 SRV 只走 A 记录，所以 `mirror_public_address` / `main_public_address` 需填能直接解析的地址（穿透地址/域名，而非只有 SRV 的裸域名）

---

## 6. 测试方法

### 6.1 本地启动主服

```bash
cd /home/hml/code/mirror/MCCC
# 确保使用 JDK 25
/opt/jdk-25/bin/java -Xmx2G -jar fabric-server-launch.jar nogui
```

启动脚本（已改好）：
- `start.sh`（Linux）：硬编码 `/opt/jdk-25/bin/java`
- `start.bat`（Windows）：优先 `JAVA_HOME`，需用户把 JAVA_HOME 指向 JDK 25

### 6.2 测试 goto/return

前置：删掉旧的 `mirror/` 目录（`rm -rf MCCC/mirror`）重新克隆。

1. 启动主服
2. 游戏内 `/mirror start`（首次会自动克隆 + 启动镜像服）
3. 等镜像服就绪（`/mirror status` 显示"运行中"，或日志出现 `[Process] Mirror server is ready`）
4. `/mirror goto` → 客户端直连镜像服公网地址，进入镜像服
5. `/mirror return` → 客户端直连主服公网地址，回到主服

**验证点**：
- goto 后玩家进入镜像服（世界是镜像服的 `mirror/world`）
- return 后玩家回到主服
- Xaero 地图数据不分裂（跟初始进入的 SRV 域名绑定）

### 6.3 测试镜像服独立功能

- `/mirror status` 显示状态（已停止/启动中/运行中/停止中/错误）
- `/mirror sync map`：同步主服地图到镜像服（save-all + 暂停 autosave + 跳过 session.lock）
- `/mirror sync config`：同步配置/模组后重启
- 主服关闭时镜像服同步关闭（SERVER_STOPPING + shutdown hook）

---

## 7. 实现细节（关键技术机制）

### 7.1 Transfer 识别机制（已查证 MC 26.2 源码）

- 客户端 transfer 时，握手包 `ClientIntentionPacket.intention` = `ClientIntent.TRANSFER`
- `ClientIntent` 枚举：`STATUS`(id=1)、`LOGIN`(id=2)、`TRANSFER`(id=3)
- 服务器在 `ServerHandshakePacketListenerImpl.handleIntention` 识别：
  - `LOGIN` → `beginLogin(packet, false)` 正常登录
  - `TRANSFER` → 检查 `acceptsTransfers()`，true 则 `beginLogin(packet, true)`

### 7.2 镜像服离线模式玩家验证（MirrorOfflineProfileMixin）

- 镜像服（`-Dmirror.instance=true`）且主服 offline-mode 时，跳过正常验证流程，直接用 GameProfile 验证玩家
- 保证离线模式下玩家能正常进入镜像服

### 7.3 进程控制（MirrorProcess）

- `start()`：ProcessBuilder 启动，`ready` 标志等 "Done" 信号才就绪
- `stop()`：异步（先发 stop 命令再 running=false，后台 waitFor 30s 超时强杀）
- `stopAndWait()`：同步版（sync 用）
- `isReady()`：MC 服务器是否 Done（goto 前检查）
- 子进程用 `java.home` 的 java（`System.getProperty("java.home") + "/bin/java"`），**不是 PATH 的 java**（否则 Java 版本不符）

### 7.4 关键坑（务必注意）

1. **accepts-transfers 双侧须 true**：否则"此服务器不接受转移"。镜像服克隆时强制 true，主服启动时 `setAcceptsTransfers(true)` 自动开启
2. **online-mode 继承主服**（不强制 true），保证验证模式一致
3. **镜像服启动未完成时 goto 会 Connection refused**：用 `isReady()`（"Done" 信号）检测
4. **sync map 文件锁冲突**：sync 前 `saveEverything(false,true,true)` + `setAutoSave(false)`，跳过 `session.lock`（主服 DirectoryLock 独占锁定），锁冲突文件容错跳过
5. **克隆要复制启动器+game jar+launcher 配置**：`fabric-server-launch.jar`（启动器）+ `server.jar`（game jar）+ `fabric-server-launcher.properties`（`serverJar=server.jar`），缺 game jar 会报 "Missing game jar"
6. **Mixin 用 remap=false**（无 mappings 模式），字段名用 intermediary 名
7. **测试后必须清理**：pkill 服务端进程 + 删 session.lock（用户硬性要求）

---

## 8. 编译/运行命令

```bash
# 编译
cd /home/hml/code/mirror
export JAVA_HOME=/opt/jdk-25
./gradlew build --no-daemon

# 产物
ls mc-26.2/build/libs/mirror-mc26.2-0.2.0-Alpha.jar

# 复制到测试环境
cp mc-26.2/build/libs/mirror-mc26.2-0.2.0-Alpha.jar MCCC/mods/

# 启动主服（本地测试）
cd /home/hml/code/mirror/MCCC
/opt/jdk-25/bin/java -Xmx2G -jar fabric-server-launch.jar nogui
```

**测试后务必清理**：pkill 服务端进程 + 删除 session.lock。

---

## 9. 环境信息

- JDK 25：`/opt/jdk-25`（默认 java 是 JDK 21，必须显式指定）
- MC 26.2 映射：Yarn v2（intermediary 名）
- 反编译查 API：`/opt/jdk-25/bin/javap -p -c -classpath <minecraft-server-deobf.jar> <类名>`
- minecraft jar：`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-server-deobf/26.2/minecraft-server-deobf-26.2.jar`
- 玩家测试账号：summer_37（UUID: f0737c77-86be-48a7-951b-11f3df1fb69a）

---

## 10. 待办清单

- [ ] 生产环境地址配置确认（用户内网穿透 + SRV 场景，已实测通过，待整理最终配置）
- [ ] 镜像服离线模式玩家验证（MirrorOfflineProfileMixin）实测确认
