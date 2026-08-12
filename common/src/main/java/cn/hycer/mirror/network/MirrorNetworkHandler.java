package cn.hycer.mirror.network;

import cn.hycer.mirror.core.MirrorServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 镜像实例网络处理器。
 * 将镜像端口挂载到主服 ServerConnectionListener 上，复用完整 MC 协议栈。
 */
public class MirrorNetworkHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    private final MirrorServer mirrorServer;
    private final MinecraftServer mainServer;
    private final int port;
    private final String bindAddress;

    public MirrorNetworkHandler(MirrorServer mirrorServer, MinecraftServer mainServer,
                                 int port, String bindAddress) {
        this.mirrorServer = mirrorServer;
        this.mainServer = mainServer;
        this.port = port;
        this.bindAddress = bindAddress;
    }

    /**
     * 将镜像端口注册到主服的 ServerConnectionListener 上。
     */
    public boolean start() {
        try {
            LOGGER.info("[MirrorNetwork] Binding mirror port {}:{} on main listener", bindAddress, port);

            // Get main server's ServerConnectionListener
            Object listener = getServerConnectionListener(mainServer);
            if (listener == null) {
                LOGGER.error("[MirrorNetwork] Cannot access ServerConnectionListener");
                return false;
            }

            // Call startTcpServerListener(InetAddress, int)
            Method startMethod = null;
            for (Method m : listener.getClass().getMethods()) {
                if (m.getName().equals("startTcpServerListener")
                        && m.getParameterCount() == 2) {
                    startMethod = m;
                    break;
                }
            }

            if (startMethod != null) {
                InetAddress addr = InetAddress.getByName(bindAddress);
                startMethod.invoke(listener, addr, port);
                LOGGER.info("[MirrorNetwork] Mirror port {} registered on main listener", port);
            } else {
                // Fallback: try any start method with InetAddress + int
                for (Method m : listener.getClass().getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2
                            && params[0].isAssignableFrom(InetAddress.class)
                            && params[1] == int.class) {
                        m.setAccessible(true);
                        InetAddress addr = InetAddress.getByName(bindAddress);
                        m.invoke(listener, addr, port);
                        LOGGER.info("[MirrorNetwork] Mirror port {} bound via {}", port, m.getName());
                        break;
                    }
                }
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("[MirrorNetwork] Failed to bind mirror port: {}", e.getMessage());
            return false;
        }
    }

    public void stop() {
        // The mirror port is part of the main listener, stops with the server
        LOGGER.info("[MirrorNetwork] Stopped");
    }

    /**
     * Transfer player to mirror: kick with cookie info, client reconnects to mirror port.
     */
    public static boolean transferToMirror(ServerPlayer player) {
        var config = cn.hycer.mirror.config.MirrorConfig.getInstance();
        String addr = config.getBindAddress() + ":" + config.getPort();

        // Store transfer intent so on reconnect we know to route to mirror world
        // For now, just kick with reconnect instructions
        player.connection.disconnect(Component.literal(
                "§6正在传送到镜像实例...\n\n" +
                "§7正在重新连接，请稍候...\n" +
                "§7目标: §e" + addr));
        LOGGER.info("[MirrorNetwork] Player {} transferring to mirror at {}",
                player.getName().getString(), addr);
        return true;
    }

    public static boolean transferToMain(ServerPlayer player) {
        player.connection.disconnect(Component.literal(
                "§6正在返回主服...\n\n§7正在重新连接..."));
        return true;
    }

    /**
     * Get the server's ServerConnectionListener via reflection.
     */
    private static Object getServerConnectionListener(MinecraftServer server) {
        try {
            Class<?> sclClass = Class.forName(
                    "net.minecraft.server.network.ServerConnectionListener");
            for (Field f : server.getClass().getDeclaredFields()) {
                if (sclClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(server);
                }
            }
            // Check parent class
            for (Field f : server.getClass().getSuperclass().getDeclaredFields()) {
                if (sclClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(server);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[MirrorNetwork] Failed to get listener", e);
        }
        return null;
    }
}
