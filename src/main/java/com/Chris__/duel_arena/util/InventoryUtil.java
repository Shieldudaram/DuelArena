package com.Chris__.duel_arena.util;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class InventoryUtil {

    private InventoryUtil() {
    }

    /**
     * Tries to add the full stack to the player's non-armor inventory.
     * <p>
     * All-or-nothing: if the full quantity can't fit, no changes are applied.
     */
    public static boolean tryGive(Player player, ItemStack stack) {
        if (player == null) return false;
        if (stack == null || stack.isEmpty() || stack.getQuantity() <= 0) return false;

        Inventory inv = player.getInventory();
        if (inv == null) return false;

        Item item = null;
        try {
            item = stack.getItem();
        } catch (Throwable ignored) {
        }
        if (item == null) return false;

        int maxStack = Math.max(1, item.getMaxStack());
        int qty = stack.getQuantity();

        List<ItemContainer> containers = nonArmorContainers(inv);
        if (containers.isEmpty()) return false;

        // Preflight: compute total available space for this stack type.
        int available = 0;
        for (ItemContainer c : containers) {
            if (c == null) continue;
            short cap = c.getCapacity();
            for (short s = 0; s < cap; s++) {
                ItemStack cur = c.getItemStack(s);
                if (cur == null || cur.isEmpty() || cur.getQuantity() <= 0) {
                    available += maxStack;
                    continue;
                }
                if (!cur.isStackableWith(stack)) continue;
                int space = maxStack - cur.getQuantity();
                if (space > 0) available += space;
            }
        }

        if (available < qty) return false;

        int remaining = qty;

        // 1) Fill existing stackable slots first.
        for (ItemContainer c : containers) {
            if (c == null) continue;
            short cap = c.getCapacity();
            for (short s = 0; s < cap; s++) {
                if (remaining <= 0) break;
                ItemStack cur = c.getItemStack(s);
                if (cur == null || cur.isEmpty() || cur.getQuantity() <= 0) continue;
                if (!cur.isStackableWith(stack)) continue;

                int space = maxStack - cur.getQuantity();
                if (space <= 0) continue;

                int toAdd = Math.min(space, remaining);
                if (toAdd <= 0) continue;

                c.setItemStackForSlot(s, cur.withQuantity(cur.getQuantity() + toAdd));
                remaining -= toAdd;
            }
            if (remaining <= 0) break;
        }

        // 2) Use empty slots, splitting into stacks if needed.
        for (ItemContainer c : containers) {
            if (c == null) continue;
            short cap = c.getCapacity();
            for (short s = 0; s < cap; s++) {
                if (remaining <= 0) break;
                ItemStack cur = c.getItemStack(s);
                if (cur != null && !cur.isEmpty() && cur.getQuantity() > 0) continue;

                int toAdd = Math.min(maxStack, remaining);
                c.setItemStackForSlot(s, stack.withQuantity(toAdd));
                remaining -= toAdd;
            }
            if (remaining <= 0) break;
        }

        return remaining <= 0;
    }

    public static int countEquivalentInScopes(Player player, ItemStack target, List<String> scopes) {
        if (player == null) return 0;
        if (target == null || target.isEmpty()) return 0;
        Inventory inv = player.getInventory();
        if (inv == null) return 0;

        int total = 0;
        for (ItemContainer c : containersForScopes(inv, scopes)) {
            if (c == null) continue;
            short cap = c.getCapacity();
            for (short s = 0; s < cap; s++) {
                ItemStack cur = c.getItemStack(s);
                if (cur == null || cur.isEmpty() || cur.getQuantity() <= 0) continue;
                if (!cur.isEquivalentType(target)) continue;
                total += cur.getQuantity();
            }
        }
        return total;
    }

    /**
     * Removes a quantity of items equivalent to {@code target} from the given inventory scopes.
     * All-or-nothing: if insufficient quantity exists, this returns null and applies no changes.
     *
     * @return list of removed stacks (split as needed), or null on failure.
     */
    public static List<ItemStack> removeEquivalentFromScopes(Player player, ItemStack target, int quantity, List<String> scopes) {
        if (player == null) return null;
        if (target == null || target.isEmpty()) return null;
        int needed = Math.max(0, quantity);
        if (needed <= 0) return new ArrayList<>();

        Inventory inv = player.getInventory();
        if (inv == null) return null;

        List<ItemContainer> containers = containersForScopes(inv, scopes);
        if (containers.isEmpty()) return null;

        int available = 0;
        for (ItemContainer c : containers) {
            if (c == null) continue;
            short cap = c.getCapacity();
            for (short s = 0; s < cap; s++) {
                ItemStack cur = c.getItemStack(s);
                if (cur == null || cur.isEmpty() || cur.getQuantity() <= 0) continue;
                if (!cur.isEquivalentType(target)) continue;
                available += cur.getQuantity();
                if (available >= needed) break;
            }
            if (available >= needed) break;
        }

        if (available < needed) return null;

        List<ItemStack> removed = new ArrayList<>();
        int remaining = needed;

        for (ItemContainer c : containers) {
            if (c == null) continue;
            short cap = c.getCapacity();
            for (short s = 0; s < cap; s++) {
                if (remaining <= 0) break;
                ItemStack cur = c.getItemStack(s);
                if (cur == null || cur.isEmpty() || cur.getQuantity() <= 0) continue;
                if (!cur.isEquivalentType(target)) continue;

                int take = Math.min(remaining, cur.getQuantity());
                if (take <= 0) continue;

                removed.add(cur.withQuantity(take));

                int left = cur.getQuantity() - take;
                if (left <= 0) {
                    c.setItemStackForSlot(s, ItemStack.EMPTY);
                } else {
                    c.setItemStackForSlot(s, cur.withQuantity(left));
                }

                remaining -= take;
            }
            if (remaining <= 0) break;
        }

        return removed;
    }

    public static List<ItemContainer> containersForScopes(Inventory inv, List<String> scopes) {
        if (inv == null) return List.of();
        if (scopes == null || scopes.isEmpty()) return nonArmorContainers(inv);

        Set<String> set = new HashSet<>();
        for (String s : scopes) {
            if (s == null) continue;
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty()) set.add(k);
        }

        if (set.contains("all")) {
            return allContainers(inv);
        }

        if (set.contains("non_armor") || set.contains("nonarmor") || set.contains("non-armour") || set.contains("non_armour")) {
            return nonArmorContainers(inv);
        }

        List<ItemContainer> out = new ArrayList<>();
        if (set.contains("storage")) out.add(inv.getStorage());
        if (set.contains("backpack")) out.add(inv.getBackpack());
        if (set.contains("hotbar")) out.add(inv.getHotbar());
        if (set.contains("tools")) out.add(inv.getTools());
        if (set.contains("utility")) out.add(inv.getUtility());
        if (set.contains("armor") || set.contains("armour")) out.add(inv.getArmor());
        return out;
    }

    private static List<ItemContainer> nonArmorContainers(Inventory inv) {
        if (inv == null) return List.of();
        return List.of(inv.getStorage(), inv.getBackpack(), inv.getHotbar(), inv.getTools(), inv.getUtility());
    }

    private static List<ItemContainer> allContainers(Inventory inv) {
        if (inv == null) return List.of();
        return List.of(inv.getStorage(), inv.getBackpack(), inv.getHotbar(), inv.getTools(), inv.getUtility(), inv.getArmor());
    }
}

