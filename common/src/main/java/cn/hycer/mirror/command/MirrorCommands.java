package cn.hycer.mirror.command;

import cn.hycer.mirror.core.MirrorInstanceManager;
import cn.hycer.mirror.core.MirrorProcess;
import cn.hycer.mirror.network.PlayerTransferManager;
import cn.hycer.mirror.sync.WorldSyncManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * 镜像实例指令模块。
 *
 * 主服侧：/mirror start|stop|status|sync|goto
 * 镜像侧：/mirror return
 */
public class MirrorCommands {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mirror");

    public static Component msg(String text) {
        return Component.literal("§8[§bMirror§8] " + text);
    }

    /** 主服侧指令注册 */
    public static void registerMainSide() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var mirror = literal("mirror")
                    .requires(src -> Commands.LEVEL_ALL.check(src.permissions()))
                    .then(literal("status").executes(MirrorCommands::showStatus))
                    .then(literal("start")
                            .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                            .executes(MirrorCommands::startMirror))
                    .then(literal("stop")
                            .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                            .executes(MirrorCommands::stopMirror))
                    .then(literal("restart")
                            .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                            .executes(MirrorCommands::restartMirror))
                    .then(literal("sync")
                            .executes(MirrorCommands::previewSync)
                            .then(literal("map").executes(MirrorCommands::syncMap))
                            .then(literal("config").executes(MirrorCommands::syncConfig))
                            .then(literal("mod").executes(MirrorCommands::syncMod)))
                    .then(literal("goto").executes(MirrorCommands::gotoMirror))
                    .then(literal("exec")
                            .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                            .then(argument("command", StringArgumentType.greedyString())
                                    .executes(MirrorCommands::execMirror)))
                    .then(literal("setServerProperties")
                            .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                            .then(argument("config", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        var cloner = MirrorInstanceManager.getInstance().getCloner();
                                        if (cloner != null) {
                                            for (String k : cloner.listMirrorServerPropertyKeys()) {
                                                builder.suggest(k);
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .then(argument("value", StringArgumentType.greedyString())
                                            .executes(MirrorCommands::setServerProperties))));

            dispatcher.register(mirror);
        });
    }

