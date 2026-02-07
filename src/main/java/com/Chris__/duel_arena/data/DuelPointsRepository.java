package com.Chris__.duel_arena.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class DuelPointsRepository {

    private static final String FILE_NAME = "duel_points.json";
    private static final Type MAP_TYPE = new TypeToken<Map<String, PointsEntry>>() {
    }.getType();

    public static final class PointsEntry {
        public int points = 0;
        public String lastKnownName = "";
    }

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private final Map<String, PointsEntry> byUuid = new HashMap<>();

    public DuelPointsRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        load();
    }

    public int getPoints(String uuid) {
        if (uuid == null || uuid.isBlank()) return 0;
        synchronized (lock) {
            PointsEntry e = byUuid.get(uuid);
            if (e == null) return 0;
            return Math.max(0, e.points);
        }
    }

    public void setLastKnownName(String uuid, String name) {
        if (uuid == null || uuid.isBlank()) return;
        synchronized (lock) {
            PointsEntry e = byUuid.computeIfAbsent(uuid, k -> new PointsEntry());
            e.lastKnownName = (name == null) ? "" : name;
            save();
        }
    }

    public void addPoints(String uuid, int delta) {
        if (uuid == null || uuid.isBlank()) return;
        if (delta == 0) return;
        synchronized (lock) {
            PointsEntry e = byUuid.computeIfAbsent(uuid, k -> new PointsEntry());
            int cur = Math.max(0, e.points);
            long nextL = (long) cur + (long) delta;
            int next = (nextL > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) nextL;
            if (next < 0) next = 0;
            e.points = next;
            save();
        }
    }

    public boolean spendPoints(String uuid, int cost) {
        if (uuid == null || uuid.isBlank()) return false;
        if (cost <= 0) return true;
        synchronized (lock) {
            PointsEntry e = byUuid.computeIfAbsent(uuid, k -> new PointsEntry());
            int cur = Math.max(0, e.points);
            if (cur < cost) return false;
            e.points = cur - cost;
            save();
            return true;
        }
    }

    private void load() {
        if (filePath == null) return;
        synchronized (lock) {
            byUuid.clear();
            try {
                Files.createDirectories(filePath.getParent());
                if (!Files.exists(filePath)) {
                    save();
                    return;
                }

                try (Reader r = Files.newBufferedReader(filePath)) {
                    Map<String, PointsEntry> loaded = gson.fromJson(r, MAP_TYPE);
                    if (loaded != null) {
                        for (Map.Entry<String, PointsEntry> e : loaded.entrySet()) {
                            if (e.getKey() == null || e.getKey().isBlank()) continue;
                            PointsEntry pe = (e.getValue() == null) ? new PointsEntry() : e.getValue();
                            if (pe.lastKnownName == null) pe.lastKnownName = "";
                            pe.points = Math.max(0, pe.points);
                            byUuid.put(e.getKey(), pe);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[DuelArena] Failed to load duel_points.json.");
            }
        }
    }

    private void save() {
        if (filePath == null) return;
        try {
            Files.createDirectories(filePath.getParent());
            try (Writer w = Files.newBufferedWriter(filePath)) {
                gson.toJson(byUuid, MAP_TYPE, w);
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[DuelArena] Failed to save duel_points.json.");
        }
    }
}

