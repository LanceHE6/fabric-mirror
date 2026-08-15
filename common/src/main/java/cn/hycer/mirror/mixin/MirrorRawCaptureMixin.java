package cn.hycer.mirror.mixin;

import cn.hycer.mirror.proxy.RawByteCaptor;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在连接激活时，把原始字节捕获器安装到管线头部。
 *
 * 必须在任何 MC 解码器（splitter/decoder）之前，才能捕获到客户端发来的
 * 握手包 + 紧随其后的登录包等原始字节，供 Transfer 桥接完整回放。
 */
@Mixin(Connection.class)
public abstract class MirrorRawCaptureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    @Inject(method = "channelActive", at = @At("HEAD"), remap = false)
    private void onChannelActive(ChannelHandlerContext ctx, CallbackInfo ci) {
        ctx.pipeline().addFirst("mirror_captor", new RawByteCaptor());
        // LOGGER.info("[Proxy] RawByteCaptor installed, pipeline={}", ctx.pipeline().names());
    }
}
