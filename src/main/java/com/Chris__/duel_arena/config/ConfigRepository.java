package com.Chris__.duel_arena.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigRepository {

    private static final String FILE_NAME = "duel_config.json";

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private volatile DuelConfig cached = null;

    public ConfigRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        reload();
    }

    public DuelConfig get() {
        DuelConfig c = cached;
        if (c != null) return c;
        c = normalize(new DuelConfig());
        cached = c;
        return c;
    }

    public void reload() {
        synchronized (lock) {
            if (filePath == null) {
                cached = normalize(new DuelConfig());
                return;
            }

            try {
                Files.createDirectories(filePath.getParent());
                if (!Files.exists(filePath)) {
                    DuelConfig cfg = normalize(new DuelConfig());
                    cached = cfg;
                    save(cfg);
                    return;
                }

                try (Reader r = Files.newBufferedReader(filePath)) {
                    DuelConfig loaded = gson.fromJson(r, DuelConfig.class);
                    cached = normalize((loaded == null) ? new DuelConfig() : loaded);
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[DuelArena] Failed to load duel_config.json; using defaults.");
                cached = normalize(new DuelConfig());
            }
        }
    }

    public void saveCurrent() {
        DuelConfig c = get();
        save(c);
    }

    public void save(DuelConfig cfg) {
        if (filePath == null || cfg == null) return;
        synchronized (lock) {
            try {
                Files.createDirectories(filePath.getParent());
                try (Writer w = Files.newBufferedWriter(filePath)) {
                    gson.toJson(cfg, w);
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[DuelArena] Failed to save duel_config.json.");
            }
        }
    }

    private static DuelConfig normalize(DuelConfig cfg) {
        if (cfg == null) cfg = new DuelConfig();

        if (cfg.ui == null) cfg.ui = new DuelConfig.Ui();
        if (cfg.duel == null) cfg.duel = new DuelConfig.Duel();
        if (cfg.rules == null) cfg.rules = new DuelConfig.Rules();
        if (cfg.rules.defaults == null) cfg.rules.defaults = new DuelConfig.Rules.Defaults();
        if (cfg.staking == null) cfg.staking = new DuelConfig.Staking();
        if (cfg.elo == null) cfg.elo = new DuelConfig.Elo();
        if (cfg.tournament == null) cfg.tournament = new DuelConfig.Tournament();
        if (cfg.tournament.sizes == null) cfg.tournament.sizes = new java.util.ArrayList<>();
        if (cfg.tournament.entryFee == null) cfg.tournament.entryFee = new DuelConfig.Tournament.EntryFee();
        if (cfg.tournament.rewards == null) cfg.tournament.rewards = new DuelConfig.Tournament.Rewards();
        if (cfg.simpleClaims == null) cfg.simpleClaims = new DuelConfig.SimpleClaims();
        if (cfg.logging == null) cfg.logging = new DuelConfig.Logging();

        if (cfg.rules.armorSlotNames == null || cfg.rules.armorSlotNames.isEmpty()) {
            cfg.rules.armorSlotNames = new java.util.ArrayList<>(java.util.List.of("Armor 1", "Armor 2", "Armor 3", "Armor 4"));
        }
        if (cfg.rules.consumableItemIds == null) cfg.rules.consumableItemIds = new java.util.ArrayList<>();

        if (cfg.rules.defaults.armorSlotAllowed == null || cfg.rules.defaults.armorSlotAllowed.length != 4) {
            cfg.rules.defaults.armorSlotAllowed = new boolean[]{true, true, true, true};
        }

        if (cfg.staking.stakeItemScope == null || cfg.staking.stakeItemScope.isEmpty()) {
            cfg.staking.stakeItemScope = new java.util.ArrayList<>(java.util.List.of("hotbar", "tools", "utility"));
        }

        if (cfg.duel.requestTimeoutSeconds <= 0) cfg.duel.requestTimeoutSeconds = 60;
        if (cfg.duel.countdownSeconds < 0) cfg.duel.countdownSeconds = 0;
        if (cfg.duel.pointsPerWin < 0) cfg.duel.pointsPerWin = 0;

        if (cfg.staking.maxPointStake < 0) cfg.staking.maxPointStake = 0;
        if (cfg.staking.maxItemEntries < 0) cfg.staking.maxItemEntries = 0;
        if (cfg.staking.maxItemQuantityPerClick <= 0) cfg.staking.maxItemQuantityPerClick = 1;

        if (cfg.elo.initialRating <= 0) cfg.elo.initialRating = 1000;
        if (cfg.elo.kFactor <= 0) cfg.elo.kFactor = 32;

        return cfg;
    }
}

