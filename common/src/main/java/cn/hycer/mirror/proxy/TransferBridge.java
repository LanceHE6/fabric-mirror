package cn.hycer.mirror.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Transfer 桥接器。
 *
 * 主服作为唯一公网入口，识别客户端 TRANSFER 意图后，把连接以字节流方式
 * 透传到镜像服（127.0.0.1:25566），实现单域名单端口下的主服/镜像服切换。
 *
 * 原理：客户端 transfer 连接在握手阶段（intention=TRANSFER）被识别，
 * 主服重编码握手包回放给镜像服，之后双向透传原始 TCP 字节。
 */
public class TransferBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    private static Field connectionChannelField;

    static {
        try {
            connectionChannelField = Connection.class.getDeclaredField("channel");
            connectionChannelField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            LOGGER.error("[Proxy] Cannot access Connection.channel field", e);
        }
    }

    /**
     * 桥接一个 TRANSFER 连接：建立到镜像服的连接并双向透传。
     *
     * @param clientConnection 客户端连接（主服侧）
     * @param packet           已解码的握手包（用于回放给镜像服）
     * @param mirrorHost       镜像服地址（内网）
     * @param mirrorPort       镜像服端口
     */
    public static void bridge(Connection clientConnection,
                              ClientIntentionPacket packet,
                              String mirrorHost, int mirrorPort) {
        Channel clientChannel = getChannel(clientConnection);
        if (clientChannel == null) {
            LOGGER.error("[Proxy] Cannot get client channel, transfer aborted");
            clientConnection.disconnect(
                    net.minecraft.network.chat.Component.literal("Transfer 桥接失败：无法获取连接"));
            return;
        }

        LOGGER.info("[Proxy] Bridging transfer connection to {}:{}", mirrorHost, mirrorPort);

        // 1. 取出 channelActive 时安装的捕获器（mirror_captor）累积的原始字节。
        //    客户端把握手包 + 登录包在同一个 TCP 读里发来（共 43 字节），
        //    必须在此处（stripMcPipeline 之前）取走，否则捕获器被移除时
        //    handlerRemoved 会释放并清空缓冲，导致登录包丢失。
        RawByteCaptor captor = (RawByteCaptor) clientChannel.pipeline().get("mirror_captor");
        final ByteBuf initial = (captor != null) ? captor.takeCapturedBytes() : null;
        LOGGER.info("[Proxy] captured {} bytes from client", initial != null ? initial.readableBytes() : 0);

        // 2. 移除所有 MC 管线 handler（splitter/decoder/packet_handler 等）
        stripMcPipeline(clientChannel);
        // strip 移除了 FlowControlHandler/ReadTimeoutHandler 等，确保 channel 继续自动读取客户端字节
        clientChannel.config().setAutoRead(true);
        clientChannel.read();

        // 3. 建立到镜像服的纯 Netty 连接（复用客户端 event loop）
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientChannel.eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 纯字节通道，不做 MC packet 处理
                    }
                });

        final Channel client = clientChannel;
        bootstrap.connect(mirrorHost, mirrorPort).addListener((ChannelFuture future) -> {
            if (!future.isSuccess()) {
                LOGGER.error("[Proxy] Failed to connect mirror server", future.cause());
                clientConnection.disconnect(
                        net.minecraft.network.chat.Component.literal("Transfer 失败：无法连接镜像服"));
                return;
            }

            Channel mirror = future.channel();
            LOGGER.info("[Proxy] Mirror connection established, switching to raw passthrough");

            // 4. 回放捕获的原始字节（握手 + 登录包），否则回退重编码握手
            if (initial != null && initial.isReadable()) {
                LOGGER.info("[Proxy] replaying {} bytes to mirror", initial.readableBytes());
                mirror.writeAndFlush(initial);
            } else {
                LOGGER.info("[Proxy] no captured bytes, replaying encoded handshake");
                mirror.writeAndFlush(encodeHandshake(packet));
            }

            // 5. 建立双向字节流透传
            client.pipeline().addLast("proxy", new PassthroughHandler(mirror, "client->mirror"));
            mirror.pipeline().addLast("proxy", new PassthroughHandler(client, "mirror->client"));
        });
    }

    /**
     * 编码握手包为字节（frame 长度 + packet ID + 数据）。
     */
    private static ByteBuf encodeHandshake(ClientIntentionPacket packet) {
        ByteBuf payload = Unpooled.buffer();
        FriendlyByteBuf fb = new FriendlyByteBuf(payload);
        fb.writeVarInt(0); // 握手协议 packet ID（CLIENT_INTENTION 唯一，ID=0）
        fb.writeVarInt(packet.protocolVersion());
        fb.writeUtf(packet.hostName());
        fb.writeShort(packet.port());
        fb.writeVarInt(packet.intention().id());

        // frame 前缀：VarInt 长度 + payload
        ByteBuf framed = Unpooled.buffer();
        FriendlyByteBuf frameBuf = new FriendlyByteBuf(framed);
        frameBuf.writeVarInt(payload.readableBytes());
        frameBuf.writeBytes(payload);
        payload.release();
        return framed;
    }

    /**
     * 移除客户端 channel 的所有 handler，只保留原始字节通道。
     *
     * MC 26.2 的管线里除 splitter/decoder/prepender/encoder/packet_handler 外，
     * 还有 timeout、legacy_query、flow_control、inbound_config、hackfix 等 handler。
     * 只按固定名字列表移除会漏掉，导致客户端后续字节打到错误 handler。
     * 因此这里改为遍历 names() 移除全部 handler，彻底切断 MC 协议处理。
     * 注意：mirror_captor 捕获的原始字节必须在调用本方法前先取走。
     */
    private static void stripMcPipeline(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        List<String> names = new ArrayList<>(pipeline.names());
        for (String name : names) {
            try {
                pipeline.remove(name);
            } catch (Exception ignored) {
            }
        }
    }

    private static Channel getChannel(Connection connection) {
        try {
            return connectionChannelField != null ? (Channel) connectionChannelField.get(connection) : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * 字节透传 handler：把对端 channel 读到的字节转发到本 channel。
     */
    private static class PassthroughHandler extends ChannelInboundHandlerAdapter {
        private final Channel target;
        private final String label;

        PassthroughHandler(Channel target, String label) {
            this.target = target;
            this.label = label;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                LOGGER.info("[Proxy] {} +{} bytes", label, buf.readableBytes());
                int n = Math.min(48, buf.readableBytes());
                byte[] head = new byte[n];
                buf.getBytes(buf.readerIndex(), head);
                StringBuilder sb = new StringBuilder();
                for (byte b : head) { sb.append(String.format("%02x ", b)); }
                LOGGER.info("[Proxy] {} head: {}", label, sb.toString().trim());
                target.writeAndFlush(buf);
            } else {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // 一端断开，关闭另一端
            LOGGER.info("[Proxy] {} inactive", label);
            target.close();
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.error("[Proxy] {} exceptionCaught", label, cause);
            target.close();
            ctx.close();
        }
    }
}
