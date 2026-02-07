package com.Chris__.duel_arena.data;

import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.config.DuelConfig;
import com.Chris__.duel_arena.duel.DuelEndReason;
import com.Chris__.duel_arena.duel.DuelSession;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public final class DuelHistoryLogger {

    private final Object lock = new Object();
    private final Path dataDirectory;
    private final ConfigRepository configRepository;
    private final HytaleLogger logger;

    public DuelHistoryLogger(Path dataDirectory, ConfigRepository configRepository, HytaleLogger logger) {
        this.dataDirectory = dataDirectory;
        this.configRepository = configRepository;
        this.logger = logger;
    }

    public void logEnd(DuelSession session,
                       DuelEndReason reason,
                       boolean ranked,
                       String winnerUuid,
                       String loserUuid,
                       DuelEloRepository.EloDelta eloDelta,
                       int pointsAwarded,
                       int stakePointsTotal,
                       int stakeItemStacksTotal) {
        if (session == null) return;
        if (dataDirectory == null) return;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        if (cfg == null || cfg.logging == null || !cfg.logging.historyEnabled) return;

        String fileName = (cfg.logging.historyFile == null || cfg.logging.historyFile.isBlank())
                ? "duel_history.log"
                : cfg.logging.historyFile.trim();

        Path path = dataDirectory.resolve(fileName);
        String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        String arenaId = (session.arena == null || session.arena.id == null) ? "" : session.arena.id;
        String tournament = session.tournamentMatch ? session.tournamentId : "";

        String elo = "";
        if (eloDelta != null) {
            elo = " elo[w:" + eloDelta.winnerBefore() + "->" + eloDelta.winnerAfter() +
                    " l:" + eloDelta.loserBefore() + "->" + eloDelta.loserAfter() + "]";
        }

        String line = ts +
                " session=" + safe(session.id) +
                " arena=" + safe(arenaId) +
                (tournament.isBlank() ? "" : " tournament=" + safe(tournament)) +
                " ranked=" + ranked +
                " reason=" + reason +
                " winner=" + safe(winnerUuid) +
                " loser=" + safe(loserUuid) +
                " pointsAwarded=" + pointsAwarded +
                " stakePointsTotal=" + stakePointsTotal +
                " stakeItemStacksTotal=" + stakeItemStacksTotal +
                elo +
                "\n";

        synchronized (lock) {
            try {
                Files.createDirectories(path.getParent());
                try (Writer w = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                    w.write(line);
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[DuelArena] Failed to write duel history log.");
            }
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\n", "").replace("\r", "");
    }
}

