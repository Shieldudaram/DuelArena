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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DuelEloRepository {

    private static final String FILE_NAME = "duel_elo.json";
    private static final Type MAP_TYPE = new TypeToken<Map<String, EloEntry>>() {
    }.getType();

    public static final class EloEntry {
        public int rating = 1000;
        public int games = 0;
        public int wins = 0;
        public int losses = 0;
        public String lastKnownName = "";
    }

    public record EloDelta(int winnerBefore, int winnerAfter, int loserBefore, int loserAfter) {
    }

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private final int initialRating;
    private final int kFactor;

    private final Map<String, EloEntry> byUuid = new HashMap<>();

    public DuelEloRepository(Path dataDirectory, HytaleLogger logger, int initialRating, int kFactor) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        this.initialRating = Math.max(1, initialRating);
        this.kFactor = Math.max(1, kFactor);
        load();
    }

    public EloEntry getOrCreate(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        synchronized (lock) {
            return byUuid.computeIfAbsent(uuid, k -> {
                EloEntry e = new EloEntry();
                e.rating = initialRating;
                return e;
            });
        }
    }

    public int getRating(String uuid) {
        EloEntry e = getOrCreate(uuid);
        return (e == null) ? initialRating : Math.max(1, e.rating);
    }

    public void setLastKnownName(String uuid, String name) {
        if (uuid == null || uuid.isBlank()) return;
        synchronized (lock) {
            EloEntry e = getOrCreate(uuid);
            if (e == null) return;
            e.lastKnownName = (name == null) ? "" : name;
            save();
        }
    }

    public EloDelta recordRankedMatch(String winnerUuid, String loserUuid) {
        if (winnerUuid == null || winnerUuid.isBlank()) return null;
        if (loserUuid == null || loserUuid.isBlank()) return null;
        if (winnerUuid.equals(loserUuid)) return null;

        synchronized (lock) {
            EloEntry w = getOrCreate(winnerUuid);
            EloEntry l = getOrCreate(loserUuid);
            if (w == null || l == null) return null;

            int wBefore = Math.max(1, w.rating);
            int lBefore = Math.max(1, l.rating);

            double expectedW = 1.0d / (1.0d + Math.pow(10.0d, (lBefore - wBefore) / 400.0d));
            double expectedL = 1.0d - expectedW;

            int wAfter = (int) Math.round(wBefore + kFactor * (1.0d - expectedW));
            int lAfter = (int) Math.round(lBefore + kFactor * (0.0d - expectedL));

            if (wAfter < 1) wAfter = 1;
            if (lAfter < 1) lAfter = 1;

            w.rating = wAfter;
            l.rating = lAfter;

            w.games++;
            l.games++;
            w.wins++;
            l.losses++;

            save();

            return new EloDelta(wBefore, wAfter, lBefore, lAfter);
        }
    }

    public List<Map.Entry<String, EloEntry>> topByRating(int limit) {
        int lim = Math.max(1, Math.min(100, limit));
        synchronized (lock) {
            List<Map.Entry<String, EloEntry>> list = new ArrayList<>(byUuid.entrySet());
            list.sort(Comparator.<Map.Entry<String, EloEntry>>comparingInt(e -> (e.getValue() == null) ? 0 : e.getValue().rating).reversed());
            if (list.size() > lim) {
                return new ArrayList<>(list.subList(0, lim));
            }
            return list;
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
                    Map<String, EloEntry> loaded = gson.fromJson(r, MAP_TYPE);
                    if (loaded != null) {
                        for (Map.Entry<String, EloEntry> e : loaded.entrySet()) {
                            if (e.getKey() == null || e.getKey().isBlank()) continue;
                            EloEntry v = (e.getValue() == null) ? new EloEntry() : e.getValue();
                            v.rating = Math.max(1, v.rating);
                            if (v.lastKnownName == null) v.lastKnownName = "";
                            byUuid.put(e.getKey(), v);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[DuelArena] Failed to load duel_elo.json.");
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
            logger.atWarning().withCause(t).log("[DuelArena] Failed to save duel_elo.json.");
        }
    }
}

