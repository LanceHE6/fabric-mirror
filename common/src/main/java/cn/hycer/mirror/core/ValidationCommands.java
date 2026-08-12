package cn.hycer.mirror.core;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static net.minecraft.commands.Commands.literal;

/**
 * Phase 1 验证工具 — 研究 Fabric Loader 是否支持二次 MinecraftServer 构造。
 */
public class ValidationCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    /**
     * 将 Phase 1 验证子命令添加到现有的 /mirror 节点下。
     */
    public static void addToNode(
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> mirror) {
        var test = literal("test")
                .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                .then(literal("init")
                        .executes(ValidationCommands::testFabricInit))
                .then(literal("static")
                        .executes(ValidationCommands::scanStaticFields))
                .then(literal("construct")
                        .executes(ValidationCommands::testConstruction))
                .then(literal("classloader")
                        .executes(ValidationCommands::testClassLoader))
                .then(literal("network")
                        .executes(ValidationCommands::testNetwork))
                .then(literal("world")
                        .executes(ValidationCommands::testWorldReload))
                .then(literal("gc")
                        .executes(ValidationCommands::testGC))
                .then(literal("mods")
                        .executes(ValidationCommands::testModCompatibility));

        mirror.then(test);
    }

    /**
     * 预研 1: Fabric Loader 二次初始化测试。
     */
    private static int testFabricInit(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSystemMessage(Component.literal("§6===== [预研 1] Fabric Loader 二次初始化测试 ====="));

        // 1. 获取当前服务器实例
        MinecraftServer currentServer = src.getServer();
        src.sendSystemMessage(Component.literal("§a[✓] 当前主服实例: " + currentServer.getClass().getName()));
        src.sendSystemMessage(Component.literal("  Server thread: " + currentServer.getRunningThread().getName()));

        // 2. 检查 FabricLoader 单例
        try {
            Class<?> flClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            var getInstance = flClass.getMethod("getInstance");
            Object flInstance = getInstance.invoke(null);
            src.sendSystemMessage(Component.literal("§a[✓] FabricLoader.getInstance() 可访问"));
        } catch (Exception e) {
            src.sendSystemMessage(Component.literal("§c[✗] FabricLoader 访问失败: " + e.getMessage()));
        }

        // 3. 分析 MinecraftServer 构造函数
        Constructor<?>[] constructors = currentServer.getClass().getDeclaredConstructors();
        src.sendSystemMessage(Component.literal("\n§eMinecraftServer 构造函数:"));
        for (int i = 0; i < constructors.length; i++) {
            Constructor<?> ctor = constructors[i];
            ctor.setAccessible(true);
            String params = describeParameters(ctor.getParameterTypes());
            String mods = Modifier.toString(ctor.getModifiers());
            src.sendSystemMessage(Component.literal("  [" + i + "] " + mods + " (" + params + ")"));
        }

        // 4. 检查关键全局注册表
        src.sendSystemMessage(Component.literal("\n§e关键全局注册表:"));
        try {
            Field regField = net.minecraft.core.registries.BuiltInRegistries.class.getDeclaredField("REGISTRY");
            src.sendSystemMessage(Component.literal("  BuiltInRegistries.REGISTRY: 存在"));
        } catch (Exception e) {
            src.sendSystemMessage(Component.literal("  BuiltInRegistries: §e" + e.getMessage()));
        }

        src.sendSystemMessage(Component.literal("\n§6===== 预研 1 分析完成 ====="));
        return 1;
    }

    /**
     * 预研 2: 静态字段冲突扫描。
     */
    private static int scanStaticFields(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSystemMessage(Component.literal("§6===== [预研 2] 静态字段冲突扫描 ====="));

        List<String> riskyFields = new ArrayList<>();
        scanClassFields(MinecraftServer.class, riskyFields);
        scanClassFields(net.minecraft.SharedConstants.class, riskyFields);

        // Fabric API 内部缓存
        try {
            scanClassFields(Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents"), riskyFields);
        } catch (Exception ignored) {}

        if (riskyFields.isEmpty()) {
            src.sendSystemMessage(Component.literal("§a未发现高风险静态字段。"));
        } else {
            src.sendSystemMessage(Component.literal("§e发现 " + riskyFields.size() + " 个可能冲突的静态字段:"));
            for (String f : riskyFields) {
                src.sendSystemMessage(Component.literal(f));
            }
        }

        src.sendSystemMessage(Component.literal("\n§6===== 预研 2 扫描完成 ====="));
        return 1;
    }

    /**
     * 预研 3: MinecraftServer 构造 PoC。
     */
    private static int testConstruction(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSystemMessage(Component.literal("§6===== [预研 3] MinecraftServer 二次构造分析 ====="));

        MinecraftServer mainServer = src.getServer();
        Constructor<?>[] ctors = mainServer.getClass().getDeclaredConstructors();

        for (Constructor<?> ctor : ctors) {
            ctor.setAccessible(true);
            Class<?>[] paramTypes = ctor.getParameterTypes();
            src.sendSystemMessage(Component.literal("\n§e构造器 " + ctor.getParameterCount() + " 参数:"));

            List<String> replicable = new ArrayList<>();
            List<String> shared = new ArrayList<>();
            for (Class<?> type : paramTypes) {
                String name = type.getSimpleName();
                if (type.isPrimitive() || type == String.class
                        || type == java.nio.file.Path.class || type == java.io.File.class
                        || type.getName().contains("Executor")
                        || type == java.util.concurrent.CompletableFuture.class) {
                    replicable.add(name + " (可独立创建)");
                } else {
                    shared.add(name);
                }
            }
            if (!replicable.isEmpty()) {
                src.sendSystemMessage(Component.literal("  §a独立: " + String.join(", ", replicable)));
            }
            if (!shared.isEmpty()) {
                src.sendSystemMessage(Component.literal("  §c共享: " + String.join(", ", shared)));
            }
        }

        src.sendSystemMessage(Component.literal("\n§6===== 预研 3 分析完成 ====="));
        return 1;
    }

    private static int testClassLoader(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSystemMessage(Component.literal("§6===== [预研 4] 独立类加载器可行性 ====="));
        src.sendSystemMessage(Component.literal("§e预研 4 尚未实现。"));
        return 1;
    }

    private static int testNetwork(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSystemMessage(Component.literal("§6===== [预研 5] 网络层隔离与 Transfer 包 ====="));
        src.sendSystemMessage(Component.literal("§e预研 5 尚未实现。"));
        return 1;
    }

    private static int testWorldReload(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSystemMessage(Component.literal("§6===== [预研 6] 世界数据热加载/卸载 ====="));
        src.sendSystemMessage(Component.literal("§e预研 6 尚未实现。"));
        return 1;
    }

    private static int testGC(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSystemMessage(Component.literal("§6===== [预研 7] GC 影响评估 ====="));
        src.sendSystemMessage(Component.literal("§e预研 7 尚未实现。"));
        return 1;
    }

    private static int testModCompatibility(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSystemMessage(Component.literal("§6===== [预研 7] 模组兼容性 ====="));
        src.sendSystemMessage(Component.literal("§e预研 7 模组兼容性尚未实现。"));
        return 1;
    }

    // --- helpers ---

    private static void scanClassFields(Class<?> clazz, List<String> results) {
        Field[] fields = clazz.getDeclaredFields();
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers())) {
                String type = f.getType().getSimpleName();
                if (type.contains("Server") || type.contains("Instance") || type.contains("Manager")) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(null);
                        results.add("  " + clazz.getSimpleName() + "." + f.getName()
                                + " (" + type + ") = " + val);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static String describeParameters(Class<?>[] types) {
        List<String> names = new ArrayList<>();
        for (Class<?> t : types) {
            names.add(t.getSimpleName());
        }
        return String.join(", ", names);
    }
}
