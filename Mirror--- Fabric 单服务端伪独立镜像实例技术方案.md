---

# Mirror--- Fabric 单服务端伪独立镜像实例 —— 完整技术方案

## 一、方案概述

在单个 Fabric 服务端进程中，运行一个独立的从属 Minecraft 服务端实例作为镜像实验服。两个实例共享同一 JVM 进程，但拥有独立的游戏主线程、TPS、世界数据、玩家数据和配置文件。玩家通过主服指令透明切换到镜像实例，无需客户端断开重连，无需外部群组代理。

---

## 二、架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                        JVM 进程                              │
│                                                              │
│  ┌──────────────────────┐    ┌──────────────────────────┐   │
│  │   主服务端实例         │    │   镜像服务端实例           │   │
│  │                      │    │                          │   │
│  │  ServerThread-A      │    │  ServerThread-B          │   │
│  │  监听 0.0.0.0:25565  │    │  监听 127.0.0.1:25566    │   │
│  │                      │    │                          │   │
│  │  ┌────────────────┐  │    │  ┌────────────────────┐  │   │
│  │  │ 玩家转发模块    │◀─┼────┼─▶│ 握手响应模块        │  │   │
│  │  └────────────────┘  │    │  └────────────────────┘  │   │
│  │                      │    │                          │   │
│  │  ┌────────────────┐  │    │  ┌────────────────────┐  │   │
│  │  │ 世界同步模块    │──┼────┼─▶│ 世界重载模块        │  │   │
│  │  └────────────────┘  │    │  └────────────────────┘  │   │
│  │                      │    │                          │   │
│  │  世界目录: world/     │    │  世界目录: mirror_world/ │   │
│  │  配置: config/       │    │  配置: mirror_config/   │   │
│  │  玩家数据独立         │    │  玩家数据独立             │   │
│  └──────────────────────┘    └──────────────────────────┘   │
│                                                              │
│  共享：JVM 堆内存、文件系统、CPU 核心                          │
│  隔离：ServerThread、世界状态、玩家会话、配置                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 三、技术预研清单

> **重要：在正式编码前，必须逐项验证以下技术点。任何一项失败都将导致方案需要重新设计。**

### 预研项目 1：Fabric Loader 状态与二次初始化

| 项目 | 说明 |
| ------ | ------ |
| **目标** | 验证在同一 JVM 进程内，能否构造第二个 `MinecraftServer` 实例而不触发 Fabric Loader 的单例保护 |
| **风险** | Fabric Loader 在启动时设置全局状态（`FabricLoader.getInstance()` 返回单例），第二次调用 `FabricLoader.initialize()` 可能抛出异常 |
| **验证方法** | 编写测试模组，在服务端启动完成后，通过指令触发尝试 `new MinecraftServer(...)` 并观察日志和异常 |
| **关键检查点** | 1. `MinecraftServer` 构造函数是否可重复调用<br>2. `Registry` 等全局注册表是否在构造时被修改（导致主服数据污染）<br>3. `DataFixer` 是否被重新初始化<br>4. `SessionService` / `GameProfileRepository` 是否冲突 |
| **预期结论** | 可能需要绕过 Fabric Loader 的完整初始化，改用轻量级启动流程 |

### 预研项目 2：静态字段冲突扫描

| 项目 | 说明 |
| ------ | ------ |
| **目标** | 识别 Minecraft 源码和 Fabric API 中使用了 `static` 字段来存储“当前服务端实例”的类 |
| **风险** | 如果某个全局功能依赖 `static` 单例指向主服实例，镜像实例运行时会覆盖该单例，导致主服功能异常或崩溃 |
| **验证方法** | 1. 源码审计：搜索 `MinecraftServer` 类型被赋给 `static` 字段的位置<br>2. 运行时检测：在镜像实例运行期间，检查主服的 `MinecraftServer.getInstance()` 返回值是否被篡改 |
| **已知高风险区域** | `MinecraftServer` 自身（无静态 getInstance，构造时设置）<br>`SharedConstants`<br>`BuiltinRegistries`<br>`Fabric API 各模块的内部缓存`<br>`ThreadExecutor` 相关 |
| **预期结论** | 列出所有冲突点及绕过方案（包装器、ThreadLocal、独立类加载器） |

### 预研项目 3：独立类加载器可行性

