package com.Chris__.duel_arena.ui;

import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.duel.DuelSession;
import com.Chris__.duel_arena.duel.DuelStage;
import com.Chris__.duel_arena.tourney.TournamentService;
import com.Chris__.duel_arena.ui.pages.DuelConfirmPage;
import com.Chris__.duel_arena.ui.pages.DuelOfferPage;
import com.Chris__.duel_arena.ui.pages.TournamentBracketPage;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DuelUiService {

    private final DuelService duelService;
    private final TournamentService tournamentService;
    private final ConfigRepository configRepository;

    private final Set<String> pendingRefreshByUuid = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingBracketByUuid = ConcurrentHashMap.newKeySet();

    public DuelUiService(DuelService duelService,
                         TournamentService tournamentService,
                         ConfigRepository configRepository) {
        this.duelService = duelService;
        this.tournamentService = tournamentService;
        this.configRepository = configRepository;
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

            if (pendingBracketByUuid.remove(uuid)) {
                player.getPageManager().openCustomPage(chunk.getReferenceTo(entityId), store,
                        new TournamentBracketPage(playerRef, tournamentService, this::onBracketRefresh));
                return;
            }

            if (!pendingRefreshByUuid.remove(uuid)) return;
            if (duelService == null) return;

            DuelSession s = duelService.getSessionForPlayer(uuid);
            if (s == null) return;

            if (s.stage == DuelStage.OFFER) {
                player.getPageManager().openCustomPage(chunk.getReferenceTo(entityId), store,
                        new DuelOfferPage(playerRef, duelService, this::onOfferOrConfirmChange, configRepository));
            } else if (s.stage == DuelStage.CONFIRM) {
                player.getPageManager().openCustomPage(chunk.getReferenceTo(entityId), store,
                        new DuelConfirmPage(playerRef, duelService, this::onOfferOrConfirmChange, configRepository));
            }
        }

        private void onOfferOrConfirmChange(DuelSession s) {
            requestRefreshForSession(s);
        }

        private void onBracketRefresh(String uuid) {
            requestBracket(uuid);
        }
    }
}

