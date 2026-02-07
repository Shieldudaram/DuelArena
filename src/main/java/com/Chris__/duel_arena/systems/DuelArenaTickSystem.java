package com.Chris__.duel_arena.systems;

import com.Chris__.duel_arena.data.PendingDeliveriesRepository;
import com.Chris__.duel_arena.duel.DuelRules;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.duel.DuelSession;
import com.Chris__.duel_arena.duel.DuelStage;
import com.Chris__.duel_arena.tourney.TournamentService;
import com.Chris__.duel_arena.util.InventoryUtil;
import com.Chris__.duel_arena.util.TeleportService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DuelArenaTickSystem extends EntityTickingSystem<EntityStore> {

    private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

    private final DuelService duelService;
    private final TournamentService tournamentService;
    private final TeleportService teleportService;
    private final PendingDeliveriesRepository pendingDeliveriesRepository;

    private final AtomicLong nextGlobalUpdateNanos = new AtomicLong(0L);
    private final AtomicLong lastGlobalUpdateNanos = new AtomicLong(0L);

    private final Map<String, Long> nextArmorEnforceMillis = new ConcurrentHashMap<>();

    public DuelArenaTickSystem(DuelService duelService,
                              TournamentService tournamentService,
                              TeleportService teleportService,
                              PendingDeliveriesRepository pendingDeliveriesRepository) {
        this.duelService = duelService;
        this.tournamentService = tournamentService;
        this.teleportService = teleportService;
        this.pendingDeliveriesRepository = pendingDeliveriesRepository;
    }

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

        // Apply one queued teleport (if any).
        if (teleportService != null) {
            TeleportService.PendingTeleport pending = teleportService.poll(uuid);
            if (pending != null) {
                scheduleTeleport(player.getWorld(), store, ref, pending.worldName(), pending.x(), pending.y(), pending.z());
            }
        }

        // Duel countdown "freeze": pin players to their spawn positions.
        if (duelService != null) {
            DuelSession s = duelService.getSessionForPlayer(uuid);
            if (s != null && s.stage == DuelStage.COUNTDOWN && s.arena != null) {
                Vector3d pos = uuid.equals(s.playerAUuid) ? s.arena.spawnA.position() : s.arena.spawnB.position();
                scheduleTeleport(player.getWorld(), store, ref, s.arena.world, pos.getX(), pos.getY(), pos.getZ());
            }

            // Armor slot enforcement (best-effort; rate-limited).
            if (s != null && s.isActiveCombat()) {
                maybeEnforceArmorSlots(uuid, player, s.rules);
            }
        }

        tickGlobalsOncePerSlice();
    }

    private void tickGlobalsOncePerSlice() {
        long now = System.nanoTime();
        long next = nextGlobalUpdateNanos.get();
        if (now < next) return;
        if (!nextGlobalUpdateNanos.compareAndSet(next, now + 50_000_000L)) return; // ~50ms

        long last = lastGlobalUpdateNanos.getAndSet(now);
        float dt = (last == 0L) ? 0f : (float) ((now - last) / 1_000_000_000.0);
        long nowMillis = System.currentTimeMillis();

        if (duelService != null) {
            try {
                duelService.tick(nowMillis);
            } catch (Throwable ignored) {
            }
        }
        if (tournamentService != null) {
            try {
                tournamentService.tick(nowMillis);
            } catch (Throwable ignored) {
            }
        }
    }

    private void scheduleTeleport(World currentWorld, Store<EntityStore> store, Ref<EntityStore> ref, String targetWorldName, double x, double y, double z) {
        if (currentWorld == null) return;
        if (store == null) return;
        if (ref == null || !ref.isValid()) return;
        if (targetWorldName == null || targetWorldName.isBlank()) return;

        World targetWorld = Universe.get().getWorld(targetWorldName);
        if (targetWorld == null) return;

        try {
            currentWorld.execute(() -> {
                try {
                    store.putComponent(ref, Teleport.getComponentType(), new Teleport(
                            targetWorld,
                            new Vector3d(x, y, z),
                            new Vector3f()
                    ));
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void maybeEnforceArmorSlots(String uuid, Player player, DuelRules rules) {
        if (uuid == null || uuid.isBlank()) return;
        if (player == null || rules == null) return;

        long now = System.currentTimeMillis();
        long next = nextArmorEnforceMillis.getOrDefault(uuid, 0L);
        if (now < next) return;
        nextArmorEnforceMillis.put(uuid, now + 1000L); // once per second

        Inventory inv = player.getInventory();
        if (inv == null) return;
        ItemContainer armor = inv.getArmor();
        if (armor == null) return;

        boolean[] allowed = rules.armorSlotAllowed;
        if (allowed == null) return;

        boolean changed = false;
        short cap = armor.getCapacity();
        int lim = Math.min((int) cap, allowed.length);
        for (short i = 0; i < lim; i++) {
            if (allowed[i]) continue;
            ItemStack cur = armor.getItemStack(i);
            if (cur == null || cur.isEmpty() || cur.getQuantity() <= 0) continue;

            boolean ok = InventoryUtil.tryGive(player, cur);
            if (!ok && pendingDeliveriesRepository != null) {
                pendingDeliveriesRepository.add(uuid, cur);
            }
            armor.setItemStackForSlot(i, ItemStack.EMPTY);
            changed = true;
        }

        if (changed) {
            try {
                player.sendInventory();
            } catch (Throwable ignored) {
            }
        }
    }
}