| 项目 | 说明 |
| ------ | ------ |
| **目标** | 验证为镜像实例使用独立 `ClassLoader` 加载 Minecraft 类的可行性 |
| **风险** | Minecraft 类中大量使用 `static` 字段和全局注册表，两个类加载器加载的“同一个类”会被 JVM 视为不同类型，导致 `ClassCastException` |
| **验证方法** | 使用 `URLClassLoader` 加载 jar 中的 Minecraft 类，测试与主服的对象交互 |
| **预期结论** | 很可能不可行或代价极高，应优先选择 ThreadLocal 隔离方案 |

### 预研项目 4：网络层隔离与玩家转发

| 项目 | 说明 |
| ------ | ------ |
| **目标** | 验证主服能否在进程内将玩家的网络连接无缝转移到镜像实例 |
| **风险** | Minecraft 1.20.5+ 引入了 `Transfer` 包，但它是为跨服务器设计的。进程内转移可能需要绕过某些握手检查 |
| **验证方法** | 1. 验证 `ClientIntentionPackets` 和 Transfer 包在 Fabric 中的可用性<br>2. 测试主服通过 Transfer 包将客户端重定向到 `127.0.0.1:25566`<br>3. 测试客户端是否接受非加密连接的重定向<br>4. 验证玩家登录数据（UUID、皮肤）在两个实例中是否一致 |
| **关键检查点** | Transfer 包是否需要客户端 mod 配合<br>两个实例的 `online-mode` 设置如何协调<br>玩家退出主服和加入镜像实例之间的状态同步延迟 |
| **预期结论** | 使用 Transfer 包是标准路径；若有限制，可降级为本地代理转发 |

### 预研项目 5：世界数据热加载/卸载

| 项目 | 说明 |
| ------ | ------ |
| **目标** | 验证镜像实例能否在不重启的情况下，卸载当前世界并加载同步后的新世界 |
| **风险** | `MinecraftServer` 的 `WorldManager` 没有公开的热重载 API，需要 Mixin 注入 |
| **验证方法** | 在单机测试环境，通过 Mixin 调用内部方法实现世界卸载→替换文件→重新加载，测试稳定性和内存泄漏 |
| **预期结论** | 可行，但需要深度 Mixin，且要处理所有在线玩家（需先踢出或传送到临时维度） |

### 预研项目 6：GC 暂停对主服的影响

| 项目 | 说明 |
| ------ | ------ |
| **目标** | 量化评估镜像实例运行时，GC 暂停对主服 TPS 的实际影响 |
| **风险** | Full GC 导致主服卡顿 |
| **验证方法** | 1. 分配固定堆内存（如 8GB）<br>2. 模拟镜像实例加载大量区块<br>3. 使用 `-Xlog:gc*` 记录 GC 日志<br>4. 同时监控主服 TPS<br>5. 分别测试 G1GC、ZGC、Shenandoah |
| **预期结论** | ZGC 下 GC 暂停 < 1ms，主服基本无感知；需在配置中推荐 ZGC |

### 预研项目 7：模组兼容性

| 项目 | 说明 |
| ------ | ------ |
| **目标** | 确定哪些常见模组可以在镜像实例中安全加载 |
| **风险** | 某些模组的 Mixin 可能假设只有一个 `MinecraftServer` 实例 |
| **验证方法** | 选取生电服常用模组（Carpet、Lithium、Sodium 等），在镜像实例中加载并观察行为 |
| **预期结论** | 部分模组需要屏蔽或只在主服加载 |

---

## 四、核心模块设计

### 模块架构

```
mirror-instance/
├── mirror-core/          # 核心模块：镜像实例生命周期管理
├── mirror-network/       # 网络模块：玩家转发与连接管理
├── mirror-sync/          # 同步模块：世界数据复制与热重载
├── mirror-command/       # 指令模块：面向玩家的操作接口
└── mirror-config/        # 配置模块：独立配置管理
```

### 4.1 mirror-core：镜像实例生命周期管理

**职责**：负责镜像 `MinecraftServer` 实例的创建、启动、停止和状态监控。

