package cn.hycer.mirror.network;

import cn.hycer.mirror.core.MirrorServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * 镜像实例网络处理器。
 * 在独立端口上监听客户端连接。
 */
public class MirrorNetworkHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    private final MirrorServer mirrorServer;
    private final int port;
    private final String bindAddress;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Thread networkThread;

    public MirrorNetworkHandler(MirrorServer mirrorServer, int port, String bindAddress) {
        this.mirrorServer = mirrorServer;
        this.port = port;
        this.bindAddress = bindAddress;
    }

    public boolean start() {
        try {
            LOGGER.info("[MirrorNetwork] Starting listener on {}:{}", bindAddress, port);

            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) {}

                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    LOGGER.info("[MirrorNetwork] Connection from {}",
                                            ctx.channel().remoteAddress());
                                    ctx.close();
                                }
                            });
                        }
                    });

            serverChannel = bootstrap.bind(new InetSocketAddress(bindAddress, port)).sync().channel();
            LOGGER.info("[MirrorNetwork] Listener started on port {}", port);

            networkThread = new Thread(this::networkLoop, "Mirror-Network-Thread");
            networkThread.setDaemon(true);
            networkThread.start();

            return true;
        } catch (Exception e) {
            LOGGER.error("[MirrorNetwork] Failed to start: {}", e.getMessage());
            // Clean up on failure to avoid leaking file descriptors
            if (bossGroup != null) bossGroup.shutdownGracefully();
            if (workerGroup != null) workerGroup.shutdownGracefully();
            return false;
        }
    }

    private void networkLoop() {
        try {
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        try {
            if (serverChannel != null) serverChannel.close();
            if (bossGroup != null) bossGroup.shutdownGracefully();
            if (workerGroup != null) workerGroup.shutdownGracefully();
        } catch (Exception e) {
            LOGGER.warn("[MirrorNetwork] Error stopping: {}", e.getMessage());
        }
        LOGGER.info("[MirrorNetwork] Stopped");
    }

    /**
     * Transfer a player from main server to mirror instance.
     */
    public static boolean transferToMirror(ServerPlayer player) {
        try {
            Class<?> transferClass = findTransferPacketClass();
            if (transferClass == null) {
                player.sendSystemMessage(Component.literal("§cTransfer 不可用，请尝试手动连接。"));
                return false;
            }

            var config = cn.hycer.mirror.config.MirrorConfig.getInstance();
            var ctor = transferClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object packet = ctor.newInstance(config.getBindAddress(), config.getPort());

            player.connection.send((net.minecraft.network.protocol.Packet<?>) packet);
            player.connection.disconnect(Component.literal("正在传送到镜像实例..."));
            LOGGER.info("[MirrorNetwork] Player {} transferred to mirror", player.getName().getString());
            return true;
        } catch (Exception e) {
            LOGGER.error("[MirrorNetwork] Transfer failed", e);
            return false;
        }
    }

    public static boolean transferToMain(ServerPlayer player) {
        try {
            Class<?> transferClass = findTransferPacketClass();
            if (transferClass == null) return false;
            var ctor = transferClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object packet = ctor.newInstance("127.0.0.1", 25565);
            player.connection.send((net.minecraft.network.protocol.Packet<?>) packet);
            player.connection.disconnect(Component.literal("正在返回主服..."));
            return true;
        } catch (Exception e) {
            LOGGER.error("[MirrorNetwork] Transfer to main failed", e);
            return false;
        }
    }

    private static Class<?> findTransferPacketClass() {
        String[] candidates = {
            "net.minecraft.network.protocol.common.ClientboundTransferPacket",
            "net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket",
        };
        for (String name : candidates) {
            try { return Class.forName(name); } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }
}