    /** 镜像侧指令注册（仅 return） */
    public static void registerMirrorSide() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("mirror")
                    .then(literal("return").executes(MirrorCommands::returnToMain)));
        });
    }

    // ===== 主服侧指令实现 =====

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var server = src.getServer();
        var mgr = MirrorInstanceManager.getInstance();
        var state = mgr.getState();
        var proc = mgr.getProcess();

        if (state == MirrorProcess.State.RUNNING && proc != null && proc.isReady()) {
            proc.queryPlayerCount(players -> server.execute(() -> {
                sendDetailedStatus(src, state, proc, players);
            }));
        } else {
            sendDetailedStatus(src, state, proc, null);
        }
        return 1;
    }

    private static void sendDetailedStatus(CommandSourceStack src,
                                           MirrorProcess.State state,
                                           MirrorProcess proc,
                                           String playerCount) {
        src.sendSystemMessage(msg("§6===== 镜像实例状态 ====="));
        src.sendSystemMessage(msg("  状态: §e" + stateLabel(state)));

        if (state == MirrorProcess.State.RUNNING && proc != null && proc.isReady()) {
            String pStr = playerCount != null ? playerCount : proc.getCachedPlayerCount();
            src.sendSystemMessage(msg("  玩家: §b" + pStr));

            long readyMs = proc.getReadyTimestamp();
            if (readyMs > 0) {
                long uptimeSec = Math.max(0, (System.currentTimeMillis() - readyMs) / 1000);
                src.sendSystemMessage(msg("  运行时间: §a" + formatUptime(uptimeSec)));
            }
        }

        if (state == MirrorProcess.State.ERROR) {
            src.sendSystemMessage(msg("§c提示: 镜像实例处于异常退出状态，可执行 §e/mirror start §c重新启动，或执行 §e/mirror stop §c重置状态。"));
        }
    }

    private static String formatUptime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02dh%02dm%02ds", h, m, s);
    }

    private static String stateLabel(MirrorProcess.State state) {
        return switch (state) {
            case STOPPED -> "已停止";
            case STARTING -> "启动中";
            case RUNNING -> "运行中";
            case STOPPING -> "停止中";
            case ERROR -> "错误 (异常退出)";
        };
    }

    private static int startMirror(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var mgr = MirrorInstanceManager.getInstance();

        if (mgr.isRunning()) {
            src.sendSystemMessage(msg("§c镜像实例已在运行。"));
            return 0;
        }

        src.sendSystemMessage(msg("§6正在启动镜像实例..."));
        var server = src.getServer();
        new Thread(() -> {
            // 启动完成（Done）后提示成功，失败则提示失败
            mgr.start(
                    () -> server.execute(() ->
                            src.sendSystemMessage(msg("§a镜像实例启动完成！"))),
                    () -> server.execute(() ->
                            src.sendSystemMessage(msg("§c镜像实例启动失败，请检查日志。")))
            );
        }, "Mirror-Start-Thread").start();
        return 1;
    }

    private static int stopMirror(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var mgr = MirrorInstanceManager.getInstance();

        if (!mgr.isRunning() && mgr.getState() != MirrorProcess.State.ERROR) {
            src.sendSystemMessage(msg("§c镜像实例未运行。"));
            return 0;
        }

        src.sendSystemMessage(msg("§6正在停止镜像实例..."));
        var server = src.getServer();
        // 进程退出后，通过 server.execute 调度回主线程发送完成提示
        mgr.stop(() -> server.execute(() ->
                src.sendSystemMessage(msg("§a镜像实例已停止。"))));
        src.sendSystemMessage(msg("§7已发送停止指令，镜像实例正在后台关闭..."));
        return 1;
    }

    private static int restartMirror(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var mgr = MirrorInstanceManager.getInstance();

        if (!mgr.isRunning() && mgr.getState() != MirrorProcess.State.ERROR) {
            src.sendSystemMessage(msg("§c镜像实例未运行。请先 /mirror start"));
            return 0;
        }

        src.sendSystemMessage(msg("§6正在重启镜像实例..."));
        var server = src.getServer();
        new Thread(() -> {
            // 同步停止（等待进程真正退出，避免端口未释放就启动新进程）
            mgr.stopAndWait();
            // 启动（等镜像服 Done 后提示完成）
            mgr.start(
                    () -> server.execute(() ->
                            src.sendSystemMessage(msg("§a镜像实例重启完成！"))),
                    () -> server.execute(() ->
                            src.sendSystemMessage(msg("§c镜像实例重启失败，请检查日志。")))
            );
        }, "Mirror-Restart-Thread").start();
        return 1;
    }

    private static int previewSync(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSystemMessage(msg(
                "§6同步命令：\n" +
                "  §e/mirror sync map §7— 仅同步地图（复制世界文件+热重载）\n" +
                "  §e/mirror sync config §7— 仅同步 config 目录（模组配置，重启镜像服）\n" +
                "  §e/mirror sync mod §7— 完整对齐 mods 目录（复制+覆盖+删除多余，重启镜像服）"));
        return 1;
    }

    private static int syncMap(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        WorldSyncManager.syncMap(src);
        return 1;
    }

    private static int syncConfig(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        WorldSyncManager.syncConfig(src);
        return 1;
    }

    private static int syncMod(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        WorldSyncManager.syncMod(src);
        return 1;
    }

    private static int gotoMirror(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendSystemMessage(msg("§c此命令只能由玩家执行。"));
            return 0;
        }
        var mgr = MirrorInstanceManager.getInstance();
        if (!mgr.isRunning()) {
            src.sendSystemMessage(msg("§c镜像实例未运行。请先 /mirror start"));
            return 0;
        }
        if (!mgr.isReady()) {
            src.sendSystemMessage(msg("§c镜像实例正在启动中，请稍后再试。"));
            return 0;
        }
        PlayerTransferManager.transferToMirror(player);
        return 1;
    }

    private static int execMirror(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        String cmd = StringArgumentType.getString(ctx, "command");
        var mgr = MirrorInstanceManager.getInstance();

        if (!mgr.isRunning()) {
            src.sendSystemMessage(msg("§c镜像实例未运行。请先 /mirror start"));
            return 0;
        }
        var proc = mgr.getProcess();
        if (proc == null) {
            src.sendSystemMessage(msg("§c镜像进程不可用。"));
            return 0;
        }

        // 命令执行线程：向镜像服 stdin 写入命令（非阻塞），结果在后台线程捕获后再调度回主线程转发
        var server = src.getServer();
        // 先开捕获窗口再发命令：镜像服输出可能在发送后立即到达，捕获未就位会漏掉开头
        final int timeoutMs = 5000;
        proc.captureOutput(timeoutMs, lines -> server.execute(() -> {
            if (lines.isEmpty()) {
                LOGGER.info("[Exec] /{} -> no output", cmd);
                src.sendSystemMessage(msg("§7(mirror) §e/" + cmd + "§7: 无输出"));
                return;
            }
            LOGGER.info("[Exec] /{} -> {}", cmd, String.join(" | ", lines));
            src.sendSystemMessage(msg("§7---- §e/" + cmd + " §7----"));
            for (String l : lines) {
                src.sendSystemMessage(Component.literal("§7" + l));
            }
            src.sendSystemMessage(msg("§7----------------"));
        }));
        if (!proc.sendCommand(cmd)) {
            proc.clearCapture(); // 发送失败，取消捕获窗口
            src.sendSystemMessage(msg("§c命令发送失败，请检查日志。"));
            return 0;
        }
        src.sendSystemMessage(msg("§7已发送至镜像服: §e/" + cmd));
        return 1;
    }

    private static int setServerProperties(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "config");
        String value = StringArgumentType.getString(ctx, "value");
        var mgr = MirrorInstanceManager.getInstance();
        var cloner = mgr.getCloner();

        if (cloner == null) {
            src.sendSystemMessage(msg("§c镜像实例未初始化。"));
            return 0;
        }
        var clonerKeys = cloner.listMirrorServerPropertyKeys();
        if (!clonerKeys.contains(key)) {
            src.sendSystemMessage(msg("§c配置项不存在: §e" + key
                    + "§c。可用:" + (clonerKeys.isEmpty() ? " 无" : "")));
            if (!clonerKeys.isEmpty()) {
                src.sendSystemMessage(msg("§7" + String.join("§7, §e", clonerKeys)));
            }
            return 0;
        }
        if (!cloner.setMirrorServerProperty(key, value)) {
            src.sendSystemMessage(msg("§c修改失败，请检查日志。"));
            return 0;
        }
        src.sendSystemMessage(msg("§a已修改镜像服配置 §e" + key + "§a = §e" + value));
        src.sendSystemMessage(msg("§7重启镜像服后生效（/mirror restart）"));
        return 1;
    }

    // ===== 镜像侧指令实现 =====

    private static int returnToMain(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendSystemMessage(msg("§c此命令只能由玩家执行。"));
            return 0;
        }
        PlayerTransferManager.transferToMain(player);
        return 1;
    }
}
