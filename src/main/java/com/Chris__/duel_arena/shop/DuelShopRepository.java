package com.Chris__.duel_arena.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DuelShopRepository {

    private static final String FILE_NAME = "duel_shop.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private volatile DuelShopConfig cached = null;

    public DuelShopRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        reload();
    }

    public DuelShopConfig get() {
        DuelShopConfig c = cached;
        if (c != null) return c;
        c = defaultConfig();
        cached = c;
        return c;
    }

    public void reload() {
        if (filePath == null) {
            cached = defaultConfig();
            return;
        }

        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                DuelShopConfig cfg = defaultConfig();
                cached = cfg;
                save(cfg);
                return;
            }

            try (Reader r = Files.newBufferedReader(filePath)) {
                DuelShopConfig loaded = gson.fromJson(r, DuelShopConfig.class);
                cached = (loaded == null) ? defaultConfig() : loaded;
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[DuelArena] Failed to load duel_shop.json; using defaults.");
            cached = defaultConfig();
        }
    }

    private void save(DuelShopConfig cfg) {
        if (filePath == null || cfg == null) return;
        try (Writer w = Files.newBufferedWriter(filePath)) {
            gson.toJson(cfg, w);
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[DuelArena] Failed to save default duel_shop.json.");
        }
    }

    private static DuelShopConfig defaultConfig() {
        DuelShopConfig cfg = new DuelShopConfig();
        cfg.version = 1;

        DuelShopConfig.ShopItem example = new DuelShopConfig.ShopItem();
        example.id = "example";
        example.enabled = false;
        example.name = "Example Duel Reward";
        example.cost = 100;
        example.type = "item";
        example.itemId = "REPLACE_ME";
        example.amount = 1;
        cfg.items.add(example);

        return cfg;
    }
}

