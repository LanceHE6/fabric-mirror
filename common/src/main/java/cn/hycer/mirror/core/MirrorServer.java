package cn.hycer.mirror.core;

import cn.hycer.mirror.config.MirrorConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
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

public class MirrorServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");

    private static final ResourceKey<Level> MIRROR_OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, Identifier.tryParse("mirror:overworld"));
    private static final ResourceKey<Level> MIRROR_NETHER =
            ResourceKey.create(Registries.DIMENSION, Identifier.tryParse("mirror:the_nether"));
    private static final ResourceKey<Level> MIRROR_END =
            ResourceKey.create(Registries.DIMENSION, Identifier.tryParse("mirror:the_end"));

    private final MinecraftServer mainServer;
    private final MirrorConfig config;
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private Thread tickThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerLevel mirrorOverworld;
    private ServerLevel mirrorNether;
    private ServerLevel mirrorEnd;
    private Object mirrorStorage;
    private final Map<UUID, ServerPlayer> players = new ConcurrentHashMap<>();
    private long lastTickTime;
    private double currentTPS;

    public enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    public MirrorServer(MinecraftServer mainServer, MirrorConfig config) {
        this.mainServer = mainServer;
        this.config = config;
    }

    // ==================== Lifecycle ====================

    public boolean start() {
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) return false;
        try {
            LOGGER.info("[MirrorServer] Loading mirror worlds...");
            loadMirrorWorlds();

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

    public boolean stop() {
        running.set(false);
        try {
            if (mirrorOverworld != null) mirrorOverworld.save(null, false, false);
            if (mirrorNether != null) mirrorNether.save(null, false, false);
            if (mirrorEnd != null) mirrorEnd.save(null, false, false);
        } catch (Exception ignored) {}
        closeStorage();
        state.set(State.STOPPED);
        return true;
    }

    public void reloadWorlds() {
        LOGGER.info("[MirrorServer] Hot-reloading mirror worlds...");
        try {
            if (mirrorOverworld != null) mirrorOverworld.save(null, false, false);
            if (mirrorNether != null) mirrorNether.save(null, false, false);
            if (mirrorEnd != null) mirrorEnd.save(null, false, false);
            closeStorage();
            mirrorOverworld = null;
            mirrorNether = null;
            mirrorEnd = null;
            loadMirrorWorlds();
            LOGGER.info("[MirrorServer] Mirror worlds reloaded successfully");
        } catch (Exception e) {
            LOGGER.error("[MirrorServer] Failed to reload worlds", e);
        }
    }

    // ==================== World Loading ====================

    @SuppressWarnings("unchecked")
    private void loadMirrorWorlds() throws Exception {
        Field levelsField = findField(mainServer.getClass(), "levels");
        levelsField.setAccessible(true);
        Map<ResourceKey<Level>, ServerLevel> mainLevels =
                (Map<ResourceKey<Level>, ServerLevel>) levelsField.get(mainServer);

        LevelStem owStem = getLevelStemFromRegistry(Level.OVERWORLD);
        LevelStem neStem = getLevelStemFromRegistry(Level.NETHER);
        LevelStem enStem = getLevelStemFromRegistry(Level.END);

        Object storage = createMirrorStorage();
        this.mirrorStorage = storage;

        mirrorOverworld = createServerLevel(storage, MIRROR_OVERWORLD, owStem);
        LOGGER.info("[MirrorServer] Mirror overworld created");

        if (neStem != null) {
            mirrorNether = createServerLevel(storage, MIRROR_NETHER, neStem);
            LOGGER.info("[MirrorServer] Mirror nether created");
        }
        if (enStem != null) {
            mirrorEnd = createServerLevel(storage, MIRROR_END, enStem);
            LOGGER.info("[MirrorServer] Mirror end created");
        }

        // Register in main server's levels map (server thread)
        // Safe because MirrorLevelTickMixin prevents main tick loop from ticking mirror levels
        mainServer.execute(() -> {
            try {
                @SuppressWarnings("unchecked")
                Map<ResourceKey<Level>, ServerLevel> all =
                        (Map<ResourceKey<Level>, ServerLevel>) levelsField.get(mainServer);
                all.put(MIRROR_OVERWORLD, mirrorOverworld);
                if (mirrorNether != null) all.put(MIRROR_NETHER, mirrorNether);
                if (mirrorEnd != null) all.put(MIRROR_END, mirrorEnd);
                LOGGER.info("[MirrorServer] Mirror levels registered in server.levels");
            } catch (Exception e) {
                LOGGER.error("[MirrorServer] Failed to register levels", e);
            }
        });
    }

    private LevelStem getLevelStemFromRegistry(ResourceKey<Level> dimension) {
        try {
            Object regAccess = mainServer.registryAccess();
            for (Method m : regAccess.getClass().getMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == ResourceKey.class
                        && m.getReturnType() != void.class) {
                    Object result = m.invoke(regAccess, Registries.LEVEL_STEM);
                    if (result instanceof Optional<?> opt && opt.isPresent()
                            && opt.get() instanceof LevelStem stem) return stem;
                    if (result != null && !(result instanceof Optional)) {
                        for (Method getM : result.getClass().getMethods()) {
                            if (getM.getParameterCount() == 1
                                    && getM.getParameterTypes()[0] == ResourceKey.class) {
                                Object val = getM.invoke(result, dimension);
                                if (val instanceof LevelStem s) return s;
                                if (val instanceof Optional<?> o && o.isPresent()
                                        && o.get() instanceof LevelStem s) return s;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[MirrorServer] Registry LevelStem lookup failed: {}", e.getMessage());
        }
        return null;
    }

    // ==================== Storage ====================

    private Object createMirrorStorage() throws Exception {
        Class<?> lssClass = Class.forName("net.minecraft.world.level.storage.LevelStorageSource");
        Method createDefault = null;
        for (Method m : lssClass.getDeclaredMethods()) {
            if (m.getName().equals("createDefault") && m.getParameterCount() == 1) {
                createDefault = m; break;
            }
        }
        Object source = createDefault.invoke(null, Path.of(".").toAbsolutePath());
        Method createAccess = null;
        for (Method m : lssClass.getDeclaredMethods()) {
            if (m.getName().equals("createAccess") && m.getParameterCount() == 1) {
                createAccess = m; break;
            }
        }
        return createAccess.invoke(source, config.getWorldPath());
    }

    private void closeStorage() {
        if (mirrorStorage != null) {
            try {
                for (Method m : mirrorStorage.getClass().getMethods()) {
                    if (m.getName().equals("close") && m.getParameterCount() == 0) {
                        m.invoke(mirrorStorage); break;
                    }
                }
            } catch (Exception ignored) {}
            mirrorStorage = null;
        }
    }

    // ==================== ServerLevel Construction ====================

    private ServerLevel createServerLevel(Object storage, ResourceKey<Level> dimKey,
                                           LevelStem stem) throws Exception {
        ServerLevel template = mainServer.overworld();
        Constructor<?> ctor = null;
        for (Constructor<?> c : ServerLevel.class.getDeclaredConstructors()) {
            ctor = c; break;
        }
        ctor.setAccessible(true);
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];

        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (LevelStem.class.isAssignableFrom(t)) args[i] = stem;
            else if (ResourceKey.class.isAssignableFrom(t)) args[i] = dimKey;
            else if (t.isAssignableFrom(mainServer.getClass())) args[i] = mainServer;
            else {
                args[i] = findFieldByType(template, t);
                if (args[i] == null) args[i] = findFieldByType(mainServer, t);
                if (args[i] == null) args[i] = findFieldByType(storage, t);
            }
        }
        return (ServerLevel) ctor.newInstance(args);
    }

    // ==================== Tick ====================

    private void tickLoop() {
        final long MSPT = 50;
        lastTickTime = System.currentTimeMillis();
        while (running.get()) {
            long start = System.nanoTime();
            try { tick(); } catch (Exception ignored) {}
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            long now = System.currentTimeMillis();
            long delta = now - lastTickTime;
            if (delta >= 1000) {
                currentTPS = 1000.0 / Math.max(delta, MSPT) * (delta / MSPT);
                lastTickTime = now;
            }
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

    // ==================== Utility ====================

    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) if (f.getName().equals(name)) return f;
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object findFieldByType(Object obj, Class<?> targetType) {
        if (obj == null) return null;
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

    // ==================== Getters ====================

    public State getState() { return state.get(); }
    public ServerLevel getOverworld() { return mirrorOverworld; }
    public ServerLevel getNether() { return mirrorNether; }
    public ServerLevel getEnd() { return mirrorEnd; }
    public double getTPS() { return currentTPS; }
    public int getOnlinePlayerCount() { return players.size(); }
    public void addPlayer(ServerPlayer player) { players.put(player.getUUID(), player); }
    public void removePlayer(UUID uuid) { players.remove(uuid); }
}
