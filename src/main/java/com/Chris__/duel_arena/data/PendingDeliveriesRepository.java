package com.Chris__.duel_arena.data;

import com.Chris__.duel_arena.util.InventoryUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PendingDeliveriesRepository {

    private static final String FILE_NAME = "duel_pending_deliveries.json";
    private static final Type MAP_TYPE = new TypeToken<Map<String, List<SerializedItemStack>>>() {
    }.getType();

    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HytaleLogger logger;
    private final Path filePath;

    private final Map<String, List<SerializedItemStack>> pendingByUuid = new HashMap<>();

    public PendingDeliveriesRepository(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        this.filePath = (dataDirectory == null) ? null : dataDirectory.resolve(FILE_NAME);
        load();
    }

    public void add(String uuid, ItemStack stack) {
        if (uuid == null || uuid.isBlank()) return;
        if (stack == null || stack.isEmpty() || stack.getQuantity() <= 0) return;

        SerializedItemStack s = SerializedItemStack.from(stack);
        if (s == null) return;

        synchronized (lock) {
            pendingByUuid.computeIfAbsent(uuid, k -> new ArrayList<>()).add(s);
            save();
        }
    }

    /**
     * Attempts to deliver pending items to a player. Items that can't fit remain pending.
     *
     * @return number of delivered stacks (not quantity)
     */
    public int tryDeliver(Player player) {
        if (player == null || player.getPlayerRef() == null || player.getPlayerRef().getUuid() == null) return 0;
        String uuid = player.getPlayerRef().getUuid().toString();
        if (uuid.isBlank()) return 0;

        synchronized (lock) {
            List<SerializedItemStack> list = pendingByUuid.get(uuid);
            if (list == null || list.isEmpty()) return 0;

            int delivered = 0;
            List<SerializedItemStack> remaining = new ArrayList<>();

            for (SerializedItemStack s : list) {
                if (s == null) continue;
                ItemStack stack = s.toItemStack();
                if (stack == null || stack.isEmpty() || stack.getQuantity() <= 0) continue;

                boolean ok = InventoryUtil.tryGive(player, stack);
                if (ok) {
                    delivered++;
                } else {
                    remaining.add(s);
                }
            }

            if (remaining.isEmpty()) {
                pendingByUuid.remove(uuid);
            } else {
                pendingByUuid.put(uuid, remaining);
            }

            if (delivered > 0) {
                try {
                    player.sendInventory();
                } catch (Throwable ignored) {
                }
                save();
            }

            return delivered;
        }
    }

    private void load() {
        if (filePath == null) return;
        synchronized (lock) {
            pendingByUuid.clear();
            try {
                Files.createDirectories(filePath.getParent());
                if (!Files.exists(filePath)) {
                    save();
                    return;
                }

                try (Reader r = Files.newBufferedReader(filePath)) {
                    Map<String, List<SerializedItemStack>> loaded = gson.fromJson(r, MAP_TYPE);
                    if (loaded != null) {
                        for (Map.Entry<String, List<SerializedItemStack>> e : loaded.entrySet()) {
                            if (e.getKey() == null || e.getKey().isBlank()) continue;
                            List<SerializedItemStack> v = (e.getValue() == null) ? new ArrayList<>() : e.getValue();
                            pendingByUuid.put(e.getKey(), v);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[DuelArena] Failed to load duel_pending_deliveries.json.");
            }
        }
    }

    private void save() {
        if (filePath == null) return;
        try {
            Files.createDirectories(filePath.getParent());
            try (Writer w = Files.newBufferedWriter(filePath)) {
                gson.toJson(pendingByUuid, MAP_TYPE, w);
            }
        } catch (Throwable t) {
            logger.atWarning().withCause(t).log("[DuelArena] Failed to save duel_pending_deliveries.json.");
        }
    }
}

