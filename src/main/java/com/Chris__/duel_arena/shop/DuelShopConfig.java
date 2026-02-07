package com.Chris__.duel_arena.shop;

import java.util.ArrayList;
import java.util.List;

public final class DuelShopConfig {
    public int version = 1;
    public List<ShopItem> items = new ArrayList<>();

    public static final class ShopItem {
        public String id;
        public boolean enabled;
        public String name;
        public int cost;
        public String type; // "item"
        public String itemId;
        public int amount;
    }
}

