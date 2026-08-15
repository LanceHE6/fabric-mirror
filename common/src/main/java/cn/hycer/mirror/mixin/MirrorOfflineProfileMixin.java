package cn.hycer.mirror.mixin;

import cn.hycer.mirror.config.MirrorConfig;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.StringUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class MirrorOfflineProfileMixin {

    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    private String requestedUsername;

    @Invoker("startClientVerification")
    public abstract void invokeStartClientVerification(GameProfile profile);

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true, remap = false)
    private void onHandleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!MirrorConfig.isMirrorInstance()) return;
        if (this.server.usesAuthentication()) return;
        if (!StringUtil.isValidPlayerName(packet.name())) return;
        this.requestedUsername = packet.name();
        invokeStartClientVerification(new GameProfile(packet.profileId(), packet.name()));
        ci.cancel();
    }
}