package com.Chris__.duel_arena.duel;

import com.Chris__.duel_arena.arena.Arena;
import com.Chris__.duel_arena.arena.ArenaService;
import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.config.DuelConfig;
import com.Chris__.duel_arena.data.DuelEloRepository;
import com.Chris__.duel_arena.data.DuelHistoryLogger;
import com.Chris__.duel_arena.data.DuelPointsRepository;
import com.Chris__.duel_arena.data.PendingDeliveriesRepository;
import com.Chris__.duel_arena.data.SerializedItemStack;
import com.Chris__.duel_arena.util.InventoryUtil;
import com.Chris__.duel_arena.util.TeleportService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class DuelService {

    public enum CreateOfferStatus {
        CREATED,
        SAME_PLAYER,
        ALREADY_IN_DUEL
    }

    public record CreateOfferResult(CreateOfferStatus status, DuelSession session) {
    }

    private static final long CLEANUP_AFTER_MILLIS = 60_000L;

    private final Object lock = new Object();
    private final HytaleLogger logger;

    private final ConfigRepository configRepository;
    private final ArenaService arenaService;
    private final DuelPointsRepository pointsRepository;
    private final DuelEloRepository eloRepository;
    private final PendingDeliveriesRepository pendingDeliveriesRepository;
    private final DuelHistoryLogger historyLogger;
    private final TeleportService teleportService;

    private final Map<String, DuelSession> sessionsById = new java.util.HashMap<>();
    private final Map<String, String> sessionIdByPlayerUuid = new java.util.HashMap<>();

    private final List<DuelServiceListener> listeners = new CopyOnWriteArrayList<>();

    private final ConcurrentLinkedQueue<EndRequest> pendingEndRequests = new ConcurrentLinkedQueue<>();

    private final AtomicLong idCounter = new AtomicLong(ThreadLocalRandom.current().nextLong(10_000L, 9_999_999L));

    public DuelService(ConfigRepository configRepository,
                       ArenaService arenaService,
                       DuelPointsRepository pointsRepository,
                       DuelEloRepository eloRepository,
                       PendingDeliveriesRepository pendingDeliveriesRepository,
                       DuelHistoryLogger historyLogger,
                       TeleportService teleportService,
                       HytaleLogger logger) {
        this.configRepository = configRepository;
        this.arenaService = arenaService;
        this.pointsRepository = pointsRepository;
        this.eloRepository = eloRepository;
        this.pendingDeliveriesRepository = pendingDeliveriesRepository;
        this.historyLogger = historyLogger;
        this.teleportService = teleportService;
        this.logger = logger;
    }

    public void addListener(DuelServiceListener listener) {
        if (listener == null) return;
        listeners.add(listener);
    }

    public CreateOfferResult createOffer(String challengerUuid, String targetUuid) {
        return createOfferInternal(challengerUuid, targetUuid, false, "", null, null);
    }

    public CreateOfferResult createTournamentOffer(String aUuid, String bUuid, String tournamentId, Arena arena, String arenaReservationKey, DuelRules forcedRules) {
        return createOfferInternal(aUuid, bUuid, true, tournamentId, arena, new TournamentOverrides(arenaReservationKey, forcedRules));
    }

    private record TournamentOverrides(String arenaReservationKey, DuelRules forcedRules) {
    }

    private CreateOfferResult createOfferInternal(String challengerUuid,
                                                 String targetUuid,
                                                 boolean tournamentMatch,
                                                 String tournamentId,
                                                 Arena forcedArena,
                                                 TournamentOverrides tournamentOverrides) {
        if (challengerUuid == null || challengerUuid.isBlank()) return new CreateOfferResult(CreateOfferStatus.ALREADY_IN_DUEL, null);
        if (targetUuid == null || targetUuid.isBlank()) return new CreateOfferResult(CreateOfferStatus.ALREADY_IN_DUEL, null);
        if (challengerUuid.equals(targetUuid)) return new CreateOfferResult(CreateOfferStatus.SAME_PLAYER, null);

        long now = System.currentTimeMillis();

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        DuelRules defaults = DuelRules.fromDefaults(cfg);

        synchronized (lock) {
            if (sessionIdByPlayerUuid.containsKey(challengerUuid) || sessionIdByPlayerUuid.containsKey(targetUuid)) {
                return new CreateOfferResult(CreateOfferStatus.ALREADY_IN_DUEL, null);
            }

            DuelSession s = new DuelSession();
            s.id = newSessionId();
            s.playerAUuid = challengerUuid;
            s.playerBUuid = targetUuid;
            s.createdAtMillis = now;
            s.stageStartedAtMillis = now;
            s.stage = DuelStage.OFFER;

            s.tournamentMatch = tournamentMatch;
            s.tournamentId = (tournamentId == null) ? "" : tournamentId;

            if (tournamentMatch) {
                s.stakingEnabled = false;
                s.rules = (tournamentOverrides != null && tournamentOverrides.forcedRules != null)
                        ? tournamentOverrides.forcedRules.copy()
                        : defaults.copy();
                if (cfg != null && cfg.tournament != null && cfg.tournament.forceRanked) {
                    s.rules.ranked = true;
                }
                s.arena = forcedArena;
                s.arenaReservationKey = (tournamentOverrides == null) ? "" : tournamentOverrides.arenaReservationKey;
            } else {
                s.stakingEnabled = cfg != null && cfg.staking != null && cfg.staking.enabled;
                s.rules = defaults.copy();
            }

            s.rules.normalize();

            sessionsById.put(s.id, s);
            sessionIdByPlayerUuid.put(challengerUuid, s.id);
            sessionIdByPlayerUuid.put(targetUuid, s.id);

            return new CreateOfferResult(CreateOfferStatus.CREATED, s);
        }
    }

    public DuelSession getSessionForPlayer(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        synchronized (lock) {
            String id = sessionIdByPlayerUuid.get(uuid);
            if (id == null) return null;
            return sessionsById.get(id);
        }
    }

    public DuelSession getSessionById(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        synchronized (lock) {
            return sessionsById.get(sessionId);
        }
    }

    public boolean toggleRule(String sessionId, String actorUuid, String ruleId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (actorUuid == null || actorUuid.isBlank()) return false;
        if (ruleId == null || ruleId.isBlank()) return false;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        boolean tournamentForceRanked = cfg != null && cfg.tournament != null && cfg.tournament.forceRanked;

        synchronized (lock) {
            DuelSession s = sessionsById.get(sessionId);
            if (s == null) return false;
            if (s.stage != DuelStage.OFFER) return false;
            if (!s.involves(actorUuid)) return false;
            if (s.tournamentMatch) return false; // fixed rules

            String id = ruleId.trim().toLowerCase(Locale.ROOT);
            boolean changed = false;

            if ("ranked".equals(id)) {
                boolean next = !s.rules.ranked;
                if (tournamentForceRanked) next = true;
                s.rules.ranked = next;
                changed = true;
            } else if ("melee".equals(id)) {
                s.rules.allowMelee = !s.rules.allowMelee;
                changed = true;
            } else if ("projectiles".equals(id) || "projectile".equals(id) || "ranged".equals(id)) {
                s.rules.allowProjectiles = !s.rules.allowProjectiles;
                changed = true;
            } else if ("consumables".equals(id) || "consumable".equals(id)) {
                s.rules.allowConsumables = !s.rules.allowConsumables;
                changed = true;
            } else if (id.startsWith("armor")) {
                try {
                    int idx = Integer.parseInt(id.replace("armor", "").trim());
                    if (idx >= 0 && idx < s.rules.armorSlotAllowed.length) {
                        s.rules.armorSlotAllowed[idx] = !s.rules.armorSlotAllowed[idx];
                        changed = true;
                    }
                } catch (Throwable ignored) {
                }
            }

            if (changed) {
                resetOfferAcceptance(s);
            }
            return changed;
        }
    }

    public boolean adjustPointStake(String sessionId, String actorUuid, int delta) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (actorUuid == null || actorUuid.isBlank()) return false;
        if (delta == 0) return false;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        int maxStake = (cfg == null || cfg.staking == null) ? 0 : Math.max(0, cfg.staking.maxPointStake);
        boolean enabled = cfg != null && cfg.staking != null && cfg.staking.enabled && cfg.staking.allowPointStake;

        synchronized (lock) {
            DuelSession s = sessionsById.get(sessionId);
            if (s == null) return false;
            if (s.stage != DuelStage.OFFER) return false;
            if (!s.involves(actorUuid)) return false;
            if (!s.stakingEnabled || s.tournamentMatch) return false;
            if (!enabled) return false;

            DuelStake stake = actorUuid.equals(s.playerAUuid) ? s.stakeA : s.stakeB;
            if (stake == null) return false;

            long nextL = (long) stake.points + (long) delta;
            int next = (nextL > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) nextL;
            if (next < 0) next = 0;
            if (maxStake > 0 && next > maxStake) next = maxStake;
            if (next == stake.points) return false;
            stake.points = next;
            resetOfferAcceptance(s);
            return true;
        }
    }

    public boolean addHeldItemStake(String sessionId, Player actor, int quantity) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (actor == null) return false;

        PlayerRef pr = actor.getPlayerRef();
        String actorUuid = (pr == null || pr.getUuid() == null) ? null : pr.getUuid().toString();
        if (actorUuid == null || actorUuid.isBlank()) return false;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        boolean enabled = cfg != null && cfg.staking != null && cfg.staking.enabled && cfg.staking.allowItemStake;
        int maxEntries = (cfg == null || cfg.staking == null) ? 0 : Math.max(0, cfg.staking.maxItemEntries);
        int maxQtyPerClick = (cfg == null || cfg.staking == null) ? Integer.MAX_VALUE : cfg.staking.maxItemQuantityPerClick;
        List<String> scope = (cfg == null || cfg.staking == null) ? List.of() : cfg.staking.stakeItemScope;

        int addQty = Math.max(1, quantity);
        if (addQty > maxQtyPerClick) addQty = maxQtyPerClick;

        ItemStack held = null;
        try {
            held = actor.getInventory().getItemInHand();
        } catch (Throwable ignored) {
            held = null;
        }
        if (held == null || held.isEmpty() || held.getQuantity() <= 0) return false;

        int available = InventoryUtil.countEquivalentInScopes(actor, held, scope);
        if (available <= 0) return false;

        synchronized (lock) {
            DuelSession s = sessionsById.get(sessionId);
            if (s == null) return false;
            if (s.stage != DuelStage.OFFER) return false;
            if (!s.involves(actorUuid)) return false;
            if (!s.stakingEnabled || s.tournamentMatch) return false;
            if (!enabled) return false;

            DuelStake stake = actorUuid.equals(s.playerAUuid) ? s.stakeA : s.stakeB;
            if (stake == null) return false;
            if (stake.items == null) stake.items = new ArrayList<>();

            if (maxEntries > 0 && stake.items.size() >= maxEntries) return false;

            int alreadyStaked = 0;
            for (SerializedItemStack it : stake.items) {
                if (it == null) continue;
                ItemStack cur = it.toItemStack();
                if (cur == null || cur.isEmpty()) continue;
                if (cur.isEquivalentType(held)) {
                    alreadyStaked += Math.max(0, it.quantity);
                }
            }

            int maxAdd = available - alreadyStaked;
            if (maxAdd <= 0) return false;
            if (addQty > maxAdd) addQty = maxAdd;
            if (addQty <= 0) return false;

            SerializedItemStack sItem = SerializedItemStack.from(held.withQuantity(addQty));
            if (sItem == null) return false;
            if (sItem.quantity <= 0) sItem.quantity = addQty;

            mergeStakeItem(stake, sItem, maxEntries);
            resetOfferAcceptance(s);
            return true;
        }
    }

    public boolean removeStakeItemAt(String sessionId, String actorUuid, int index) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (actorUuid == null || actorUuid.isBlank()) return false;
        int idx = index;
        if (idx < 0) return false;

        synchronized (lock) {
            DuelSession s = sessionsById.get(sessionId);
            if (s == null) return false;
            if (s.stage != DuelStage.OFFER) return false;
            if (!s.involves(actorUuid)) return false;
            if (!s.stakingEnabled || s.tournamentMatch) return false;

            DuelStake stake = actorUuid.equals(s.playerAUuid) ? s.stakeA : s.stakeB;
            if (stake == null || stake.items == null) return false;
            if (idx >= stake.items.size()) return false;
            stake.items.remove(idx);
            resetOfferAcceptance(s);
            return true;
        }
    }

    public boolean acceptOffer(String sessionId, String actorUuid) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (actorUuid == null || actorUuid.isBlank()) return false;
        synchronized (lock) {
            DuelSession s = sessionsById.get(sessionId);
            if (s == null) return false;
            if (s.stage != DuelStage.OFFER) return false;
            if (!s.involves(actorUuid)) return false;

            if (actorUuid.equals(s.playerAUuid)) s.offerAcceptedA = true;
            else if (actorUuid.equals(s.playerBUuid)) s.offerAcceptedB = true;

            if (s.offerAcceptedA && s.offerAcceptedB) {
                s.stage = DuelStage.CONFIRM;
                s.stageStartedAtMillis = System.currentTimeMillis();
                s.confirmAcceptedA = false;
                s.confirmAcceptedB = false;
            }
            return true;
        }
    }

    public boolean acceptConfirm(String sessionId, String actorUuid) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (actorUuid == null || actorUuid.isBlank()) return false;

        DuelSession toStart = null;
        synchronized (lock) {
            DuelSession s = sessionsById.get(sessionId);
            if (s == null) return false;
            if (s.stage != DuelStage.CONFIRM) return false;
            if (!s.involves(actorUuid)) return false;

            if (actorUuid.equals(s.playerAUuid)) s.confirmAcceptedA = true;
            else if (actorUuid.equals(s.playerBUuid)) s.confirmAcceptedB = true;

            if (s.confirmAcceptedA && s.confirmAcceptedB) {
                toStart = s;
            }
        }

        if (toStart != null) {
            startCountdown(toStart.id);
        }

        return true;
    }

    public void decline(String sessionId, String actorUuid) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (actorUuid == null || actorUuid.isBlank()) return;
        cancel(sessionId, DuelCancelReason.DECLINED);
    }

    public void startCountdown(String sessionId) {
        DuelSession s;
        synchronized (lock) {
            s = sessionsById.get(sessionId);
        }
        if (s == null) return;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        int countdownSeconds = (cfg == null || cfg.duel == null) ? 3 : Math.max(0, cfg.duel.countdownSeconds);

        long now = System.currentTimeMillis();

        DuelCancelReason cancelReason = null;
        Message startMessage = null;
        Message cancelMessage = null;

        synchronized (lock) {
            DuelSession cur = sessionsById.get(sessionId);
            if (cur == null) return;
            if (cur.stage != DuelStage.CONFIRM) return;

            if (!ensureArenaAllocated(cur)) {
                cancelReason = DuelCancelReason.NO_ARENA_AVAILABLE;
                ArenaService.ArenaAvailability availability = (arenaService == null) ? null : arenaService.getAvailabilitySnapshot();
                if (logger != null) {
                    logger.atWarning().log("[DuelArena] startCountdown canceled: no arena available session=%s playerA=%s playerB=%s arenas={%s}",
                            safe(cur.id), safe(cur.playerAUuid), safe(cur.playerBUuid), arenaAvailabilitySummary(availability));
                }
                cancelMessage = Message.raw("[DuelArena] No duel arena is currently free. Try again shortly.");
            }

            if (cancelReason == null && !ensurePlayersOnline(cur)) {
                cancelReason = DuelCancelReason.PLAYER_OFFLINE;
                if (logger != null) {
                    logger.atInfo().log("[DuelArena] startCountdown canceled: player offline session=%s playerA=%s playerB=%s",
                            safe(cur.id), safe(cur.playerAUuid), safe(cur.playerBUuid));
                }
                cancelMessage = Message.raw("[DuelArena] Duel cancelled because one participant is no longer online or in-world.");
            }

            if (cancelReason == null && cur.stakingEnabled && !cur.escrowed) {
                if (!escrowStakes(cur)) {
                    cancelReason = DuelCancelReason.STAKE_FAILED;
                }
            }

            if (cancelReason != null) {
                cur.stage = DuelStage.CANCELED;
                cur.stageStartedAtMillis = now;
                cur.cancelReason = cancelReason;
            } else {
                // Teleport into the arena spawns.
                if (teleportService != null && cur.arena != null) {
                    teleportService.queueTeleport(cur.playerAUuid, cur.arena.world, cur.arena.spawnA.position().getX(), cur.arena.spawnA.position().getY(), cur.arena.spawnA.position().getZ());
                    teleportService.queueTeleport(cur.playerBUuid, cur.arena.world, cur.arena.spawnB.position().getX(), cur.arena.spawnB.position().getY(), cur.arena.spawnB.position().getZ());
                }

                if (countdownSeconds <= 0) {
                    cur.stage = DuelStage.IN_PROGRESS;
                    cur.stageStartedAtMillis = now;
                    cur.countdownEndsAtMillis = 0L;
                    startMessage = Message.raw("[DuelArena] Fight!");
                } else {
                    cur.stage = DuelStage.COUNTDOWN;
                    cur.stageStartedAtMillis = now;
                    cur.countdownEndsAtMillis = now + (countdownSeconds * 1000L);
                    startMessage = Message.raw("[DuelArena] Duel starts in " + countdownSeconds + "…");
                }
            }
        }

        if (cancelReason != null) {
            onCanceledSideEffects(s, cancelReason, cancelMessage);
        } else if (startMessage != null) {
            sendMessageToBoth(s, startMessage);
        }
    }

    public void forfeit(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) return;
        DuelSession s = getSessionForPlayer(playerUuid);
        if (s == null) return;
        if (!s.isActiveCombat()) return;
        String other = s.other(playerUuid);
        if (other == null || other.isBlank()) return;
        pendingEndRequests.add(new EndRequest(other, playerUuid, DuelEndReason.FORFEIT));
    }

    public void onDisconnect(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) return;
        DuelSession s = getSessionForPlayer(playerUuid);
        if (s == null) return;
        if (s.stage == DuelStage.OFFER || s.stage == DuelStage.CONFIRM) {
            cancel(s.id, DuelCancelReason.PLAYER_OFFLINE);
            return;
        }
        if (s.isActiveCombat()) {
            String other = s.other(playerUuid);
            if (other == null || other.isBlank()) return;
            pendingEndRequests.add(new EndRequest(other, playerUuid, DuelEndReason.DISCONNECT));
        }
    }

    private record EndRequest(String winnerUuid, String loserUuid, DuelEndReason reason) {
    }

    public boolean shouldCancelConsumableUse(String playerUuid, String itemId) {
        if (playerUuid == null || playerUuid.isBlank()) return false;
        if (itemId == null || itemId.isBlank()) return false;

        DuelSession s = getSessionForPlayer(playerUuid);
        if (s == null || !s.isActiveCombat()) return false;
        if (s.rules.allowConsumables) return false;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        if (cfg == null || cfg.rules == null || cfg.rules.consumableItemIds == null) return false;

        String id = itemId.trim().toLowerCase(Locale.ROOT);
        for (String allowed : cfg.rules.consumableItemIds) {
            if (allowed == null) continue;
            if (allowed.trim().toLowerCase(Locale.ROOT).equals(id)) return true;
        }

        return false;
    }

    /**
     * @return true to cancel the damage event.
     */
    public boolean shouldCancelPlayerDamage(String attackerUuid, String victimUuid, boolean projectile) {
        if (victimUuid == null || victimUuid.isBlank()) return false;

        DuelSession victimSession = getSessionForPlayer(victimUuid);
        if (victimSession != null && victimSession.isActiveCombat()) {
            String opponent = victimSession.other(victimUuid);

            // Block all damage during countdown.
            if (victimSession.stage == DuelStage.COUNTDOWN) return true;

            // Only allow opponent damage.
            if (attackerUuid == null || attackerUuid.isBlank() || !attackerUuid.equals(opponent)) return true;

            if (victimSession.stage != DuelStage.IN_PROGRESS) return true;

            if (projectile) {
                return !victimSession.rules.allowProjectiles;
            }
            return !victimSession.rules.allowMelee;
        }

        // Not in an active duel: allow damage.
        return false;
    }

    public boolean isKoModeEnabled() {
        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        return cfg != null && cfg.duel != null && cfg.duel.koMode;
    }

    public void queueKo(String winnerUuid, String loserUuid) {
        if (winnerUuid == null || winnerUuid.isBlank()) return;
        if (loserUuid == null || loserUuid.isBlank()) return;
        pendingEndRequests.add(new EndRequest(winnerUuid, loserUuid, DuelEndReason.KO));
    }

    public void tick(long nowMillis) {
        // 1) Apply pending end requests (KO/forfeit/disconnect).
        EndRequest req;
        while ((req = pendingEndRequests.poll()) != null) {
            if (req.winnerUuid == null || req.loserUuid == null) continue;
            finishByUuids(req.winnerUuid, req.loserUuid, req.reason);
        }

        // 2) Handle timeouts + countdown completion + cleanup.
        List<DuelSession> ended = new ArrayList<>();
        List<DuelSession> canceled = new ArrayList<>();
        List<DuelCancelReason> canceledReasons = new ArrayList<>();

        List<String> toRemoveIds = new ArrayList<>();

        synchronized (lock) {
            DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
            int timeoutSeconds = (cfg == null || cfg.duel == null) ? 60 : Math.max(1, cfg.duel.requestTimeoutSeconds);
            long timeoutMillis = timeoutSeconds * 1000L;

            for (DuelSession s : sessionsById.values()) {
                if (s == null) continue;

                if (s.stage == DuelStage.OFFER) {
                    if ((nowMillis - s.createdAtMillis) > timeoutMillis) {
                        s.stage = DuelStage.CANCELED;
                        s.stageStartedAtMillis = nowMillis;
                        s.cancelReason = DuelCancelReason.TIMEOUT;
                        canceled.add(s);
                        canceledReasons.add(DuelCancelReason.TIMEOUT);
                    }
                } else if (s.stage == DuelStage.CONFIRM) {
                    if ((nowMillis - s.stageStartedAtMillis) > timeoutMillis) {
                        s.stage = DuelStage.CANCELED;
                        s.stageStartedAtMillis = nowMillis;
                        s.cancelReason = DuelCancelReason.TIMEOUT;
                        canceled.add(s);
                        canceledReasons.add(DuelCancelReason.TIMEOUT);
                    }
                } else if (s.stage == DuelStage.COUNTDOWN) {
                    if (s.countdownEndsAtMillis > 0 && nowMillis >= s.countdownEndsAtMillis) {
                        s.stage = DuelStage.IN_PROGRESS;
                        s.stageStartedAtMillis = nowMillis;
                        s.countdownEndsAtMillis = 0L;
                        sendMessageToBoth(s, Message.raw("[DuelArena] Fight!"));
                    }
                }

                if (s.stage == DuelStage.ENDED || s.stage == DuelStage.CANCELED) {
                    if ((nowMillis - s.stageStartedAtMillis) > CLEANUP_AFTER_MILLIS) {
                        toRemoveIds.add(s.id);
                    }
                }
            }

            for (String id : toRemoveIds) {
                DuelSession s = sessionsById.remove(id);
                if (s == null) continue;
                sessionIdByPlayerUuid.remove(s.playerAUuid);
                sessionIdByPlayerUuid.remove(s.playerBUuid);
                ended.add(s);
            }
        }

        // Cancel side effects (outside lock).
        for (int i = 0; i < canceled.size(); i++) {
            DuelSession s = canceled.get(i);
            DuelCancelReason r = canceledReasons.get(i);
            onCanceledSideEffects(s, r);
        }
    }

    public void cancel(String sessionId, DuelCancelReason reason) {
        if (sessionId == null || sessionId.isBlank()) return;
        DuelSession session;
        synchronized (lock) {
            session = sessionsById.get(sessionId);
            if (session == null) return;
            if (session.stage == DuelStage.ENDED || session.stage == DuelStage.CANCELED) return;
            session.stage = DuelStage.CANCELED;
            session.stageStartedAtMillis = System.currentTimeMillis();
            session.cancelReason = reason;
        }
        onCanceledSideEffects(session, reason);
    }

    private void onCanceledSideEffects(DuelSession session, DuelCancelReason reason) {
        onCanceledSideEffects(session, reason, null);
    }

    private void onCanceledSideEffects(DuelSession session, DuelCancelReason reason, Message customCancelMessage) {
        if (session == null) return;

        // Return escrow if it was taken.
        if (session.escrowed && session.stakingEnabled) {
            returnEscrow(session);
        }

        // Release arena if this session reserved it.
        if (arenaService != null && session.arena != null && session.arena.id != null && !session.arena.id.isBlank()) {
            if (session.id.equals(session.arenaReservationKey)) {
                arenaService.releaseArena(session.arena.id, session.arenaReservationKey);
            }
        }

        // Notify listeners.
        for (DuelServiceListener l : listeners) {
            try {
                l.onDuelCanceled(session, reason);
            } catch (Throwable ignored) {
            }
        }

        Message message = (customCancelMessage == null) ? cancelMessageForReason(reason) : customCancelMessage;
        if (message != null) {
            sendMessageToBoth(session, message);
        }
    }

    private void finishByUuids(String winnerUuid, String loserUuid, DuelEndReason reason) {
        DuelSession session = getSessionForPlayer(winnerUuid);
        if (session == null) session = getSessionForPlayer(loserUuid);
        if (session == null) return;
        if (!session.isActiveCombat()) return;
        if (!session.involves(winnerUuid) || !session.involves(loserUuid)) return;

        boolean finalized = false;
        synchronized (lock) {
            DuelSession cur = sessionsById.get(session.id);
            if (cur == null) return;
            if (cur.stage == DuelStage.ENDED || cur.stage == DuelStage.CANCELED) return;

            cur.stage = DuelStage.ENDED;
            cur.stageStartedAtMillis = System.currentTimeMillis();
            cur.endReason = reason;
            cur.winnerUuid = winnerUuid;
            cur.loserUuid = loserUuid;
            finalized = true;
        }

        DuelResult res = finalized ? finalizeRewardsAndStats(session) : null;

        // Release arena if this session reserved it.
        if (arenaService != null && session.arena != null && session.arena.id != null && !session.arena.id.isBlank()) {
            if (session.id.equals(session.arenaReservationKey)) {
                arenaService.releaseArena(session.arena.id, session.arenaReservationKey);
            }
        }

        // Teleport both to spectator spawn after the duel.
        if (teleportService != null && session.arena != null) {
            teleportService.queueTeleport(session.playerAUuid, session.arena.world, session.arena.spectatorSpawn.position().getX(), session.arena.spectatorSpawn.position().getY(), session.arena.spectatorSpawn.position().getZ());
            teleportService.queueTeleport(session.playerBUuid, session.arena.world, session.arena.spectatorSpawn.position().getX(), session.arena.spectatorSpawn.position().getY(), session.arena.spectatorSpawn.position().getZ());
        }

        if (res != null) {
            for (DuelServiceListener l : listeners) {
                try {
                    l.onDuelEnded(session, res);
                } catch (Throwable ignored) {
                }
            }
        }

        sendMessageToBoth(session, Message.raw("[DuelArena] Winner: " + shortUuid(winnerUuid) + " (" + reason + ")"));
    }

    private DuelResult finalizeRewardsAndStats(DuelSession session) {
        if (session == null) return null;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        int pointsPerWin = (cfg == null || cfg.duel == null) ? 0 : Math.max(0, cfg.duel.pointsPerWin);
        boolean eloEnabled = cfg != null && cfg.elo != null && cfg.elo.enabled;

        String winner = session.winnerUuid;
        String loser = session.loserUuid;
        boolean ranked = session.rules != null && session.rules.ranked;

        // Base points for any win (ranked or unranked).
        if (pointsRepository != null && winner != null && !winner.isBlank() && pointsPerWin > 0) {
            pointsRepository.addPoints(winner, pointsPerWin);
        }

        // Elo for ranked matches only.
        DuelEloRepository.EloDelta eloDelta = null;
        try {
            if (eloEnabled && ranked && eloRepository != null) {
                eloDelta = eloRepository.recordRankedMatch(winner, loser);
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[DuelArena] Failed to record Elo match.");
            eloDelta = null;
        }

        // Staking payouts.
        int stakePointsTotal = 0;
        int stakeItemStacksTotal = 0;
        if (session.stakingEnabled && session.escrowed) {
            stakePointsTotal = Math.max(0, session.escrowPointsA) + Math.max(0, session.escrowPointsB);
            stakeItemStacksTotal = (session.escrowItemsA == null ? 0 : session.escrowItemsA.size()) + (session.escrowItemsB == null ? 0 : session.escrowItemsB.size());
            deliverEscrowToWinner(session);
        }

        // History log.
        if (historyLogger != null) {
            historyLogger.logEnd(session, session.endReason, ranked, winner, loser, eloDelta, pointsPerWin, stakePointsTotal, stakeItemStacksTotal);
        }

        return new DuelResult(winner, loser, session.endReason, ranked, eloDelta);
    }

    private void deliverEscrowToWinner(DuelSession session) {
        if (session == null) return;
        if (!session.escrowed) return;

        String winner = session.winnerUuid;
        if (winner == null || winner.isBlank()) return;

        int points = Math.max(0, session.escrowPointsA) + Math.max(0, session.escrowPointsB);
        if (pointsRepository != null && points > 0) {
            pointsRepository.addPoints(winner, points);
        }

        List<ItemStack> all = new ArrayList<>();
        if (session.escrowItemsA != null) all.addAll(session.escrowItemsA);
        if (session.escrowItemsB != null) all.addAll(session.escrowItemsB);

        Player winnerPlayer = findOnlinePlayer(winner);
        if (winnerPlayer == null) {
            if (pendingDeliveriesRepository != null) {
                for (ItemStack st : all) {
                    if (st == null || st.isEmpty() || st.getQuantity() <= 0) continue;
                    pendingDeliveriesRepository.add(winner, st);
                }
            }
            return;
        }

        int delivered = 0;
        for (ItemStack st : all) {
            if (st == null || st.isEmpty() || st.getQuantity() <= 0) continue;
            boolean ok = InventoryUtil.tryGive(winnerPlayer, st);
            if (ok) {
                delivered++;
            } else if (pendingDeliveriesRepository != null) {
                pendingDeliveriesRepository.add(winner, st);
            }
        }

        if (delivered > 0) {
            try {
                winnerPlayer.sendInventory();
            } catch (Throwable ignored) {
            }
        }
    }

    private void returnEscrow(DuelSession session) {
        if (session == null) return;
        if (!session.escrowed) return;

        if (pointsRepository != null) {
            if (session.escrowPointsA > 0) pointsRepository.addPoints(session.playerAUuid, session.escrowPointsA);
            if (session.escrowPointsB > 0) pointsRepository.addPoints(session.playerBUuid, session.escrowPointsB);
        }

        returnEscrowItemsTo(session.playerAUuid, session.escrowItemsA);
        returnEscrowItemsTo(session.playerBUuid, session.escrowItemsB);
    }

    private void returnEscrowItemsTo(String uuid, List<ItemStack> items) {
        if (uuid == null || uuid.isBlank()) return;
        if (items == null || items.isEmpty()) return;

        Player player = findOnlinePlayer(uuid);
        if (player == null) {
            if (pendingDeliveriesRepository != null) {
                for (ItemStack st : items) {
                    if (st == null || st.isEmpty() || st.getQuantity() <= 0) continue;
                    pendingDeliveriesRepository.add(uuid, st);
                }
            }
            return;
        }

        int delivered = 0;
        for (ItemStack st : items) {
            if (st == null || st.isEmpty() || st.getQuantity() <= 0) continue;
            boolean ok = InventoryUtil.tryGive(player, st);
            if (ok) {
                delivered++;
            } else if (pendingDeliveriesRepository != null) {
                pendingDeliveriesRepository.add(uuid, st);
            }
        }

        if (delivered > 0) {
            try {
                player.sendInventory();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean escrowStakes(DuelSession session) {
        if (session == null) return false;
        if (!session.stakingEnabled) return true;
        if (session.escrowed) return true;

        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        if (cfg == null || cfg.staking == null || !cfg.staking.enabled) return true;

        List<String> scope = cfg.staking.stakeItemScope;

        // Points
        int aPoints = Math.max(0, session.stakeA == null ? 0 : session.stakeA.points);
        int bPoints = Math.max(0, session.stakeB == null ? 0 : session.stakeB.points);

        if (pointsRepository != null) {
            if (aPoints > 0 && !pointsRepository.spendPoints(session.playerAUuid, aPoints)) return false;
            if (bPoints > 0 && !pointsRepository.spendPoints(session.playerBUuid, bPoints)) {
                if (aPoints > 0) pointsRepository.addPoints(session.playerAUuid, aPoints);
                return false;
            }
        } else {
            if (aPoints > 0 || bPoints > 0) return false;
        }

        Player aPlayer = findOnlinePlayer(session.playerAUuid);
        Player bPlayer = findOnlinePlayer(session.playerBUuid);
        if (aPlayer == null || bPlayer == null) {
            // Refund points.
            if (pointsRepository != null) {
                if (aPoints > 0) pointsRepository.addPoints(session.playerAUuid, aPoints);
                if (bPoints > 0) pointsRepository.addPoints(session.playerBUuid, bPoints);
            }
            return false;
        }

        List<ItemStack> aEscrow = new ArrayList<>();
        List<ItemStack> bEscrow = new ArrayList<>();

        if (session.stakeA != null && session.stakeA.items != null) {
            for (SerializedItemStack it : session.stakeA.items) {
                if (it == null || it.quantity <= 0) continue;
                ItemStack target = it.toItemStack();
                if (target == null || target.isEmpty()) continue;
                List<ItemStack> removed = InventoryUtil.removeEquivalentFromScopes(aPlayer, target, it.quantity, scope);
                if (removed == null) {
                    // Refund + rollback.
                    rollbackEscrow(session.playerAUuid, aEscrow);
                    rollbackEscrow(session.playerBUuid, bEscrow);
                    if (pointsRepository != null) {
                        if (aPoints > 0) pointsRepository.addPoints(session.playerAUuid, aPoints);
                        if (bPoints > 0) pointsRepository.addPoints(session.playerBUuid, bPoints);
                    }
                    return false;
                }
                aEscrow.addAll(removed);
            }
        }

        if (session.stakeB != null && session.stakeB.items != null) {
            for (SerializedItemStack it : session.stakeB.items) {
                if (it == null || it.quantity <= 0) continue;
                ItemStack target = it.toItemStack();
                if (target == null || target.isEmpty()) continue;
                List<ItemStack> removed = InventoryUtil.removeEquivalentFromScopes(bPlayer, target, it.quantity, scope);
                if (removed == null) {
                    rollbackEscrow(session.playerAUuid, aEscrow);
                    rollbackEscrow(session.playerBUuid, bEscrow);
                    if (pointsRepository != null) {
                        if (aPoints > 0) pointsRepository.addPoints(session.playerAUuid, aPoints);
                        if (bPoints > 0) pointsRepository.addPoints(session.playerBUuid, bPoints);
                    }
                    return false;
                }
                bEscrow.addAll(removed);
            }
        }

        try {
            aPlayer.sendInventory();
        } catch (Throwable ignored) {
        }
        try {
            bPlayer.sendInventory();
        } catch (Throwable ignored) {
        }

        session.escrowed = true;
        session.escrowPointsA = aPoints;
        session.escrowPointsB = bPoints;
        session.escrowItemsA = aEscrow;
        session.escrowItemsB = bEscrow;

        return true;
    }

    private void rollbackEscrow(String uuid, List<ItemStack> escrow) {
        if (uuid == null || uuid.isBlank()) return;
        if (escrow == null || escrow.isEmpty()) return;
        Player player = findOnlinePlayer(uuid);
        if (player == null) {
            if (pendingDeliveriesRepository != null) {
                for (ItemStack st : escrow) {
                    if (st == null || st.isEmpty() || st.getQuantity() <= 0) continue;
                    pendingDeliveriesRepository.add(uuid, st);
                }
            }
            return;
        }
        for (ItemStack st : escrow) {
            if (st == null || st.isEmpty() || st.getQuantity() <= 0) continue;
            boolean ok = InventoryUtil.tryGive(player, st);
            if (!ok && pendingDeliveriesRepository != null) {
                pendingDeliveriesRepository.add(uuid, st);
            }
        }
        try {
            player.sendInventory();
        } catch (Throwable ignored) {
        }
    }

    private boolean ensureArenaAllocated(DuelSession session) {
        if (session == null) return false;
        if (session.arena != null) return true;
        if (arenaService == null) return false;

        Arena a = arenaService.allocateAnyFreeArena(session.id);
        if (a == null) return false;
        if (!a.isValidForUse()) {
            arenaService.releaseArena(a.id, session.id);
            return false;
        }

        session.arena = a;
        session.arenaReservationKey = session.id;
        return true;
    }

    private boolean ensurePlayersOnline(DuelSession session) {
        if (session == null) return false;
        return findOnlinePlayer(session.playerAUuid) != null && findOnlinePlayer(session.playerBUuid) != null;
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

    private void sendMessageToBoth(DuelSession session, Message msg) {
        if (session == null || msg == null) return;
        sendMessage(session.playerAUuid, msg);
        sendMessage(session.playerBUuid, msg);
    }

    private void sendMessage(String uuid, Message msg) {
        if (uuid == null || uuid.isBlank()) return;
        try {
            Player p = findOnlinePlayer(uuid);
            if (p != null) {
                p.sendMessage(msg);
            }
        } catch (Throwable ignored) {
        }
    }

    private void resetOfferAcceptance(DuelSession session) {
        if (session == null) return;
        session.offerAcceptedA = false;
        session.offerAcceptedB = false;
    }

    private static void mergeStakeItem(DuelStake stake, SerializedItemStack add, int maxEntries) {
        if (stake == null || add == null) return;
        if (stake.items == null) stake.items = new ArrayList<>();

        for (SerializedItemStack existing : stake.items) {
            if (!isSameItem(existing, add)) continue;
            long next = (long) existing.quantity + (long) add.quantity;
            existing.quantity = (next > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) next;
            return;
        }

        if (maxEntries > 0 && stake.items.size() >= maxEntries) return;
        stake.items.add(add);
    }

    private static boolean isSameItem(SerializedItemStack a, SerializedItemStack b) {
        if (a == null || b == null) return false;
        if (a.itemId == null || b.itemId == null) return false;
        if (!a.itemId.equals(b.itemId)) return false;
        String am = (a.metadataJson == null) ? "" : a.metadataJson;
        String bm = (b.metadataJson == null) ? "" : b.metadataJson;
        if (!am.equals(bm)) return false;
        if (Double.compare(a.durability, b.durability) != 0) return false;
        if (Double.compare(a.maxDurability, b.maxDurability) != 0) return false;
        return true;
    }

    private String newSessionId() {
        long n = idCounter.getAndIncrement();
        return "D" + n;
    }

    private static String shortUuid(String uuid) {
        if (uuid == null) return "";
        if (uuid.length() <= 8) return uuid;
        return uuid.substring(0, 8);
    }

    private static Message cancelMessageForReason(DuelCancelReason reason) {
        if (reason == DuelCancelReason.NO_ARENA_AVAILABLE) {
            return Message.raw("[DuelArena] No duel arena is currently free. Try again shortly.");
        }
        if (reason == DuelCancelReason.PLAYER_OFFLINE) {
            return Message.raw("[DuelArena] Duel cancelled because one participant is no longer online or in-world.");
        }
        return Message.raw("[DuelArena] Duel cancelled (" + reason + ").");
    }

    private static String arenaAvailabilitySummary(ArenaService.ArenaAvailability availability) {
        if (availability == null) return "unavailable";
        return "total=" + availability.total()
                + ", valid=" + availability.valid()
                + ", reserved=" + availability.reserved()
                + ", free=" + availability.free();
    }

    private static String safe(String value) {
        return (value == null) ? "" : value;
    }
}
