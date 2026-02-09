package com.Chris__.duel_arena.arena;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ArenaService {

    public enum Zone {
        NONE,
        ARENA,
        SPECTATOR
    }

    public record ArenaAvailability(int total, int valid, int reserved, int free) {
    }

    private final ArenaRepository arenaRepository;
    private final HytaleLogger logger;

    private final Object lock = new Object();
    private volatile Map<String, Arena> arenaById = Collections.emptyMap();

    /** arenaId -> reservationKey (duel session id / "tournament") */
    private final Map<String, String> reservations = new ConcurrentHashMap<>();

    public ArenaService(ArenaRepository arenaRepository, HytaleLogger logger) {
        this.arenaRepository = arenaRepository;
        this.logger = logger;
        reloadFromRepo();
    }

    public void reloadFromRepo() {
        if (arenaRepository == null) return;
        arenaRepository.reload();
        ArenaRepository.ArenaConfigFile cfg = arenaRepository.get();

        Map<String, Arena> map = new HashMap<>();
        if (cfg != null && cfg.arenas != null) {
            for (Arena a : cfg.arenas) {
                if (a == null || a.id == null || a.id.isBlank()) continue;
                a.normalize();
                map.put(a.id.toLowerCase(Locale.ROOT), a);
            }
        }

        synchronized (lock) {
            arenaById = map;
        }
    }

    public List<Arena> listArenas() {
        synchronized (lock) {
            return new ArrayList<>(arenaById.values());
        }
    }

    public Arena getArena(String arenaId) {
        if (arenaId == null) return null;
        synchronized (lock) {
            return arenaById.get(arenaId.toLowerCase(Locale.ROOT));
        }
    }

    public boolean isArenaReserved(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) return false;
        return reservations.containsKey(arenaId.toLowerCase(Locale.ROOT));
    }

    public ArenaAvailability getAvailabilitySnapshot() {
        int total = 0;
        int valid = 0;
        int reserved = 0;

        synchronized (lock) {
            for (Arena a : arenaById.values()) {
                if (a == null) continue;
                total++;
                if (!a.isValidForUse()) continue;
                valid++;

                String id = (a.id == null) ? "" : a.id.toLowerCase(Locale.ROOT);
                if (!id.isEmpty() && reservations.containsKey(id)) {
                    reserved++;
                }
            }
        }

        int free = Math.max(0, valid - reserved);
        return new ArenaAvailability(total, valid, reserved, free);
    }

    public Arena allocateAnyFreeArena(String reservationKey) {
        if (reservationKey == null || reservationKey.isBlank()) return null;

        List<Arena> arenas = listArenas();
        for (Arena a : arenas) {
            if (a == null) continue;
            String id = (a.id == null) ? "" : a.id.toLowerCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            if (!a.isValidForUse()) continue;

            String existing = reservations.putIfAbsent(id, reservationKey);
            if (existing == null) {
                if (logger != null) {
                    logger.atInfo().log("[DuelArena] reserved arena=%s key=%s", a.id, reservationKey);
                }
                return a;
            }
        }

        return null;
    }

    public void releaseArena(String arenaId, String reservationKey) {
        if (arenaId == null || arenaId.isBlank()) return;
        if (reservationKey == null || reservationKey.isBlank()) return;
        String id = arenaId.toLowerCase(Locale.ROOT);
        String cur = reservations.get(id);
        if (cur != null && cur.equals(reservationKey)) {
            reservations.remove(id);
            if (logger != null) {
                logger.atInfo().log("[DuelArena] released arena=%s key=%s", arenaId, reservationKey);
            }
        }
    }

    public Zone zoneAt(String worldName, int bx, int by, int bz) {
        if (worldName == null || worldName.isBlank()) return Zone.NONE;

        List<Arena> arenas = listArenas();
        for (Arena a : arenas) {
            if (a == null) continue;
            if (a.world == null) continue;
            if (!a.world.equals(worldName)) continue;

            if (a.bounds != null && a.bounds.containsBlock(bx, by, bz)) return Zone.ARENA;
            if (a.spectatorBounds != null && a.spectatorBounds.containsBlock(bx, by, bz)) return Zone.SPECTATOR;
        }

        return Zone.NONE;
    }
}
