package cn.hycer.mirror.mixin;

import cn.hycer.mirror.core.MirrorInstanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 拦截 teleportTo 的 target 参数：玩家在镜像维度时，将维度目标重定向到镜像维度。
 */
@Mixin(value = Entity.class, remap = false)
public abstract class MirrorPortalRedirectMixin {

    @ModifyVariable(
            method = "teleportTo",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true,
            remap = false
    )
    private ServerLevel redirectTarget(ServerLevel target) {
        if (!((Object) this instanceof ServerPlayer self)) return target;

        String currentDim = self.level().dimension().toString();
        if (!currentDim.contains("mirror:")) return target;

        String targetDim = target.dimension().toString();
        if (!targetDim.contains("minecraft:")) return target;

        ServerLevel mappedLevel = lookupMirrorLevel(
                targetDim.replace("minecraft:", "mirror:"));
        return mappedLevel != null ? mappedLevel : target;
    }

    private static ServerLevel lookupMirrorLevel(String dimStr) {
        try {
            var mirror = MirrorInstanceManager.getInstance().getMirrorServer();
            if (mirror == null) return null;
            if (dimStr.contains(":overworld")) return mirror.getOverworld();
            if (dimStr.contains(":the_nether")) return mirror.getNether();
            if (dimStr.contains(":the_end")) return mirror.getEnd();
        } catch (Exception ignored) {}
        return null;
    }
}
