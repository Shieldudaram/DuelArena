package com.Chris__.duel_arena.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TeleportService {

    public record PendingTeleport(String worldName, double x, double y, double z) {
    }

    private final Map<String, PendingTeleport> pendingByUuid = new ConcurrentHashMap<>();

    public void queueTeleport(String uuid, String worldName, double x, double y, double z) {
        if (uuid == null || uuid.isBlank()) return;
        if (worldName == null || worldName.isBlank()) return;
        pendingByUuid.put(uuid, new PendingTeleport(worldName, x, y, z));
    }

    public PendingTeleport poll(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        return pendingByUuid.remove(uuid);
    }

    public PendingTeleport peek(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        return pendingByUuid.get(uuid);
    }
}

