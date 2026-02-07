package com.Chris__.duel_arena.systems;

import com.Chris__.duel_arena.arena.ArenaService;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.duel.DuelSession;
import com.Chris__.duel_arena.duel.DuelStage;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;

public final class DuelArenaDamageSystem extends DamageEventSystem {

    private final DuelService duelService;
    private final ArenaService arenaService;

    public DuelArenaDamageSystem(DuelService duelService, ArenaService arenaService) {
        this.duelService = duelService;
        this.arenaService = arenaService;
    }

    @Override
    public void handle(int index,
                       @NonNullDecl ArchetypeChunk<EntityStore> archetypeChunk,
                       @NonNullDecl Store<EntityStore> store,
                       @NonNullDecl CommandBuffer<EntityStore> commandBuffer,
                       @NonNullDecl Damage damage) {
        if (damage.isCancelled()) return;

        Ref<EntityStore> victimRef = archetypeChunk.getReferenceTo(index);
        Player victimPlayer = store.getComponent(victimRef, Player.getComponentType());
        PlayerRef victimPlayerRef = store.getComponent(victimRef, PlayerRef.getComponentType());
        if (victimPlayer == null || victimPlayerRef == null || victimPlayerRef.getUuid() == null) return;

        String victimUuid = victimPlayerRef.getUuid().toString();
        String worldName = victimPlayer.getWorld().getName();

        ArenaService.Zone zone = ArenaService.Zone.NONE;
        if (arenaService != null) {
            try {
                TransformComponent tc = store.getComponent(victimRef, TransformComponent.getComponentType());
                Vector3d pos = (tc == null) ? null : tc.getPosition();
                if (pos != null) {
                    int bx = (int) Math.floor(pos.getX());
                    int by = (int) Math.floor(pos.getY());
                    int bz = (int) Math.floor(pos.getZ());
                    zone = arenaService.zoneAt(worldName, bx, by, bz);
                }
            } catch (Throwable ignored) {
                zone = ArenaService.Zone.NONE;
            }
        }

        // Spectator zones are always safe.
        if (zone == ArenaService.Zone.SPECTATOR) {
            damage.setCancelled(true);
            return;
        }

        boolean projectile = damage.getSource() instanceof Damage.ProjectileSource;
        boolean isPlayerSource = false;
        String attackerUuid = null;

        if (damage.getSource() instanceof Damage.EntitySource src) {
            Ref<EntityStore> attackerRef = src.getRef();
            if (attackerRef != null && attackerRef.isValid()) {
                Player attackerPlayer = (Player) commandBuffer.getComponent(attackerRef, Player.getComponentType());
                isPlayerSource = attackerPlayer != null;
                PlayerRef attackerPr = (PlayerRef) commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
                if (attackerPr != null && attackerPr.getUuid() != null) {
                    attackerUuid = attackerPr.getUuid().toString();
                }
            }
        }

        // Arena bounds are safe by default unless you're actively dueling.
        if (zone == ArenaService.Zone.ARENA && isPlayerSource && duelService != null) {
            DuelSession s = duelService.getSessionForPlayer(victimUuid);
            if (s == null || !s.isActiveCombat()) {
                damage.setCancelled(true);
                return;
            }
        }

        if (duelService == null) return;

        if (duelService.shouldCancelPlayerDamage(attackerUuid, victimUuid, projectile)) {
            damage.setCancelled(true);
            return;
        }

        // KO mode: cancel lethal damage and end the duel.
        if (!duelService.isKoModeEnabled()) return;

        DuelSession session = duelService.getSessionForPlayer(victimUuid);
        if (session == null || session.stage != DuelStage.IN_PROGRESS) return;

        float dmg = damage.getAmount();
        if (dmg <= 0f) return;

        try {
            EntityStatMap stats = store.getComponent(victimRef, EntityStatMap.getComponentType());
            if (stats == null) return;
            EntityStatValue hv = stats.get(DefaultEntityStatTypes.getHealth());
            float health = (hv == null) ? 0f : hv.get();
            if (health <= 0f) return;

            if ((health - dmg) <= 0.0001f) {
                damage.setCancelled(true);
                if (attackerUuid != null && !attackerUuid.isBlank()) {
                    duelService.queueKo(attackerUuid, victimUuid);
                }
            }
        } catch (Throwable ignored) {
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