```java
public class MirrorInstanceManager {
    private MinecraftServer mirrorServer;
    private Thread mirrorServerThread;
    private volatile MirrorState state = MirrorState.STOPPED;
    
    public enum MirrorState {
        STOPPED, STARTING, RUNNING, STOPPING, ERROR
    }
    
    /**
     * 启动镜像实例
     * 1. 创建独立的 SessionService、ResourceManager
     * 2. 初始化世界加载器，指向 mirror_world/ 目录
     * 3. 绑定到内部地址 127.0.0.1:mirror-port
     * 4. 在新线程中启动 ServerThread
     */
    public CompletableFuture<Boolean> start();
    
    /**
     * 安全停止镜像实例
     * 1. 踢出所有在线玩家
     * 2. 保存世界
     * 3. 停止 ServerThread
     * 4. 释放资源
     */
    public CompletableFuture<Void> stop();
    
    public MirrorState getState();
    public int getOnlinePlayerCount();
    public double getTPS();
}
```

**关键实现细节**：

```
1. MinecraftServer 构造参数：
   - 使用独立的线程池（Util.makeIoExecutor(1)）
   - 传入独立的 DataPackSettings
   - 使用独立的 world 目录 Path

2. 绕过 Fabric Loader 完整初始化：
   - 不调用 FabricLoader.initialize()
   - 手动调用需要的 Fabric API 初始化方法
   - 或使用条件判断跳过已初始化的模块

3. 静态字段保护：
   - 为关键静态字段使用 ThreadLocal 包装器
   - 在镜像实例代码路径中，通过 ThreadLocal 获取正确的实例引用
```

### 4.2 mirror-network：玩家转发与连接管理

**职责**：实现玩家在主服与镜像实例之间的透明切换。

```java
public class PlayerTransferManager {
    
    /**
     * 将玩家从主服转移到镜像实例
     * 
     * 流程：
     * 1. 在主服保存玩家状态（位置、生命值、背包等）
     * 2. 向客户端发送 Transfer 包 (Minecraft 1.20.5+)
     *    地址: 127.0.0.1 端口: mirror-port
     * 3. 镜像实例接收连接，验证并恢复玩家会话
     */
    public void transferToMirror(ServerPlayerEntity player);
    
    /**
     * 将玩家从镜像实例转移回主服
     * 流程同上，方向相反
     */
    public void transferToMain(ServerPlayerEntity player);
    
    /**
     * 进程内快速转移（优化路径）
     * 如果客户端支持且网络层允许，直接转移玩家对象引用
     * 避免实际的网络包往返
     */
    public void inProcessTransfer(ServerPlayerEntity player, boolean toMirror);
}
```

**Transfer 包实现要点**：

```
Minecraft 1.20.5+ 的 Transfer 机制：
- 服务端 → 客户端：发送 Transfer 包（目标地址+端口）
- 客户端自动重连到新地址
- 新服务端接收时，通过 login 包的 transfer 字段识别为转移连接
- 需要两个实例共享或交换玩家的 profile 和 session 数据

配置要点：
- 镜像实例使用 online-mode=false（如果主服是正版验证）
  或者通过自定义 GameProfileRepository 共享验证结果
- 两个实例的 encryption 设置需一致或兼容
```

### 4.3 mirror-sync：世界同步模块

**职责**：将主服世界数据复制到镜像实例世界，并支持热重载。

```java
public class WorldSyncManager {
    
    /**
     * 同步流程：
     * 1. 主服：save-all flush 强制刷盘
     * 2. 主服：暂停自动保存 (save-off)
     * 3. 主服：可选的区块加载冻结
     * 4. 复制 world/region/* → mirror_world/region/*
     *    复制 world/DIM-1/region/* → mirror_world/DIM-1/region/*
     *    复制 world/DIM1/region/* → mirror_world/DIM1/region/*
     * 5. 主服：恢复自动保存 (save-on)
     * 6. 镜像实例：卸载世界
     * 7. 镜像实例：重新加载世界
     * 8. 通知玩家同步完成
     */
    public CompletableFuture<SyncResult> syncWorlds();
    
    /**
     * 增量同步（可选优化）
     * 仅复制自上次同步以来变更的 region 文件
     * 通过比对文件的最后修改时间戳
     */
    public CompletableFuture<SyncResult> syncWorldsIncremental();
    
    public static class SyncResult {
        public long bytesCopied;
        public long durationMs;
        public boolean success;
        public String errorMessage;
    }
}
```

**文件复制策略**：

