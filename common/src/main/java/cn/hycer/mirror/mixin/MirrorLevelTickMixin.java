package cn.hycer.mirror.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 阻止主服 tick 镜像 ServerLevel。
 * 镜像世界由 MirrorServer 独立 tick。
 */
@Mixin(value = ServerLevel.class, remap = false)
public abstract class MirrorLevelTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void skipMirrorTick(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (isMirrorLevel(self)) {
            // Only tick if called from MirrorServer's thread
            String threadName = Thread.currentThread().getName();
            if (!threadName.equals("Mirror-Tick-Thread")) {
                ci.cancel();
            }
        }
    }

    private static boolean isMirrorLevel(ServerLevel level) {
        return level.dimension().toString().contains("mirror:");
    }
}
