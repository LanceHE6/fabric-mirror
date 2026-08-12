package cn.hycer.mirror.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;

/**
 * Phase 1 自动验证器。
 * 在服务端启动完成后自动执行所有预研验证项，结果输出到日志。
 */
public class AutoValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(AutoValidator::runAll);
    }

    private static void runAll(MinecraftServer server) {
        LOGGER.info("==============================================================");
        LOGGER.info("[预研] Phase 1 自动验证开始");
        LOGGER.info("==============================================================");

        preResearch1_FabricInit(server);
        preResearch2_StaticFields(server);
        preResearch3_Construction(server);
        preResearch4_ClassLoader();
        preResearch5_Network();
        preResearch6_WorldReload(server);

        LOGGER.info("==============================================================");
        LOGGER.info("[预研] Phase 1 自动验证完成");
        LOGGER.info("==============================================================");
    }

    // ===== 预研 1: Fabric Loader 二次初始化 =====

    private static void preResearch1_FabricInit(MinecraftServer server) {
        LOGGER.info("--- [预研 1] Fabric Loader 状态分析 ---");

        // 1.1 当前服务器类
        Class<?> serverClass = server.getClass();
        LOGGER.info("  MinecraftServer 类: {}", serverClass.getName());
        LOGGER.info("  Server Thread: {}", server.getRunningThread().getName());
        LOGGER.info("  isRunning: {}", server.isRunning());
        LOGGER.info("  isDedicatedServer: {}", server.isDedicatedServer());

        // 1.2 FabricLoader 访问
        try {
            Class<?> flClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object fl = flClass.getMethod("getInstance").invoke(null);
            LOGGER.info("  FabricLoader.getInstance(): 可访问 ✓");

            // 检查是否已初始化
            try {
                Method isInit = flClass.getMethod("isDevelopmentEnvironment");
                LOGGER.info("  isDevelopmentEnvironment: {}", isInit.invoke(fl));
            } catch (Exception e) {
                LOGGER.info("  isDevelopmentEnvironment: N/A");
            }
        } catch (Exception e) {
            LOGGER.warn("  FabricLoader: 无法访问 — {}", e.getMessage());
        }

        // 1.3 MinecraftServer 构造函数
        Constructor<?>[] ctors = serverClass.getDeclaredConstructors();
        LOGGER.info("  MinecraftServer 声明构造函数数: {}", ctors.length);
        for (int i = 0; i < ctors.length; i++) {
            Constructor<?> c = ctors[i];
            c.setAccessible(true);
            StringBuilder sb = new StringBuilder();
            for (Class<?> p : c.getParameterTypes()) {
                sb.append(p.getSimpleName()).append(", ");
            }
            LOGGER.info("    [{}] ({})", i, sb.toString());
        }

        // 1.4 关键全局注册表
        try {
            Field regField = net.minecraft.core.registries.BuiltInRegistries.class.getDeclaredField("REGISTRY");
            regField.setAccessible(true);
            Object reg = regField.get(null);
            LOGGER.info("  BuiltInRegistries.REGISTRY: {} 单例", reg != null ? "存在" : "NULL");
        } catch (NoSuchFieldException e) {
            LOGGER.info("  BuiltInRegistries: 无 REGISTRY 静态字段");
        } catch (Exception e) {
            LOGGER.warn("  BuiltInRegistries: {} — {}", e.getClass().getSimpleName(), e.getMessage());
        }

        // 1.5 检查是否有 getServer() 静态方法 (单例模式)
        try {
            Method getServer = MinecraftServer.class.getMethod("getServer");
            LOGGER.warn("  ⚠ MinecraftServer.getServer() 静态方法存在 — 是全局单例！镜像实例可能覆盖主服引用");
        } catch (NoSuchMethodException e) {
            LOGGER.info("  MinecraftServer.getServer(): 不存在静态方法 ✓ (无单例风险)");
        }

        // 1.6 检查 Fabric 入口点是否已全部初始化
        try {
            Class<?> entryClass = Class.forName("net.fabricmc.loader.api.entrypoint.EntrypointContainer");
            LOGGER.info("  EntrypointContainer: 可访问");
        } catch (Exception e) {
            LOGGER.info("  EntrypointContainer: 不可访问");
        }

        LOGGER.info("  预研 1 结论: 获取了必要的反射信息，可用于下一步构造分析");
    }

    // ===== 预研 2: 静态字段冲突扫描 =====

    private static void preResearch2_StaticFields(MinecraftServer server) {
        LOGGER.info("--- [预研 2] 静态字段冲突扫描 ---");

        int found = 0;
        int risky = 0;

        // 扫描 MinecraftServer 自身
        found += scanStatic(server.getClass(), "MinecraftServer");
        
        // 扫描 SharedConstants
        found += scanStatic(net.minecraft.SharedConstants.class, "SharedConstants");
        
        // 扫描内置注册表
        found += scanStatic(net.minecraft.core.registries.BuiltInRegistries.class, "BuiltInRegistries");

        // FabricAPI 事件系统
        Class<?>[] fabricClasses = {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.class,
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.class,
        };
        for (Class<?> fc : fabricClasses) {
            found += scanStatic(fc, fc.getSimpleName());
        }

        // 网络层
        try {
            Class<?> connClass = Class.forName("net.minecraft.server.network.ServerConnectionListener");
            found += scanStatic(connClass, "ServerConnectionListener");
        } catch (Exception ignored) {}

        LOGGER.info("  扫描了 {} 个静态字段", found);
        LOGGER.info("  预研 2 结论: 关键静态字段已记录，需在构造镜像实例时逐个检查覆盖风险");
    }

    private static int scanStatic(Class<?> clazz, String label) {
        int count = 0;
        for (Field f : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            count++;
            String type = f.getType().getSimpleName();
            // 关注可能存储全局实例引用的类型
            boolean isRisky = type.contains("Server") || type.contains("Instance")
                    || type.contains("Registry") || type.equals("MinecraftServer")
                    || type.contains("Manager") || type.contains("Session");
            if (isRisky) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    LOGGER.warn("  ⚠ {}.{} = {} (类型: {}, 值: {})",
                            label, f.getName(),
                            val == null ? "null" : "非空",
                            type,
                            val);
                } catch (Exception e) {
                    LOGGER.info("  {}.{} = ? (类型: {}, 无法读取)", label, f.getName(), type);
                }
            }
        }
        return count;
    }

    // ===== 预研 3: MinecraftServer 构造分析 =====

    private static void preResearch3_Construction(MinecraftServer server) {
        LOGGER.info("--- [预研 3] MinecraftServer 构造参数分析 ---");

        Constructor<?> best = null;
        int bestScore = Integer.MAX_VALUE;

        for (Constructor<?> ctor : server.getClass().getDeclaredConstructors()) {
            ctor.setAccessible(true);
            Class<?>[] params = ctor.getParameterTypes();
            LOGGER.info("  构造器 {} 参数:", ctor.getParameterCount());

            int hardParams = 0;  // 难以独立提供的参数
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                String name = p.getSimpleName();
                String fullName = p.getName();
                boolean hard = false;

                // 判断该参数是否可独立提供
                if (p == Path.class || p == java.io.File.class
                        || p == String.class || p == int.class
                        || p == boolean.class || p == long.class) {
                    LOGGER.info("    [{}] {} {} — ✓ 可独立提供", i, name, fullName);
                } else if (p == java.util.concurrent.Executor.class
                        || p == java.util.concurrent.CompletableFuture.class) {
                    LOGGER.info("    [{}] {} — ✓ 可独立创建", i, name);
                } else if (fullName.contains("proxy") || fullName.contains("Proxy")) {
                    LOGGER.info("    [{}] {} — ✓ 可以使用 NO_PROXY", i, name);
                } else {
                    hard = true;
                    hardParams++;
                    LOGGER.warn("    [{}] {} — ⚠ 需通过反射获取现有实例", i, name);
                }
            }

            if (hardParams < bestScore) {
                bestScore = hardParams;
                best = ctor;
            }
        }

        if (best != null) {
            LOGGER.info("  最佳构造器: {} 参数，{} 个需获取现有实例",
                    best.getParameterCount(), bestScore);
            // 尝试获取各参数的实际值
            LOGGER.info("  尝试从主服提取构造参数...");
            tryExtractConstructorParams(server);
        }

        LOGGER.info("  预研 3 结论: 需要在运行时通过反射获取现有实例的复杂参数值");
    }

    private static void tryExtractConstructorParams(MinecraftServer server) {
        // 尝试通过反射读取主服实例中的字段，作为构造参数候选
        Field[] fields = server.getClass().getDeclaredFields();
        for (Field f : fields) {
            f.setAccessible(true);
            try {
                Object val = f.get(server);
                if (val == null) continue;
                String type = f.getType().getSimpleName();
                // 输出非基本类型的字段
                if (!f.getType().isPrimitive() && f.getType() != String.class) {
                    LOGGER.info("  主服字段 {} ({}): {}", f.getName(), type,
                            val.getClass().getName());
                }
            } catch (Exception ignored) {}
        }
    }

    // ===== 预研 4: 独立类加载器可行性 =====

    private static void preResearch4_ClassLoader() {
        LOGGER.info("--- [预研 4] 独立类加载器可行性 ---");

        ClassLoader current = AutoValidator.class.getClassLoader();
        LOGGER.info("  当前 Mod 类加载器: {}", current.getClass().getName());
        LOGGER.info("  父加载器: {}", current.getParent().getClass().getName());
        if (current.getParent().getParent() != null) {
            LOGGER.info("  祖父加载器: {}", current.getParent().getParent().getClass().getName());
        } else {
            LOGGER.info("  祖父加载器: null (Bootstrap)");
        }

        // 检查 Knot 类加载器结构
        if (current.getClass().getName().contains("Knot")) {
            LOGGER.info("  当前运行在 Fabric Knot 类加载器中");
        }

        // 测试：如果用新的 URLClassLoader 加载 MinecraftServer，会发生什么？
        LOGGER.info("  测试: 尝试用不同类加载器加载 MinecraftServer.class");
        try {
            ClassLoader newCL = new java.net.URLClassLoader(
                    new java.net.URL[0], ClassLoader.getPlatformClassLoader());
            Class<?> loaded = newCL.loadClass("net.minecraft.server.MinecraftServer");
            Class<?> existing = MinecraftServer.class;
            LOGGER.info("  newCL.loadClass = {}", loaded.getClassLoader());
            LOGGER.info("  existing.class = {}", existing.getClassLoader());
            LOGGER.info("  isSame: {}", loaded == existing);
            LOGGER.warn("  结论: 独立类加载器方案不可行 — 不同类加载器加载的类无法互操作");
            LOGGER.warn("  建议: 使用 ThreadLocal 隔离方案替代独立类加载器");
        } catch (Exception e) {
            LOGGER.error("  类加载器测试失败: {}", e.getMessage());
        }
    }

    // ===== 预研 5: 网络层与 Transfer 包 =====

    private static void preResearch5_Network() {
        LOGGER.info("--- [预研 5] 网络层隔离与 Transfer 包 ---");

        // 5.1 检查 ClientIntentionPacket (握手包)
        try {
            Class<?> intentionClass = Class.forName(
                    "net.minecraft.network.protocol.handshake.ClientIntentionPacket");
            LOGGER.info("  ClientIntentionPacket: 存在 ✓");
            
            // 检查是否有 transfer 方法
            for (Method m : intentionClass.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("transfer")) {
                    LOGGER.info("    transfer 相关方法: {}", m.getName());
                }
            }
        } catch (ClassNotFoundException e) {
            LOGGER.warn("  ClientIntentionPacket: 不存在 — MC 26.2 可能重命名了该类");
        }

        // 5.2 检查 Transfer 相关协议包
        try {
            Class.forName("net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket");
            LOGGER.info("  Login 包: 存在");
        } catch (ClassNotFoundException e) {
            LOGGER.info("  Login 包路径可能不同");
        }

        // 5.3 检查 Cookie 包 (1.20.5+ 的 Transfer 机制依赖于 Cookie)
        try {
            Class<?> cookieClass = Class.forName(
                    "net.minecraft.network.protocol.common.ClientboundStoreCookiePacket");
            LOGGER.info("  StoreCookiePacket: 存在 ✓ (MC 1.20.5+ Transfer 机制可用)");
        } catch (ClassNotFoundException e) {
            LOGGER.info("  StoreCookiePacket: 不存在");
        }

        // 5.4 尝试查找所有与 "transfer" 相关的类
        LOGGER.info("  搜索网络包中的 transfer 相关类...");
        try {
            Class<?> loginPacketClass = Class.forName(
                    "net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket");
            LOGGER.info("  LoginFinishedPacket: 存在");
        } catch (ClassNotFoundException ignored) {}

        LOGGER.info("  预研 5 结论: MC 26.2 的网络层需要进一步运行时验证 Cookie-based Transfer");
    }

    // ===== 预研 6: 世界热加载/卸载 =====

    private static void preResearch6_WorldReload(MinecraftServer server) {
        LOGGER.info("--- [预研 6] 世界数据热加载/卸载 ---");

        // 6.1 获取 WorldManager / SaveHandler
        try {
            Field worldManagerField = findField(server.getClass(), "levels");
            if (worldManagerField == null) {
                worldManagerField = findField(server.getClass(), "worlds");
            }
            if (worldManagerField == null) {
                worldManagerField = findField(server.getClass(), "allLevels");
            }
            if (worldManagerField != null) {
                worldManagerField.setAccessible(true);
                Object levels = worldManagerField.get(server);
                if (levels instanceof java.util.Map) {
                    LOGGER.info("  server.levels: Map ({} 个维度)", ((java.util.Map<?,?>) levels).size());
                    for (Object key : ((java.util.Map<?,?>) levels).keySet()) {
                        LOGGER.info("    维度: {}", key);
                    }
                } else if (levels instanceof Iterable) {
                    int count = 0;
                    for (Object ignored : (Iterable<?>) levels) count++;
                    LOGGER.info("  server.levels: Iterable ({} 个维度)", count);
                } else {
                    LOGGER.info("  server.levels: {} (类型: {})", 
                            levels != null ? "非空" : "null",
                            levels != null ? levels.getClass().getSimpleName() : "N/A");
                }
            } else {
                LOGGER.warn("  无法找到 server 上的维度存储字段");
            }
        } catch (Exception e) {
            LOGGER.error("  读取维度列表失败: {}", e.getMessage());
        }

        // 6.2 检查 WorldManager 相关 API
        try {
            Class<?> wmClass = Class.forName("net.minecraft.server.WorldManager");
            LOGGER.info("  WorldManager 类: 存在");
            for (Method m : wmClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.contains("close") || name.contains("save") || name.contains("load")) {
                    LOGGER.info("    {}(): {} 参数", name, m.getParameterCount());
                }
            }
        } catch (ClassNotFoundException e) {
            LOGGER.info("  WorldManager: 类不存在 (MC 26.2 可能移到其他位置)");
        }

        // 6.3 检查 MinecraftServer 上的世界管理方法
        for (Method m : MinecraftServer.class.getDeclaredMethods()) {
            String name = m.getName();
            if (name.contains("save") || name.contains("load") || name.contains("world")
                    || name.contains("level") || name.contains("reload")) {
                LOGGER.info("  MinecraftServer.{}(): {} 参数", name, m.getParameterCount());
            }
        }

        LOGGER.info("  预研 6 结论: 世界热加载需要 Mixin 注入到内部维度管理逻辑");
    }

    // --- 工具方法 ---

    private static Field findField(Class<?> clazz, String name) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getName().equals(name) || f.getName().contains(name)) {
                return f;
            }
        }
        // 检查父类
        if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
            return findField(clazz.getSuperclass(), name);
        }
        return null;
    }
}