```java
// 使用 Java NIO 高效复制
public void copyRegionFiles(Path source, Path target) throws IOException {
    // 1. 确保目标目录存在
    Files.createDirectories(target);
    
    // 2. 删除目标中的旧 .mca 文件（但保留 level.dat 等）
    try (var stream = Files.newDirectoryStream(target, "*.mca")) {
        for (Path file : stream) {
            Files.delete(file);
        }
    }
    
    // 3. 使用 FileChannel 高效复制
    try (var sourceStream = Files.newDirectoryStream(source, "*.mca")) {
        for (Path sourceFile : sourceStream) {
            Path targetFile = target.resolve(sourceFile.getFileName());
            try (FileChannel src = FileChannel.open(sourceFile);
                 FileChannel dst = FileChannel.open(targetFile, 
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                src.transferTo(0, src.size(), dst);
            }
        }
    }
}
```

**镜像实例世界热重载**：

```java
// 需要 Mixin 注入到 WorldManager/SaveHandler
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    
    @Inject(method = "reloadWorld", at = @At("HEAD"), cancellable = true)
    private void onReloadWorld(CallbackInfo ci) {
        if (isMirrorInstance()) {
            // 自定义世界重载逻辑
            reloadMirrorWorld();
            ci.cancel();
        }
    }
    
    private void reloadMirrorWorld() {
        // 1. 将所有在线玩家传送到临时安全维度/坐标
        // 2. 关闭当前 World 的 ChunkManager
        // 3. 卸载 Dimension
        // 4. 重新创建 World 对象
        // 5. 加载 spawn 区块
        // 6. 将玩家传送回重生点
    }
}
```

### 4.4 mirror-command：指令模块

```java
public class MirrorCommands {
    
    // 主服指令
    public static final LiteralArgumentBuilder<ServerCommandSource> MIRROR =
        literal("mirror")
            // 查看镜像实例状态
            .then(literal("status")
                .executes(ctx -> showStatus(ctx.getSource())))
            
            // 同步世界数据
            .then(literal("sync")
                .executes(ctx -> previewSync(ctx.getSource()))
                .then(literal("confirm")
                    .executes(ctx -> executeSync(ctx.getSource())))
                .then(literal("incremental")
                    .executes(ctx -> executeIncrementalSync(ctx.getSource()))))
            
            // 传送到镜像实例
            .then(literal("goto")
                .executes(ctx -> transferToMirror(ctx.getSource())))
            
            // 从镜像实例返回（镜像实例中注册）
            .then(literal("return")
                .executes(ctx -> transferToMain(ctx.getSource())));
}
```

**权限控制**：

```json
{
  "mirror.command.status": "op_level_0",     // 所有人可查看状态
  "mirror.command.sync": "op_level_3",       // 仅管理员可同步
  "mirror.command.goto": "op_level_0",       // 所有人可进入
  "mirror.command.return": "op_level_0",     // 所有人可返回
  "mirror.player.limit": 3                    // 镜像实例最大玩家数
}
```

### 4.5 mirror-config：配置模块

**主服配置** (`config/mirror-instance.json`)：

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

**镜像实例配置** (`mirror_config/mirror_server.properties`)：

```properties
# 独立于主服的 server.properties
gamemode=creative
force-gamemode=true
difficulty=peaceful
allow-flight=true
spawn-protection=0
max-players=3
online-mode=false
server-ip=127.0.0.1
server-port=25566
level-name=mirror_world
view-distance=8
simulation-distance=4
```

---

## 五、玩家体验流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────┐
│  玩家操作     │     │  主服处理     │     │  镜像实例处理     │
└──────┬──────┘     └──────┬──────┘     └────────┬────────┘
       │                    │                     │
       │ /mirror sync       │                     │
       │ confirm            │                     │
       │───────────────────▶│                     │
       │                    │ 复制 world → mirror_world
       │                    │─────────────────────▶│
       │                    │                     │ 热重载世界
       │  同步完成提示      │◀────────────────────│
       │◀───────────────────│                     │
       │                    │                     │
       │ /mirror goto       │                     │
       │───────────────────▶│                     │
       │                    │ Transfer 包          │
       │  客户端自动重连     │─────────────────────▶│
       │═══════════════════════════════════════════│
       │                    │                     │ 进入镜像世界
       │                    │                     │ 创造模式
       │  在镜像世界实验...  │                     │
       │                    │                     │
       │ /mirror return     │                     │
       │════════════════════│                     │
       │                    │  Transfer 包         │
       │                    │◀────────────────────│
       │  客户端自动重连     │                     │
       │◀───────────────────│                     │
       │  回到主服生存模式   │                     │
