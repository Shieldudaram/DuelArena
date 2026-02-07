package com.Chris__.duel_arena.data;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;

public final class SerializedItemStack {

    public String itemId = "";
    public int quantity = 0;
    public double durability = 0.0d;
    public double maxDurability = 0.0d;
    public String metadataJson = "";

    public static SerializedItemStack from(ItemStack stack) {
        if (stack == null) return null;
        SerializedItemStack s = new SerializedItemStack();
        s.itemId = (stack.getItemId() == null) ? "" : stack.getItemId();
        s.quantity = Math.max(0, stack.getQuantity());
        s.durability = stack.getDurability();
        s.maxDurability = stack.getMaxDurability();
        try {
            BsonDocument md = stack.getMetadata();
            s.metadataJson = (md == null) ? "" : md.toJson();
        } catch (Throwable ignored) {
            s.metadataJson = "";
        }
        return s;
    }

    public ItemStack toItemStack() {
        if (itemId == null || itemId.isBlank()) return null;
        int q = Math.max(0, quantity);

        BsonDocument md = null;
        try {
            if (metadataJson != null && !metadataJson.isBlank()) {
                md = BsonDocument.parse(metadataJson);
            }
        } catch (Throwable ignored) {
            md = null;
        }
        if (md == null) md = new BsonDocument();

        try {
            return new ItemStack(itemId, q, durability, maxDurability, md);
        } catch (Throwable ignored) {
            try {
                return new ItemStack(itemId, q, md);
            } catch (Throwable ignored2) {
                try {
                    return new ItemStack(itemId, q);
                } catch (Throwable ignored3) {
                    return null;
                }
            }
        }
    }
}

