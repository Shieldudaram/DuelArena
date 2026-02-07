package com.Chris__.duel_arena.systems;

import com.Chris__.duel_arena.arena.ArenaService;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.duel.DuelSession;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;

public final class ArenaDropItemRequestEventSystem extends EntityEventSystem<EntityStore, DropItemEvent.PlayerRequest> {

    private final ArenaService arenaService;
    private final DuelService duelService;

    public ArenaDropItemRequestEventSystem(ArenaService arenaService, DuelService duelService) {
        super(DropItemEvent.PlayerRequest.class);
        this.arenaService = arenaService;
        this.duelService = duelService;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull DropItemEvent.PlayerRequest event) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || pr == null || pr.getUuid() == null) return;

        String uuid = pr.getUuid().toString();
        DuelSession s = (duelService == null) ? null : duelService.getSessionForPlayer(uuid);
        if (s != null && s.isActiveCombat()) {
            event.setCancelled(true);
            return;
        }

        if (arenaService == null) return;
        Vector3d pos = pr.getTransform().getPosition();
        if (pos == null) return;

        ArenaService.Zone zone = arenaService.zoneAt(player.getWorld().getName(),
                (int) Math.floor(pos.getX()),
                (int) Math.floor(pos.getY()),
                (int) Math.floor(pos.getZ()));
        if (zone != ArenaService.Zone.NONE) {
            event.setCancelled(true);
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @NonNullDecl
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Collections.singleton(RootDependency.first());
    }
}

