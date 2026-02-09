package com.Chris__.duel_arena.tourney;

import com.Chris__.duel_arena.arena.Arena;
import com.Chris__.duel_arena.arena.ArenaService;
import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.config.DuelConfig;
import com.Chris__.duel_arena.duel.DuelCancelReason;
import com.Chris__.duel_arena.duel.DuelResult;
import com.Chris__.duel_arena.duel.DuelRules;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.duel.DuelServiceListener;
import com.Chris__.duel_arena.duel.DuelSession;
import com.Chris__.duel_arena.util.InventoryUtil;
import com.Chris__.duel_arena.util.TeleportService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class TournamentService implements DuelServiceListener {

    public enum JoinStatus {
        JOINED,
        ALREADY_JOINED,
        NOT_ENABLED,
        TOURNAMENT_RUNNING,
        DIFFERENT_SIZE_ACTIVE,
        FULL,
        NOT_ENOUGH_POINTS,
        INVALID_SIZE
    }

    public record JoinResult(JoinStatus status, TournamentState tournament) {
    }

    private final Object lock = new Object();
    private final AtomicLong idCounter = new AtomicLong(ThreadLocalRandom.current().nextLong(10_000L, 9_999_999L));

    private final ConfigRepository configRepository;
    private final ArenaService arenaService;
    private final DuelService duelService;
    private final com.Chris__.duel_arena.data.DuelPointsRepository pointsRepository;
    private final com.Chris__.duel_arena.data.PendingDeliveriesRepository pendingDeliveriesRepository;
    private final TeleportService teleportService;
    private final HytaleLogger logger;
    private final Consumer<DuelSession> uiRefresh;

    private volatile TournamentState active = null;

    public TournamentService(ConfigRepository configRepository,
                             ArenaService arenaService,
                             DuelService duelService,
                             com.Chris__.duel_arena.data.DuelPointsRepository pointsRepository,
                             com.Chris__.duel_arena.data.PendingDeliveriesRepository pendingDeliveriesRepository,
                             TeleportService teleportService,
                             Consumer<DuelSession> uiRefresh,
                             HytaleLogger logger) {
        this.configRepository = configRepository;
        this.arenaService = arenaService;
        this.duelService = duelService;
        this.pointsRepository = pointsRepository;
        this.pendingDeliveriesRepository = pendingDeliveriesRepository;
        this.teleportService = teleportService;
        this.uiRefresh = uiRefresh;
        this.logger = logger;
    }

    public TournamentState getActive() {
        return active;
    }

    public JoinResult join(String uuid, int size) {
        if (uuid == null || uuid.isBlank()) return new JoinResult(JoinStatus.INVALID_SIZE, null);

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        if (cfg == null || cfg.tournament == null || !cfg.tournament.enabled) return new JoinResult(JoinStatus.NOT_ENABLED, null);

        if (cfg.tournament.sizes == null || !cfg.tournament.sizes.contains(size)) {
            return new JoinResult(JoinStatus.INVALID_SIZE, null);
        }

        synchronized (lock) {
            TournamentState t = active;
            if (t == null) {
                t = new TournamentState();
                t.id = newTournamentId();
                t.size = size;
                t.status = TournamentState.Status.LOBBY;

                // Entry fee (points only for now).
                int fee = 0;
                if (cfg.tournament.entryFee != null && cfg.tournament.entryFee.enabled) {
                    if ("points".equalsIgnoreCase(cfg.tournament.entryFee.type)) {
                        fee = Math.max(0, cfg.tournament.entryFee.points);
                    }
                }
                t.entryFeePoints = fee;

                active = t;
            } else {
                if (t.status != TournamentState.Status.LOBBY) return new JoinResult(JoinStatus.TOURNAMENT_RUNNING, t);
                if (t.size != size) return new JoinResult(JoinStatus.DIFFERENT_SIZE_ACTIVE, t);
            }

            if (t.hasEntrant(uuid)) return new JoinResult(JoinStatus.ALREADY_JOINED, t);
            if (t.entrants.size() >= t.size) return new JoinResult(JoinStatus.FULL, t);

            if (t.entryFeePoints > 0) {
                if (pointsRepository == null) return new JoinResult(JoinStatus.NOT_ENOUGH_POINTS, t);
                if (!pointsRepository.spendPoints(uuid, t.entryFeePoints)) {
                    return new JoinResult(JoinStatus.NOT_ENOUGH_POINTS, t);
                }
                t.entryFeePaidUuids.add(uuid);
            }

            t.entrants.add(uuid);
            broadcastToEntrants(t, Message.raw("[DuelArena] Tournament lobby: " + t.entrants.size() + "/" + t.size));
            return new JoinResult(JoinStatus.JOINED, t);
        }
    }

    public boolean leave(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        synchronized (lock) {
            TournamentState t = active;
            if (t == null) return false;
            if (t.status != TournamentState.Status.LOBBY) return false;
            boolean removed = t.entrants.remove(uuid);
            if (!removed) return false;

            // Refund entry fee if it was paid.
            if (t.entryFeePoints > 0 && t.entryFeePaidUuids.remove(uuid) && pointsRepository != null) {
                pointsRepository.addPoints(uuid, t.entryFeePoints);
            }

            broadcastToEntrants(t, Message.raw("[DuelArena] Tournament lobby: " + t.entrants.size() + "/" + t.size));
            if (t.entrants.isEmpty()) {
                active = null;
            }
            return true;
        }
    }

    public boolean cancelActive() {
        TournamentState t;
        synchronized (lock) {
            t = active;
        }
        if (t == null) return false;
        cancelTournament(t, "canceled");
        return true;
    }

    public void tick(long nowMillis) {
        TournamentState t = active;
        if (t == null) return;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        if (cfg == null || cfg.tournament == null || !cfg.tournament.enabled) return;
        if (!cfg.tournament.autoStartWhenFull) return;

        synchronized (lock) {
            if (active == null) return;
            if (active.status != TournamentState.Status.LOBBY) return;
            if (active.entrants.size() < active.size) return;
        }

        startIfFull();
    }

    public boolean startIfFull() {
        TournamentState t;
        synchronized (lock) {
            t = active;
            if (t == null) return false;
            if (t.status != TournamentState.Status.LOBBY) return false;
            if (t.entrants.size() < t.size) return false;
        }
        return startTournament(t);
    }

    private boolean startTournament(TournamentState t) {
        if (t == null) return false;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        if (cfg == null || cfg.tournament == null || !cfg.tournament.enabled) return false;

        Arena arena = null;
        String reservationKey = "tournament:" + t.id;

        if (arenaService != null) {
            arena = arenaService.allocateAnyFreeArena(reservationKey);
        }
        if (arena == null) {
            broadcastToEntrants(t, Message.raw("[DuelArena] No free duel arenas available for tournament."));
            cancelTournament(t, "no_arena");
            return false;
        }

        boolean releaseAllocatedArena = false;
        synchronized (lock) {
            if (active == null || active != t) {
                releaseAllocatedArena = true;
            } else if (t.status != TournamentState.Status.LOBBY) {
                releaseAllocatedArena = true;
            } else {
                t.arena = arena;
                t.arenaReservationKey = reservationKey;
                t.status = TournamentState.Status.RUNNING;

                // Build initial round players (shuffle for randomness).
                t.currentRoundPlayers = new ArrayList<>(t.entrants);
                Collections.shuffle(t.currentRoundPlayers, ThreadLocalRandom.current());
                t.nextRoundPlayers = new ArrayList<>();
                t.rounds = new ArrayList<>();
                t.currentRoundNumber = 1;
                t.currentMatchIndex = 0;

                t.rounds.add(buildRound(t.currentRoundNumber, t.currentRoundPlayers));
            }
        }
        if (releaseAllocatedArena) {
            releaseArenaReservation(arena, reservationKey);
            return false;
        }

        broadcastToEntrants(t, Message.raw("[DuelArena] Tournament started! Arena=" + safeArenaId(arena)));

        // Teleport entrants to spectator hub.
        teleportEntrantsToHub(t);

        // Start first match.
        startNextMatch();
        return true;
    }

    private void startNextMatch() {
        TournamentState t = active;
        if (t == null) return;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        if (cfg == null || cfg.tournament == null) return;

        TournamentState.Match match;
        DuelRules rules;
        Arena arena;
        String reservationKey;

        synchronized (lock) {
            t = active;
            if (t == null) return;
            if (t.status != TournamentState.Status.RUNNING) return;
            arena = t.arena;
            reservationKey = t.arenaReservationKey;
            if (arena == null) {
                cancelTournament(t, "arena_missing");
                return;
            }

            // End condition: one player left.
            if (t.currentRoundPlayers.size() <= 1) {
                t.championUuid = t.currentRoundPlayers.isEmpty() ? "" : t.currentRoundPlayers.get(0);
                t.status = TournamentState.Status.COMPLETED;
                finishTournament(t);
                return;
            }

            TournamentState.Round round = t.rounds.isEmpty() ? null : t.rounds.get(t.rounds.size() - 1);
            if (round == null || round.matches == null) {
                cancelTournament(t, "round_missing");
                return;
            }

            if (t.currentMatchIndex < 0 || t.currentMatchIndex >= round.matches.size()) {
                // Round complete: advance to next round.
                if (t.nextRoundPlayers.size() <= 1) {
                    // Final match just finished.
                    t.championUuid = t.nextRoundPlayers.isEmpty() ? "" : t.nextRoundPlayers.get(0);
                    t.status = TournamentState.Status.COMPLETED;
                    finishTournament(t);
                    return;
                }

                t.currentRoundPlayers = new ArrayList<>(t.nextRoundPlayers);
                t.nextRoundPlayers = new ArrayList<>();
                t.currentRoundNumber++;
                t.currentMatchIndex = 0;
                t.rounds.add(buildRound(t.currentRoundNumber, t.currentRoundPlayers));
                round = t.rounds.get(t.rounds.size() - 1);
            }

            match = round.matches.get(t.currentMatchIndex);
            if (match == null) {
                cancelTournament(t, "match_missing");
                return;
            }

            rules = DuelRules.fromDefaults(cfg);
            if (cfg.tournament.forceRanked) rules.ranked = true;
            rules.normalize();
        }

        if (match.aUuid == null || match.aUuid.isBlank() || match.bUuid == null || match.bUuid.isBlank()) {
            cancelTournament(t, "bad_match");
            return;
        }

        // Create the duel offer for this match (offer/confirm screens still apply).
        DuelService.CreateOfferResult offer = duelService.createTournamentOffer(match.aUuid, match.bUuid, t.id, arena, reservationKey, rules);
        if (offer.status() != DuelService.CreateOfferStatus.CREATED || offer.session() == null) {
            cancelTournament(t, "duel_create_failed");
            return;
        }

        match.duelSessionId = offer.session().id;

        if (uiRefresh != null) {
            try {
                uiRefresh.accept(offer.session());
            } catch (Throwable ignored) {
            }
        }

        broadcastToEntrants(t, Message.raw("[DuelArena] Round " + t.currentRoundNumber + " Match " + (t.currentMatchIndex + 1) +
                ": " + shortUuid(match.aUuid) + " vs " + shortUuid(match.bUuid)));
    }

    @Override
    public void onDuelEnded(DuelSession session, DuelResult result) {
        if (session == null || result == null) return;
        if (!session.tournamentMatch) return;

        TournamentState t = active;
        if (t == null) return;
        if (t.status != TournamentState.Status.RUNNING) return;
        if (session.tournamentId == null || !session.tournamentId.equals(t.id)) return;

        synchronized (lock) {
            t = active;
            if (t == null) return;
            if (t.status != TournamentState.Status.RUNNING) return;

            TournamentState.Match match = findMatchBySessionId(t, session.id);
            if (match == null) return;

            match.winnerUuid = result.winnerUuid();
            match.loserUuid = result.loserUuid();

            // Runner-up is the loser of the final match (when only 2 players were left).
            if (t.currentRoundPlayers != null && t.currentRoundPlayers.size() == 2) {
                t.runnerUpUuid = result.loserUuid();
            }

            if (t.nextRoundPlayers == null) t.nextRoundPlayers = new ArrayList<>();
            t.nextRoundPlayers.add(result.winnerUuid());

            t.currentMatchIndex++;
        }

        // Keep everyone in the hub between matches.
        teleportEntrantsToHub(t);

        startNextMatch();
    }

    @Override
    public void onDuelCanceled(DuelSession session, DuelCancelReason reason) {
        if (session == null) return;
        if (!session.tournamentMatch) return;
        TournamentState t = active;
        if (t == null) return;
        if (session.tournamentId == null || !session.tournamentId.equals(t.id)) return;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        boolean cancelOnTimeout = cfg != null && cfg.tournament != null && cfg.tournament.cancelIfMatchOfferTimeout;

        if (reason == DuelCancelReason.TIMEOUT && !cancelOnTimeout) {
            return;
        }

        cancelTournament(t, "match_canceled:" + reason);
    }

    private void cancelTournament(TournamentState t, String reason) {
        if (t == null) return;

        synchronized (lock) {
            if (active == null || active != t) return;
            if (t.status == TournamentState.Status.CANCELED || t.status == TournamentState.Status.COMPLETED) return;
            t.status = TournamentState.Status.CANCELED;
        }

        broadcastToEntrants(t, Message.raw("[DuelArena] Tournament canceled (" + reason + ")."));

        // Refund entry fees.
        if (t.entryFeePoints > 0 && pointsRepository != null) {
            for (String u : new ArrayList<>(t.entryFeePaidUuids)) {
                if (u == null || u.isBlank()) continue;
                pointsRepository.addPoints(u, t.entryFeePoints);
            }
        }

        // Release arena lock.
        if (arenaService != null && t.arena != null && t.arena.id != null && !t.arena.id.isBlank()) {
            arenaService.releaseArena(t.arena.id, t.arenaReservationKey);
        }

        synchronized (lock) {
            if (active == t) active = null;
        }
    }

    private void finishTournament(TournamentState t) {
        if (t == null) return;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        DuelConfig.Tournament.Rewards rewards = (cfg == null || cfg.tournament == null) ? null : cfg.tournament.rewards;

        String winner = t.championUuid;
        String runnerUp = t.runnerUpUuid;

        try {
            broadcastToEntrants(t, Message.raw("[DuelArena] Tournament complete! Winner: " + shortUuid(winner)));

            if (rewards != null) {
                // Participation points.
                int participation = Math.max(0, rewards.participationPoints);
                if (participation > 0 && pointsRepository != null) {
                    for (String u : t.entrants) {
                        if (u == null || u.isBlank()) continue;
                        pointsRepository.addPoints(u, participation);
                    }
                }

                // Winner/runner-up bonuses.
                if (pointsRepository != null) {
                    int wBonus = Math.max(0, rewards.winnerPointsBonus);
                    int rBonus = Math.max(0, rewards.runnerUpPointsBonus);
                    if (wBonus > 0 && winner != null && !winner.isBlank()) pointsRepository.addPoints(winner, wBonus);
                    if (rBonus > 0 && runnerUp != null && !runnerUp.isBlank()) pointsRepository.addPoints(runnerUp, rBonus);
                }

                // Winner items.
                if (rewards.winnerItems != null && winner != null && !winner.isBlank()) {
                    for (DuelConfig.Tournament.ItemReward r : rewards.winnerItems) {
                        if (r == null) continue;
                        String itemId = (r.itemId == null) ? "" : r.itemId.trim();
                        int amount = Math.max(1, r.amount);
                        if (itemId.isEmpty() || "REPLACE_ME".equalsIgnoreCase(itemId)) continue;

                        ItemStack st;
                        try {
                            st = new ItemStack(itemId, amount);
                        } catch (Throwable ignored) {
                            st = null;
                        }
                        if (st == null || st.isEmpty()) continue;

                        deliverRewardItem(winner, st);
                    }
                }
            } else if (logger != null) {
                logger.atWarning().log("[DuelArena] Tournament rewards config is missing; skipping payouts. tournament=%s", t.id);
            }
        } finally {
            releaseArenaReservation(t.arena, t.arenaReservationKey);
            synchronized (lock) {
                if (active == t) active = null;
            }
        }
    }

    private void deliverRewardItem(String uuid, ItemStack st) {
        if (uuid == null || uuid.isBlank()) return;
        if (st == null || st.isEmpty() || st.getQuantity() <= 0) return;

        Player p = findOnlinePlayer(uuid);
        if (p == null) {
            if (pendingDeliveriesRepository != null) pendingDeliveriesRepository.add(uuid, st);
            return;
        }

        boolean ok = InventoryUtil.tryGive(p, st);
        if (ok) {
            try {
                p.sendInventory();
            } catch (Throwable ignored) {
            }
        } else if (pendingDeliveriesRepository != null) {
            pendingDeliveriesRepository.add(uuid, st);
        }
    }

    private void teleportEntrantsToHub(TournamentState t) {
        if (t == null) return;
        if (teleportService == null) return;
        if (t.arena == null) return;
        for (String u : t.entrants) {
            if (u == null || u.isBlank()) continue;
            teleportService.queueTeleport(u, t.arena.world, t.arena.spectatorSpawn.position().getX(), t.arena.spectatorSpawn.position().getY(), t.arena.spectatorSpawn.position().getZ());
        }
    }

    private void broadcastToEntrants(TournamentState t, Message msg) {
        if (t == null || msg == null) return;
        for (String u : t.entrants) {
            if (u == null || u.isBlank()) continue;
            Player p = findOnlinePlayer(u);
            if (p != null) {
                try {
                    p.sendMessage(msg);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static TournamentState.Round buildRound(int roundNumber, List<String> players) {
        TournamentState.Round r = new TournamentState.Round();
        r.roundNumber = roundNumber;
        r.matches = new ArrayList<>();
        if (players == null) return r;

        int matchNo = 1;
        for (int i = 0; i + 1 < players.size(); i += 2) {
            TournamentState.Match m = new TournamentState.Match();
            m.roundNumber = roundNumber;
            m.matchNumber = matchNo++;
            m.aUuid = players.get(i);
            m.bUuid = players.get(i + 1);
            r.matches.add(m);
        }
        return r;
    }

    private static TournamentState.Match findMatchBySessionId(TournamentState t, String sessionId) {
        if (t == null) return null;
        if (sessionId == null || sessionId.isBlank()) return null;
        if (t.rounds == null) return null;
        for (TournamentState.Round r : t.rounds) {
            if (r == null || r.matches == null) continue;
            for (TournamentState.Match m : r.matches) {
                if (m == null) continue;
                if (sessionId.equals(m.duelSessionId)) return m;
            }
        }
        return null;
    }

    private Player findOnlinePlayer(String uuidString) {
        try {
            if (uuidString == null || uuidString.isBlank()) return null;
            UUID u = UUID.fromString(uuidString);
            PlayerRef pr = Universe.get().getPlayer(u);
            if (pr == null) return null;
            return pr.getComponent(Player.getComponentType());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeArenaId(Arena a) {
        if (a == null || a.id == null) return "";
        return a.id;
    }

    private void releaseArenaReservation(Arena arena, String reservationKey) {
        if (arenaService == null || arena == null || arena.id == null || arena.id.isBlank()) return;
        if (reservationKey == null || reservationKey.isBlank()) return;
        arenaService.releaseArena(arena.id, reservationKey);
    }

    private String newTournamentId() {
        return "T" + idCounter.getAndIncrement();
    }

    private static String shortUuid(String uuid) {
        if (uuid == null) return "";
        if (uuid.length() <= 8) return uuid;
        return uuid.substring(0, 8);
    }
}
