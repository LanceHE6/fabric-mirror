package cn.hycer.mirror.mixin;

import cn.hycer.mirror.config.MirrorConfig;
import cn.hycer.mirror.proxy.TransferBridge;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截握手阶段的 TRANSFER 意图，把"去镜像服"的连接桥接到镜像服。
 *
 * 区分规则（第一版，hostName）：
 * - 握手包 hostName 匹配镜像服的 transfer 地址 → 桥接到镜像服
 * - 否则（return 回主服）→ 走默认登录流程
 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class MirrorTransferProxyMixin {

    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleIntention", at = @At("HEAD"), cancellable = true, remap = false)
    private void onIntention(ClientIntentionPacket packet, CallbackInfo ci) {
        // 镜像服自身不拦截 TRANSFER：主服桥接过来的连接会带着 TRANSFER 握手包，
        // 若镜像服也走桥接逻辑，会把连接又桥回自己（127.0.0.1:port）造成死循环，玩家登录超时。
        if (MirrorConfig.isMirrorInstance()) {
            return;
        }

        if (packet.intention() != ClientIntent.TRANSFER) {
            return; // 只处理 TRANSFER 意图
        }

        MirrorConfig config = MirrorConfig.getInstance();
        String host = packet.hostName();
        String mirrorHost = config.getMirrorTransferHost();

        // hostName 匹配镜像服 transfer 地址 → 桥接到镜像服（goto）
        if (mirrorHost != null && !mirrorHost.isEmpty() && mirrorHost.equalsIgnoreCase(host)) {
            ci.cancel();
            TransferBridge.bridge(connection, packet, "127.0.0.1", config.getPort());
        }
        // 否则是 return 回主服，走默认 beginLogin(transferred=true)
    }
}