```

---

## 六、错误处理与边界情况

| 场景 | 处理策略 |
| ------ | --------- |
| 镜像实例启动失败 | 向管理员发送告警；玩家 /mirror goto 时提示“镜像服务暂不可用” |
| 同步过程中玩家尝试进入镜像 | 返回提示“同步进行中，请稍后”，显示进度 |
| 镜像实例 OOM 崩溃 | 捕获异常，自动重启镜像实例（带指数退避），主服不受影响 |
| 玩家在镜像实例中卡死 | 镜像实例提供 `/mirror return` 强制返回，或在断线时自动踢回主服 |
| 磁盘空间不足导致同步失败 | 同步前检查可用空间，不足时拒绝操作并告警 |
| 镜像实例端口冲突 | 启动时检测，自动选择可用端口并更新配置 |
| Transfer 包客户端不支持 | 降级为提示玩家手动重连到 `127.0.0.1:25566` |

---

## 七、性能优化建议

| 优化项 | 说明 |
| -------- | ------ |
| **JVM 参数** | `-XX:+UseZGC -Xms8G -Xmx16G` 最小化 GC 暂停 |
| **镜像实例视距** | 独立配置，推荐 6-8 区块 |
| **区块加载限速** | 镜像实例限制每秒最大区块加载数，防止 IO 风暴 |
| **增量同步** | 默认开启，仅复制变更的 region 文件 |
| **内存上限** | 镜像实例使用独立的堆内存监控，超出阈值时触发告警或限流 |
| **CPU 亲和性** | 可选：将镜像实例线程绑定到特定 CPU 核心，避免缓存争抢 |

---

## 八、开发路线图

```
Phase 1: 核心验证（预研清单全部通过）
├── 1.1 Fabric Loader 二次初始化测试
├── 1.2 静态字段冲突扫描与修补
├── 1.3 最小化 MinecraftServer 实例构造 PoC
└── 1.4 双实例共存稳定性测试（72小时）

Phase 2: 基础功能
├── 2.1 mirror-core：镜像实例启动/停止生命周期
├── 2.2 mirror-config：独立配置加载
├── 2.3 mirror-command：基础指令（status, start, stop）
└── 2.4 自动化测试框架

Phase 3: 玩家转移
├── 3.1 mirror-network：Transfer 包转发
├── 3.2 玩家状态保存与恢复
├── 3.3 /mirror goto / /mirror return 指令
└── 3.4 断线/异常处理

Phase 4: 世界同步
├── 4.1 mirror-sync：完整同步流程
├── 4.2 增量同步
├── 4.3 镜像世界热重载（Mixin）
└── 4.4 同步前后钩子（备份、通知等）

Phase 5: 优化与稳定
├── 5.1 GC 调优与监控
├── 5.2 性能基准测试
├── 5.3 模组兼容性测试
└── 5.4 文档与用户指南
```

---

## 九、风险与备选方案

| 风险 | 概率 | 影响 | 备选方案 |
| ------ | :---: | ------ | --------- |
| Fabric Loader 无法二次初始化 | 中 | 阻断 | 回归单服多世界方案（方案C），接受线程共享 |
| Transfer 包不可靠 | 低 | 用户需手动重连 | 在聊天栏提示连接地址，客户端用 Quick Connect 模组辅助 |
| 静态字段污染无法完全解决 | 中 | 部分功能异常 | 使用独立类加载器加载镜像实例（增加内存开销） |
| GC 暂停影响主服体验 | 低 | 偶发卡顿 | 限制镜像实例内存上限；或最终回归独立进程方案B |
| 1.21.x API 变更阻断 | 低 | 需适配 | 持续跟进 Minecraft 快照，维护多版本兼容层 |

---

以上方案和预研清单可直接交付给开发 agent 进行实现。预研清单中的每一项验证结果将决定后续实现的具体路径，建议按 Phase 1 顺序逐一验证，每项出具通过/失败/需绕过的明确结论后再进入正式编码。
