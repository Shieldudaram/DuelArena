package com.Chris__.duel_arena.integration;

import com.Chris__.duel_arena.arena.Arena;
import com.Chris__.duel_arena.arena.ArenaService;
import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.config.DuelConfig;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

public final class SimpleClaimsVerifier {

    private final ConfigRepository configRepository;
    private final ArenaService arenaService;
    private final HytaleLogger logger;

    private volatile Object claimManagerInstance = null;
    private volatile Method getChunkMethod = null;
    private volatile boolean attempted = false;

    public SimpleClaimsVerifier(ConfigRepository configRepository, ArenaService arenaService, HytaleLogger logger) {
        this.configRepository = configRepository;
        this.arenaService = arenaService;
        this.logger = logger;
    }

    public void verifyArenasWarnOnly() {
        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        if (cfg == null || cfg.simpleClaims == null || !cfg.simpleClaims.verifyClaims) return;
        if (arenaService == null) return;

        if (!ensureLoaded()) {
            return;
        }

        List<Arena> arenas = arenaService.listArenas();
        for (Arena a : arenas) {
            if (a == null || !a.isValidForUse()) continue;
            int missingArena = countUnclaimedChunks(a.world, a.bounds);
            int missingSpec = countUnclaimedChunks(a.world, a.spectatorBounds);
            int missing = missingArena + missingSpec;
            if (missing > 0) {
                logger.atWarning().log("[DuelArena] Arena '%s' has %d unclaimed chunk(s) (SimpleClaims). warn_only", a.id, missing);
            }
        }
    }

    private int countUnclaimedChunks(String worldName, Arena.Aabb bounds) {
        if (worldName == null || worldName.isBlank()) return 0;
        if (bounds == null || !bounds.isValid()) return 0;
        Method getChunk = getChunkMethod;
        Object mgr = claimManagerInstance;
        if (getChunk == null || mgr == null) return 0;

        int minChunkX = ChunkUtil.chunkCoordinate(bounds.minX());
        int maxChunkX = ChunkUtil.chunkCoordinate(bounds.maxX());
        int minChunkZ = ChunkUtil.chunkCoordinate(bounds.minZ());
        int maxChunkZ = ChunkUtil.chunkCoordinate(bounds.maxZ());

        int missing = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                try {
                    Object chunk = getChunk.invoke(mgr, worldName, cx, cz);
                    if (chunk == null) missing++;
                } catch (Throwable t) {
                    return missing;
                }
            }
        }
        return missing;
    }

    private boolean ensureLoaded() {
        if (claimManagerInstance != null && getChunkMethod != null) return true;
        if (attempted) return false;
        attempted = true;

        // Try direct load first (shared classloader in some server builds).
        if (tryLoadFromClassLoader(SimpleClaimsVerifier.class.getClassLoader())) {
            return true;
        }

        // Try plugin classloader via PluginManager lookup.
        try {
            Object pm = PluginManager.get();
            Object plugin = findPluginBestEffort(pm,
                    "Buuz135:SimpleClaims",
                    "SimpleClaims",
                    "simpleclaims",
                    "buuz135:simpleclaims");
            if (plugin != null) {
                ClassLoader cl = plugin.getClass().getClassLoader();
                if (tryLoadFromClassLoader(cl)) return true;
            }
        } catch (Throwable ignored) {
        }

        logger.atInfo().log("[DuelArena] SimpleClaims not available for claim verification (optional dependency).");
        return false;
    }

    private boolean tryLoadFromClassLoader(ClassLoader cl) {
        if (cl == null) return false;
        try {
            Class<?> claimManagerClass = Class.forName("com.buuz135.simpleclaims.claim.ClaimManager", false, cl);
            Method getInstance = claimManagerClass.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            Method getChunk = claimManagerClass.getMethod("getChunk", String.class, int.class, int.class);

            claimManagerInstance = instance;
            getChunkMethod = getChunk;
            logger.atInfo().log("[DuelArena] SimpleClaims claim verification enabled.");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object findPluginBestEffort(Object pluginManager, String... names) {
        if (pluginManager == null || names == null) return null;
        String[] methodNames = new String[]{"getPlugin", "getPluginByName", "getPluginOrNull"};

        for (String name : names) {
            for (String mName : methodNames) {
                try {
                    Method m = pluginManager.getClass().getMethod(mName, String.class);
                    Object p = m.invoke(pluginManager, name);
                    if (p != null) return p;
                } catch (Throwable ignored) {
                }
            }
        }

        try {
            Method m = pluginManager.getClass().getMethod("getPlugins");
            Object plugins = m.invoke(pluginManager);
            if (plugins instanceof Iterable<?> it) {
                for (Object p : it) {
                    if (p == null) continue;
                    String s = p.toString().toLowerCase(Locale.ROOT);
                    for (String wanted : names) {
                        if (wanted == null) continue;
                        if (s.contains(wanted.toLowerCase(Locale.ROOT))) return p;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }
}

