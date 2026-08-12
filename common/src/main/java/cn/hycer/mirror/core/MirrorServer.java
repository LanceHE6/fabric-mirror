package cn.hycer.mirror.core;

import cn.hycer.mirror.config.MirrorConfig;
import cn.hycer.mirror.network.MirrorNetworkHandler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轻量级镜像服务端实例。
 */
public class MirrorServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    private final MinecraftServer mainServer;
    private final MirrorConfig config;
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private Thread tickThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerLevel mirrorOverworld;
    private ServerLevel mirrorNether;
    private ServerLevel mirrorEnd;
    private Object mirrorStorage; // LevelStorageAccess, for lock management
    private MirrorNetworkHandler networkHandler;
    private final Map<UUID, ServerPlayer> players = new ConcurrentHashMap<>();
    private long lastTickTime;
    private double currentTPS;

    public enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    public MirrorServer(MinecraftServer mainServer, MirrorConfig config) {
        this.mainServer = mainServer;
        this.config = config;
    }

    public boolean start() {
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) return false;
        try {
            LOGGER.info("[MirrorServer] Loading mirror worlds...");
            loadMirrorWorlds();

            // Start network listener
            networkHandler = new MirrorNetworkHandler(
                    this, config.getPort(), config.getBindAddress());
            networkHandler.start();

            running.set(true);
            tickThread = new Thread(this::tickLoop, "Mirror-Tick-Thread");
            tickThread.setDaemon(true);
            tickThread.start();

            state.set(State.RUNNING);
            LOGGER.info("[MirrorServer] Started successfully");
            return true;
        } catch (Exception e) {
            LOGGER.error("[MirrorServer] Failed to start", e);
            state.set(State.ERROR);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadMirrorWorlds() throws Exception {
        Field levelsField = findField(mainServer.getClass(), "levels");
        levelsField.setAccessible(true);
        Map<ResourceKey<Level>, ServerLevel> mainLevels =
                (Map<ResourceKey<Level>, ServerLevel>) levelsField.get(mainServer);

        LevelStem overworldStem = getLevelStem(mainLevels, Level.OVERWORLD);
        LevelStem netherStem = getLevelStem(mainLevels, Level.NETHER);
        LevelStem endStem = getLevelStem(mainLevels, Level.END);

        LOGGER.debug("[MirrorServer] LevelStem overworld={}, nether={}, end={}",
                overworldStem != null, netherStem != null, endStem != null);

        Object storage = createMirrorStorage();
        this.mirrorStorage = storage;

        mirrorOverworld = createServerLevel(storage, Level.OVERWORLD, overworldStem);
        LOGGER.info("[MirrorServer] Mirror overworld created");

        if (netherStem != null) {
            mirrorNether = createServerLevel(storage, Level.NETHER, netherStem);
            LOGGER.info("[MirrorServer] Mirror nether created");
        }
        if (endStem != null) {
            mirrorEnd = createServerLevel(storage, Level.END, endStem);
            LOGGER.info("[MirrorServer] Mirror end created");
        }
    }

    private LevelStem getLevelStem(Map<ResourceKey<Level>, ServerLevel> levels,
                                    ResourceKey<Level> dimension) {
        // Try registry lookup first (most reliable)
        try {
            Object regAccess = mainServer.registryAccess();
            LOGGER.debug("[MirrorServer] registryAccess() type: {}", regAccess.getClass().getName());

            // Try ALL public methods that take a ResourceKey and return something
            for (Method m : regAccess.getClass().getMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == ResourceKey.class
                        && m.getReturnType() != void.class) {
                    try {
                        Object result = m.invoke(regAccess,
                                net.minecraft.core.registries.Registries.LEVEL_STEM);
                        if (result != null) {
                            LOGGER.debug("[MirrorServer]   {}(LEVEL_STEM) -> {}",
                                    m.getName(), result.getClass().getSimpleName());

                            // If result is a Registry, call get(dimensionKey)
                            if (result instanceof java.util.Optional) {
                                Optional<?> opt = (Optional<?>) result;
                                if (opt.isPresent() && opt.get() instanceof LevelStem) {
                                    return (LevelStem) opt.get();
                                }
                            } else {
                                // Try getOptional or get method
                                for (Method getM : result.getClass().getMethods()) {
                                    if (getM.getParameterCount() == 1
                                            && getM.getParameterTypes()[0] == ResourceKey.class
                                            && getM.getReturnType() != void.class) {
                                        Object val = getM.invoke(result, dimension);
                                        if (val instanceof LevelStem) return (LevelStem) val;
                                        if (val instanceof Optional && ((Optional<?>)val).isPresent()
                                                && ((Optional<?>)val).get() instanceof LevelStem) {
                                            return (LevelStem) ((Optional<?>)val).get();
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("[MirrorServer]   {}(LEVEL_STEM) threw: {}", m.getName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[MirrorServer] Registry access failed: {}", e.getMessage());
        }

        // Fallback: scan ServerLevel fields
        ServerLevel level = levels.get(dimension);
        if (level != null) {
            Class<?> clazz = level.getClass();
            while (clazz != null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (LevelStem.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        try { return (LevelStem) f.get(level); } catch (Exception ignored) {}
                    }
                }
                clazz = clazz.getSuperclass();
            }
        }

        return null;
    }

    private Object createMirrorStorage() throws Exception {
        Class<?> lssClass = Class.forName("net.minecraft.world.level.storage.LevelStorageSource");

        // Find createDefault(Path)
        Method createDefault = null;
        for (Method m : lssClass.getDeclaredMethods()) {
            if (m.getName().equals("createDefault") && m.getParameterCount() == 1) {
                LOGGER.debug("[MirrorServer] createDefault: {} params, type={}",
                        m.getParameterCount(), m.getParameterTypes()[0].getSimpleName());
                createDefault = m;
                break;
            }
        }
        if (createDefault == null) {
            LOGGER.error("[MirrorServer] No createDefault found on LevelStorageSource");
            return null;
        }

        Path basePath = Path.of(".").toAbsolutePath();
        LOGGER.info("[MirrorServer] Calling createDefault({})", basePath);
        Object source = createDefault.invoke(null, basePath);
        LOGGER.info("[MirrorServer] Source created: {}", source.getClass().getName());

        // Find createAccess method
        Method createAccess = null;
        for (Method m : lssClass.getDeclaredMethods()) {
            if (m.getName().equals("createAccess") && m.getParameterCount() == 1) {
                LOGGER.info("[MirrorServer] Found createAccess({})",
                        m.getParameterTypes()[0].getSimpleName());
                createAccess = m;
                break;
            }
        }
        if (createAccess == null) {
            LOGGER.error("[MirrorServer] No createAccess found");
            return null;
        }

        String worldPath = config.getWorldPath();
        LOGGER.info("[MirrorServer] Calling createAccess(\"{}\")", worldPath);
        Object storage = createAccess.invoke(source, worldPath);
        LOGGER.info("[MirrorServer] Storage created: {}", storage);
        return storage;
    }

    private ServerLevel createServerLevel(Object storage, ResourceKey<Level> dimension,
                                           LevelStem stem) throws Exception {
        ServerLevel mainOverworld = mainServer.overworld();
        Constructor<?> slCtor = findServerLevelConstructor();
        Class<?>[] paramTypes = slCtor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pType = paramTypes[i];
            Object val = null;

            if (LevelStem.class.isAssignableFrom(pType)) {
                val = stem;
            } else if (pType.isAssignableFrom(mainServer.getClass())) {
                val = mainServer;
            } else if (pType.isAssignableFrom(mainOverworld.getClass())) {
                val = findFieldByType(mainOverworld, pType);
            } else {
                val = findFieldByType(mainOverworld, pType);
                if (val == null) val = findFieldByType(mainServer, pType);
            }

            // Try matching storage
            if (val == null && storage != null) {
                for (Class<?> iface : storage.getClass().getInterfaces()) {
                    if (pType.isAssignableFrom(iface)) { val = storage; break; }
                }
            }
            args[i] = val;
            LOGGER.debug("[MirrorServer]   SL[{}] {} = {}",
                    i, pType.getSimpleName(),
                    val != null ? val.getClass().getSimpleName() : "NULL");
        }

        slCtor.setAccessible(true);
        return (ServerLevel) slCtor.newInstance(args);
    }

    private Constructor<?> findServerLevelConstructor() {
        for (Constructor<?> c : ServerLevel.class.getDeclaredConstructors()) {
            return c;
        }
        return null;
    }

    // --- Tick loop (unchanged) ---

    private void tickLoop() {
        final long MSPT = 50;
        lastTickTime = System.currentTimeMillis();
        while (running.get()) {
            long start = System.nanoTime();
            try { tick(); } catch (Exception ignored) {}
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            long now = System.currentTimeMillis();
            long delta = now - lastTickTime;
            if (delta >= 1000) { currentTPS = 1000.0 / Math.max(delta, MSPT) * (delta / MSPT); lastTickTime = now; }
            if (MSPT - elapsed > 1) {
                try { Thread.sleep(MSPT - elapsed); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void tick() {
        if (mirrorOverworld != null) try { mirrorOverworld.tick(() -> true); } catch (Exception ignored) {}
        if (mirrorNether != null) try { mirrorNether.tick(() -> true); } catch (Exception ignored) {}
        if (mirrorEnd != null) try { mirrorEnd.tick(() -> true); } catch (Exception ignored) {}
    }

    // --- Utility ---

    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) if (f.getName().equals(name)) return f;
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object findFieldByType(Object obj, Class<?> targetType) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (targetType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try { Object val = f.get(obj); if (val != null) return val; }
                    catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public boolean stop() {
        running.set(false);
        if (networkHandler != null) networkHandler.stop();
        // Save and unload mirror worlds
        try {
            if (mirrorOverworld != null) mirrorOverworld.save(null, false, false);
            if (mirrorNether != null) mirrorNether.save(null, false, false);
            if (mirrorEnd != null) mirrorEnd.save(null, false, false);
        } catch (Exception ignored) {}
        // Close storage to release file lock
        if (mirrorStorage != null) {
            try {
                for (java.lang.reflect.Method m : mirrorStorage.getClass().getMethods()) {
                    if (m.getName().equals("close") && m.getParameterCount() == 0) {
                        m.invoke(mirrorStorage); break;
                    }
                }
            } catch (Exception ignored) {}
        }
        state.set(State.STOPPED);
        return true;
    }

    /**
     * 热重载镜像世界：保存→卸载→重新加载。
     * 在文件同步后调用。
     */
    public void reloadWorlds() {
        LOGGER.info("[MirrorServer] Hot-reloading mirror worlds...");
        try {
            // Save current worlds
            if (mirrorOverworld != null) mirrorOverworld.save(null, false, false);
            if (mirrorNether != null) mirrorNether.save(null, false, false);
            if (mirrorEnd != null) mirrorEnd.save(null, false, false);

            // Close old LevelStorageAccess to release file lock
            if (mirrorStorage != null) {
                try {
                    for (java.lang.reflect.Method m : mirrorStorage.getClass().getMethods()) {
                        if (m.getName().equals("close") && m.getParameterCount() == 0) {
                            m.invoke(mirrorStorage);
                            break;
                        }
                    }
                } catch (Exception ignored) {}
                mirrorStorage = null;
            }

            // Clear references
            mirrorOverworld = null;
            mirrorNether = null;
            mirrorEnd = null;

            // Reload
            loadMirrorWorlds();
            LOGGER.info("[MirrorServer] Mirror worlds reloaded successfully");
        } catch (Exception e) {
            LOGGER.error("[MirrorServer] Failed to reload worlds", e);
        }
    }

    public State getState() { return state.get(); }
    public ServerLevel getOverworld() { return mirrorOverworld; }
    public double getTPS() { return currentTPS; }
    public int getOnlinePlayerCount() { return players.size(); }
}
