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

    private static final String[] MC_PIPELINE_HANDLERS = {
            "splitter", "decoder", "prepender", "encoder",
            "timeout", "packet_handler", "bundler", "unbundler",
            "decrypt", "encrypt", "decompress", "compress"
    };

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

        // 1. 编码握手包（用于回放）
        ByteBuf handshake = encodeHandshake(packet);

        // 2. 建立到镜像服的纯 Netty 连接（复用客户端 event loop）
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
                handshake.release();
                return;
            }

            Channel mirror = future.channel();
            LOGGER.info("[Proxy] Mirror connection established, switching to raw passthrough");

            // 3. 回放握手包给镜像服
            mirror.writeAndFlush(handshake);

            // 4. 切换客户端 channel pipeline 为字节透传
            stripMcPipeline(client);

            // 5. 双向字节流透传
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
     * 移除客户端 channel 的 MC packet pipeline，只保留原始字节通道。
     */
    private static void stripMcPipeline(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        for (String name : MC_PIPELINE_HANDLERS) {
            try {
                if (pipeline.get(name) != null) {
                    pipeline.remove(name);
                }
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
                // 两个 channel 在同一 event loop，直接转发（writeAndFlush 完成后自动释放）
                target.writeAndFlush(buf);
            } else {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // 一端断开，关闭另一端
            target.close();
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.debug("[Proxy] {} error: {}", label, cause.getMessage());
            target.close();
            ctx.close();
        }
    }
}
