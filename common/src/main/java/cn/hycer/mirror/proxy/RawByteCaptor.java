package cn.hycer.mirror.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原始字节捕获器。
 *
 * 安装在客户端连接管线的头部（splitter 之前），把客户端发来的原始 TCP 字节
 * （握手包 + 登录包 + ...）原样累积下来。用于 Transfer 桥接时把「已收到的字节」
 * 完整回放给镜像服，避免登录包在管线切换过程中丢失。
 *
 * 该 handler 只复制字节、不消费，原始字节继续沿管线向后传递（正常解码）。
 */
public class RawByteCaptor extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    private ByteBuf captured = Unpooled.buffer();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            // 复制原始字节（保留 readerIndex 不变，向前传递原对象）
            captured.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
            LOGGER.info("[Proxy] captor +{} bytes (total {})", buf.readableBytes(), captured.readableBytes());
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * 取走已捕获的字节（所有权转移给调用方），并清空内部缓冲。
     */
    public ByteBuf takeCapturedBytes() {
        LOGGER.info("[Proxy] takeCapturedBytes, captured={} bytes", captured.readableBytes());
        ByteBuf result = captured;
        captured = Unpooled.buffer();
        return result;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        LOGGER.info("[Proxy] captor handlerRemoved, captured={} bytes", captured.readableBytes());
        captured.release();
        captured = Unpooled.buffer();
        super.handlerRemoved(ctx);
    }
}
