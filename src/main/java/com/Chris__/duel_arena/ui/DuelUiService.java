package com.Chris__.duel_arena.ui;

import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.duel.DuelSession;
import com.Chris__.duel_arena.duel.DuelStage;
import com.Chris__.duel_arena.integration.MultipleHudBridge;
import com.Chris__.duel_arena.tourney.TournamentService;
import com.Chris__.duel_arena.tourney.TournamentState;
import com.Chris__.duel_arena.ui.hud.DuelStatusHud;
import com.Chris__.duel_arena.ui.hud.TournamentStatusHud;
import com.Chris__.duel_arena.ui.pages.DuelConfirmPage;
import com.Chris__.duel_arena.ui.pages.DuelOfferPage;
import com.Chris__.duel_arena.ui.pages.TournamentBracketPage;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DuelUiService {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String UI_ERROR_MESSAGE = "[DuelArena] Failed to open duel UI; ask staff to check server logs.";
    private static final long UI_ERROR_MESSAGE_THROTTLE_MILLIS = 5_000L;

    private final DuelService duelService;
    private final TournamentService tournamentService;
    private final ConfigRepository configRepository;
    private final MultipleHudBridge multipleHudBridge;

    private final Set<String> pendingRefreshByUuid = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingBracketByUuid = ConcurrentHashMap.newKeySet();
    private final Map<String, DuelStatusHud> duelHudByUuid = new ConcurrentHashMap<>();
    private final Map<String, TournamentStatusHud> tournamentHudByUuid = new ConcurrentHashMap<>();
    private final Map<String, String> duelHudSignatureByUuid = new ConcurrentHashMap<>();
    private final Map<String, String> tournamentHudSignatureByUuid = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUiErrorMessageAtByUuid = new ConcurrentHashMap<>();

    public DuelUiService(DuelService duelService,
                         TournamentService tournamentService,
                         ConfigRepository configRepository,
                         MultipleHudBridge multipleHudBridge) {
        this.duelService = duelService;
        this.tournamentService = tournamentService;
        this.configRepository = configRepository;
        this.multipleHudBridge = multipleHudBridge;
    }

    public void requestRefresh(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        pendingRefreshByUuid.add(uuid);
    }

    public void requestRefreshForSession(DuelSession s) {
        if (s == null) return;
        requestRefresh(s.playerAUuid);
        requestRefresh(s.playerBUuid);
    }

    public void requestBracket(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        pendingBracketByUuid.add(uuid);
    }

    public EntityTickingSystem<EntityStore> createSystem() {
        return new DuelUiTickSystem();
    }

    private final class DuelUiTickSystem extends EntityTickingSystem<EntityStore> {
        private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void tick(float dt,
                         int entityId,
                         ArchetypeChunk<EntityStore> chunk,
                         Store<EntityStore> store,
                         CommandBuffer<EntityStore> commandBuffer) {
            Holder<EntityStore> holder;
            try {
                holder = EntityUtils.toHolder(entityId, chunk);
            } catch (Throwable ignored) {
                return;
            }

            Player player = (Player) holder.getComponent(Player.getComponentType());
            PlayerRef playerRef = (PlayerRef) holder.getComponent(PlayerRef.getComponentType());
            if (player == null || playerRef == null || playerRef.getUuid() == null) return;

            String uuid = playerRef.getUuid().toString();
            Ref<EntityStore> ref = chunk.getReferenceTo(entityId);
            DuelSession s = (duelService == null) ? null : duelService.getSessionForPlayer(uuid);
            DuelSession sessionForClose = s;

            runUiAction(player, playerRef, uuid, "close stale duel pages", () -> maybeCloseStaleDuelPages(player, ref, store, sessionForClose));

            if (pendingBracketByUuid.remove(uuid)) {
                runUiAction(player, playerRef, uuid, "open TournamentBracketPage", () ->
                        player.getPageManager().openCustomPage(ref, store,
                                new TournamentBracketPage(playerRef, tournamentService, this::onBracketRefresh, configRepository)));
            }

            if (pendingRefreshByUuid.remove(uuid) && duelService != null) {
                s = duelService.getSessionForPlayer(uuid);
                if (s != null && s.stage == DuelStage.OFFER) {
                    runUiAction(player, playerRef, uuid, "open DuelOfferPage", () ->
                            player.getPageManager().openCustomPage(ref, store,
                                    new DuelOfferPage(playerRef, duelService, this::onOfferOrConfirmChange, configRepository)));
                } else if (s != null && s.stage == DuelStage.CONFIRM) {
                    runUiAction(player, playerRef, uuid, "open DuelConfirmPage", () ->
                            player.getPageManager().openCustomPage(ref, store,
                                    new DuelConfirmPage(playerRef, duelService, this::onOfferOrConfirmChange, configRepository)));
                }
            }

            s = (duelService == null) ? null : duelService.getSessionForPlayer(uuid);
            TournamentState t = (tournamentService == null) ? null : tournamentService.getActive();
            CustomUIPage currentPage = player.getPageManager().getCustomPage();
            DuelSession overlaySession = s;
            TournamentState overlayTournament = t;
            CustomUIPage overlayPage = currentPage;

            runUiAction(player, playerRef, uuid, "update overlays", () -> {
                updateDuelOverlay(player, playerRef, uuid, overlaySession, overlayPage);
                updateTournamentOverlay(player, playerRef, uuid, overlayTournament, overlayPage);
            });
        }

        private void onOfferOrConfirmChange(DuelSession s) {
            requestRefreshForSession(s);
        }

        private void onBracketRefresh(String uuid) {
            requestBracket(uuid);
        }

        private void runUiAction(Player player,
                                 PlayerRef playerRef,
                                 String uuid,
                                 String action,
                                 Runnable callback) {
            if (callback == null) return;
            try {
                callback.run();
            } catch (Throwable t) {
                onUiException(player, playerRef, uuid, action, t);
            }
        }

        private void maybeCloseStaleDuelPages(Player player,
                                              Ref<EntityStore> ref,
                                              Store<EntityStore> store,
                                              DuelSession session) {
            if (player == null || ref == null || store == null) return;
            if (!ref.isValid()) return;

            CustomUIPage page = player.getPageManager().getCustomPage();
            if (page == null) return;

            if (page instanceof DuelOfferPage) {
                if (session == null || session.stage != DuelStage.OFFER) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
                return;
            }

            if (page instanceof DuelConfirmPage) {
                if (session == null || session.stage != DuelStage.CONFIRM) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            }
        }

        private void updateDuelOverlay(Player player,
                                       PlayerRef playerRef,
                                       String uuid,
                                       DuelSession session,
                                       CustomUIPage currentPage) {
            if (player == null || playerRef == null) return;
            if (uuid == null || uuid.isBlank()) return;

            boolean hideForPage = currentPage instanceof DuelOfferPage || currentPage instanceof DuelConfirmPage;
            boolean visibleStage = session != null && isOverlayDuelStage(session.stage);
            if (!visibleStage || hideForPage) {
                hideDuelOverlay(player, uuid);
                return;
            }

            String title = "Duel " + session.stage;
            String opponent = resolveName(session.other(uuid));
            String line1 = "vs " + opponent + (session.tournamentMatch ? " (tourney)" : "");
            String line2 = duelStageDetail(session, uuid);
            String signature = title + "|" + line1 + "|" + line2;

            if (signature.equals(duelHudSignatureByUuid.get(uuid))) return;

            DuelStatusHud hud = getOrCreateDuelHud(uuid, playerRef);
            hud.show(title, line1, line2);
            multipleHudBridge.showDuelHud(player, playerRef, hud);
            duelHudSignatureByUuid.put(uuid, signature);
        }

        private void updateTournamentOverlay(Player player,
                                             PlayerRef playerRef,
                                             String uuid,
                                             TournamentState tournament,
                                             CustomUIPage currentPage) {
            if (player == null || playerRef == null) return;
            if (uuid == null || uuid.isBlank()) return;

            boolean hideForPage = currentPage instanceof TournamentBracketPage;
            boolean show = tournament != null && tournament.hasEntrant(uuid) && !hideForPage;
            if (!show) {
                hideTournamentOverlay(player, uuid);
                return;
            }

            String title = "Tournament " + safe(tournament.id);
            String line1 = "Status: " + tournament.status + " (" + tournament.entrants.size() + "/" + tournament.size + ")";
            String line2 = tournamentDetailLine(tournament);
            String signature = title + "|" + line1 + "|" + line2;

            if (signature.equals(tournamentHudSignatureByUuid.get(uuid))) return;

            TournamentStatusHud hud = getOrCreateTournamentHud(uuid, playerRef);
            hud.show(title, line1, line2);
            multipleHudBridge.showTournamentHud(player, playerRef, hud);
            tournamentHudSignatureByUuid.put(uuid, signature);
        }

        private DuelStatusHud getOrCreateDuelHud(String uuid, PlayerRef playerRef) {
            DuelStatusHud hud = duelHudByUuid.get(uuid);
            if (hud != null && samePlayer(hud.getPlayerRef(), playerRef)) {
                return hud;
            }
            DuelStatusHud created = new DuelStatusHud(playerRef);
            duelHudByUuid.put(uuid, created);
            duelHudSignatureByUuid.remove(uuid);
            return created;
        }

        private TournamentStatusHud getOrCreateTournamentHud(String uuid, PlayerRef playerRef) {
            TournamentStatusHud hud = tournamentHudByUuid.get(uuid);
            if (hud != null && samePlayer(hud.getPlayerRef(), playerRef)) {
                return hud;
            }
            TournamentStatusHud created = new TournamentStatusHud(playerRef);
            tournamentHudByUuid.put(uuid, created);
            tournamentHudSignatureByUuid.remove(uuid);
            return created;
        }

        private void hideDuelOverlay(Player player, String uuid) {
            boolean shouldHide = false;
            DuelStatusHud hud = duelHudByUuid.get(uuid);
            if (hud != null && hud.isVisible()) {
                hud.hide();
                shouldHide = true;
            }
            if (duelHudSignatureByUuid.remove(uuid) != null) {
                shouldHide = true;
            }
            if (shouldHide) {
                multipleHudBridge.hideDuelHud(player);
            }
        }

        private void hideTournamentOverlay(Player player, String uuid) {
            boolean shouldHide = false;
            TournamentStatusHud hud = tournamentHudByUuid.get(uuid);
            if (hud != null && hud.isVisible()) {
                hud.hide();
                shouldHide = true;
            }
            if (tournamentHudSignatureByUuid.remove(uuid) != null) {
                shouldHide = true;
            }
            if (shouldHide) {
                multipleHudBridge.hideTournamentHud(player);
            }
        }
    }

    private void onUiException(Player player, PlayerRef playerRef, String uuid, String action, Throwable t) {
        String playerName = resolvePlayerName(player, playerRef);
        LOGGER.atWarning().withCause(t).log("[DuelArena] UI action failed action=%s player=%s uuid=%s", safe(action), playerName, safe(uuid));

        if (player == null || uuid == null || uuid.isBlank()) return;
        long now = System.currentTimeMillis();
        Long lastSent = lastUiErrorMessageAtByUuid.get(uuid);
        if (lastSent != null && (now - lastSent) < UI_ERROR_MESSAGE_THROTTLE_MILLIS) {
            return;
        }
        lastUiErrorMessageAtByUuid.put(uuid, now);
        try {
            player.sendMessage(Message.raw(UI_ERROR_MESSAGE));
        } catch (Throwable ignored) {
        }
    }

    private static String resolvePlayerName(Player player, PlayerRef playerRef) {
        try {
            if (player != null) {
                String displayName = player.getDisplayName();
                if (displayName != null && !displayName.isBlank()) {
                    return displayName;
                }
            }
        } catch (Throwable ignored) {
        }
        if (playerRef != null) {
            String username = playerRef.getUsername();
            if (username != null && !username.isBlank()) {
                return username;
            }
            if (playerRef.getUuid() != null) {
                String uuid = playerRef.getUuid().toString();
                if (uuid.length() <= 8) return uuid;
                return uuid.substring(0, 8);
            }
        }
        return "<unknown>";
    }

    private static boolean isOverlayDuelStage(DuelStage stage) {
        return stage == DuelStage.OFFER
                || stage == DuelStage.CONFIRM
                || stage == DuelStage.COUNTDOWN
                || stage == DuelStage.IN_PROGRESS;
    }

    private static String duelStageDetail(DuelSession s, String viewerUuid) {
        if (s == null) return "";
        if (s.stage == DuelStage.OFFER) {
            boolean viewerIsA = viewerUuid != null && viewerUuid.equals(s.playerAUuid);
            boolean you = viewerIsA ? s.offerAcceptedA : s.offerAcceptedB;
            boolean opp = viewerIsA ? s.offerAcceptedB : s.offerAcceptedA;
            return "Offer accepted: you " + yn(you) + " | opp " + yn(opp);
        }
        if (s.stage == DuelStage.CONFIRM) {
            boolean viewerIsA = viewerUuid != null && viewerUuid.equals(s.playerAUuid);
            boolean you = viewerIsA ? s.confirmAcceptedA : s.confirmAcceptedB;
            boolean opp = viewerIsA ? s.confirmAcceptedB : s.confirmAcceptedA;
            return "Confirm accepted: you " + yn(you) + " | opp " + yn(opp);
        }
        if (s.stage == DuelStage.COUNTDOWN) {
            long now = System.currentTimeMillis();
            if (s.countdownEndsAtMillis <= 0L) return "Starting...";
            long remain = s.countdownEndsAtMillis - now;
            long seconds = Math.max(0L, (remain + 999L) / 1000L);
            return "Starts in " + seconds + "s";
        }
        if (s.stage == DuelStage.IN_PROGRESS) {
            return "Fight in progress";
        }
        return "";
    }

    private static String tournamentDetailLine(TournamentState t) {
        if (t == null || t.status == null) return "";
        return switch (t.status) {
            case LOBBY -> "Waiting for players";
            case RUNNING -> runningTournamentLine(t);
            case COMPLETED -> "Winner: " + resolveName(t.championUuid);
            case CANCELED -> "Tournament canceled";
        };
    }

    private static String runningTournamentLine(TournamentState t) {
        TournamentState.Match m = currentMatch(t);
        if (m == null) {
            return "Round " + Math.max(1, t.currentRoundNumber) + " in progress";
        }
        return "R" + Math.max(1, m.roundNumber) + " M" + Math.max(1, m.matchNumber) +
                ": " + shortName(m.aUuid) + " vs " + shortName(m.bUuid);
    }

    private static TournamentState.Match currentMatch(TournamentState t) {
        if (t == null || t.rounds == null || t.rounds.isEmpty()) return null;

        TournamentState.Round round = null;
        if (t.currentRoundNumber > 0) {
            for (TournamentState.Round r : t.rounds) {
                if (r == null) continue;
                if (r.roundNumber == t.currentRoundNumber) {
                    round = r;
                    break;
                }
            }
        }
        if (round == null) {
            round = t.rounds.get(t.rounds.size() - 1);
        }
        if (round == null || round.matches == null || round.matches.isEmpty()) return null;
        if (t.currentMatchIndex < 0 || t.currentMatchIndex >= round.matches.size()) return null;
        return round.matches.get(t.currentMatchIndex);
    }

    private static boolean samePlayer(PlayerRef a, PlayerRef b) {
        if (a == null || b == null || a.getUuid() == null || b.getUuid() == null) return false;
        return a.getUuid().equals(b.getUuid());
    }

    private static String shortName(String uuid) {
        if (uuid == null) return "";
        String name = resolveName(uuid);
        if (name == null || name.isBlank()) return "";
        return name;
    }

    private static String resolveName(String uuid) {
        if (uuid == null || uuid.isBlank()) return "<unknown>";
        try {
            PlayerRef pr = Universe.get().getPlayer(UUID.fromString(uuid));
            if (pr != null) {
                String u = pr.getUsername();
                if (u != null && !u.isBlank()) return u;
            }
        } catch (Throwable ignored) {
        }
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
    }

    private static String yn(boolean b) {
        return b ? "ON" : "OFF";
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }
}
