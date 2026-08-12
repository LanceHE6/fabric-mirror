package cn.hycer.mirror.network;

import cn.hycer.mirror.core.MirrorInstanceManager;
import cn.hycer.mirror.core.MirrorServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家转移管理器 — 使用 teleportTo 传送到镜像世界。
 */
public class PlayerTransferManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");
    private static final Map<UUID, TransferRecord> records = new ConcurrentHashMap<>();

    private record TransferRecord(
            double x, double y, double z, float yaw, float pitch, ServerLevel fromLevel) {}

    public static boolean transferToMirror(ServerPlayer player) {
        var mgr = MirrorInstanceManager.getInstance();
        var mirror = mgr.getMirrorServer();
        if (mirror == null || mirror.getState() != MirrorServer.State.RUNNING) {
            player.sendSystemMessage(Component.literal("§c镜像实例未运行"));
            return false;
        }
        ServerLevel mirrorWorld = mirror.getOverworld();
        if (mirrorWorld == null) {
            player.sendSystemMessage(Component.literal("§c镜像世界未加载"));
            return false;
        }

        ServerLevel currentLevel = (ServerLevel) player.level();
        records.put(player.getUUID(), new TransferRecord(
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), currentLevel));

        // Use standard teleportTo — it handles dimension change properly
        player.teleportTo(mirrorWorld,
                player.getX(), player.getY(), player.getZ(),
                Set.of(), player.getYRot(), player.getXRot(), false);

        mirror.addPlayer(player);
        player.sendSystemMessage(Component.literal("§a已进入镜像实例！§e/mirror return §a返回主服。"));
        LOGGER.info("[Transfer] {} → mirror ({})", player.getName().getString(),
                mirrorWorld.dimension());
        return true;
    }

    public static boolean transferToMain(ServerPlayer player) {
        TransferRecord r = records.remove(player.getUUID());
        ServerLevel target;
        double x, y, z;
        float yaw, pitch;

        if (r != null) {
            target = r.fromLevel(); x = r.x(); y = r.y(); z = r.z();
            yaw = r.yaw(); pitch = r.pitch();
        } else {
            target = ((ServerLevel) player.level()).getServer().overworld();
            x = 0.5; y = 64; z = 0.5; yaw = 0; pitch = 0;
        }

        var mirror = MirrorInstanceManager.getInstance().getMirrorServer();
        if (mirror != null) mirror.removePlayer(player.getUUID());

        player.teleportTo(target, x, y, z, Set.of(), yaw, pitch, false);
        player.sendSystemMessage(Component.literal("§a已返回主服！"));
        LOGGER.info("[Transfer] {} → main", player.getName().getString());
        return true;
    }
}
