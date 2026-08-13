package cn.hycer.mirror.network;

import cn.hycer.mirror.config.MirrorConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家转移管理器。
 * 使用 MC 1.20.5+ 官方 Transfer 包，把玩家连接重定向到镜像服或主服。
 */
public class PlayerTransferManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    /**
     * 将玩家从主服转移到镜像服（代理层方案）。
     * 发送 Transfer 包，目标填镜像服 transfer 地址（A 记录指向主服公网入口），
     * 客户端连回主服后由主服识别 TRANSFER 意图并桥接到镜像服。
     */
    public static boolean transferToMirror(ServerPlayer player) {
        MirrorConfig config = MirrorConfig.getInstance();
        String host = config.getMirrorTransferHost();
        if (host == null || host.isEmpty()) {
            // 未配置 mirror_transfer_host 时回退到直连镜像服
            host = config.getPublicAddress();
        }
        int port = config.getMainPort();

        player.connection.send(new ClientboundTransferPacket(host, port));
        LOGGER.info("[Transfer] {} → mirror ({}:{})", player.getName().getString(), host, port);
        return true;
    }

    /**
     * 将玩家从镜像服转移回主服。
     * 发送 Transfer 包，客户端自动重连到主服公网地址。
     */
    public static boolean transferToMain(ServerPlayer player) {
        MirrorConfig config = MirrorConfig.getInstance();
        String host = config.getMainPublicAddress();
        int port = config.getMainPort();

        player.connection.send(new ClientboundTransferPacket(host, port));
        LOGGER.info("[Transfer] {} → main ({}:{})", player.getName().getString(), host, port);
        return true;
    }
}
