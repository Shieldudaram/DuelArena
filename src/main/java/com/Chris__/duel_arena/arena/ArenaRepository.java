package com.Chris__.duel_arena.arena;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ArenaRepository {

    private static final String FILE_NAME = "duel_arenas.json";

    public static final class ArenaConfigFile {
        public int version = 1;
        public List<Arena> arenas = new ArrayList<>();
    }

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private volatile ArenaConfigFile cached = null;

    public ArenaRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        reload();
    }

    public ArenaConfigFile get() {
        ArenaConfigFile c = cached;
        if (c != null) return c;
        c = normalize(new ArenaConfigFile());
        cached = c;
        return c;
    }

    public void reload() {
        synchronized (lock) {
            if (filePath == null) {
                cached = normalize(new ArenaConfigFile());
                return;
            }

            try {
                Files.createDirectories(filePath.getParent());
                if (!Files.exists(filePath)) {
                    ArenaConfigFile cfg = normalize(new ArenaConfigFile());
                    cached = cfg;
                    save(cfg);
                    return;
                }

                try (Reader r = Files.newBufferedReader(filePath)) {
                    ArenaConfigFile loaded = gson.fromJson(r, ArenaConfigFile.class);
                    cached = normalize((loaded == null) ? new ArenaConfigFile() : loaded);
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[DuelArena] Failed to load duel_arenas.json; using defaults.");
                cached = normalize(new ArenaConfigFile());
            }
        }
    }

    public void saveCurrent() {
        save(get());
    }

    public void save(ArenaConfigFile cfg) {
        if (filePath == null || cfg == null) return;
        synchronized (lock) {
            try {
                Files.createDirectories(filePath.getParent());
                try (Writer w = Files.newBufferedWriter(filePath)) {
                    gson.toJson(cfg, w);
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[DuelArena] Failed to save duel_arenas.json.");
            }
        }
    }

    private static ArenaConfigFile normalize(ArenaConfigFile cfg) {
        if (cfg == null) cfg = new ArenaConfigFile();
        if (cfg.arenas == null) cfg.arenas = new ArrayList<>();
        for (Arena a : cfg.arenas) {
            if (a != null) a.normalize();
        }
        return cfg;
    }
}

