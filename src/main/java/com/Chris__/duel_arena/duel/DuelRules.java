package com.Chris__.duel_arena.duel;

import com.Chris__.duel_arena.config.DuelConfig;

import java.util.Arrays;

public final class DuelRules {

    public boolean ranked = true;
    public boolean allowMelee = true;
    public boolean allowProjectiles = true;
    public boolean allowConsumables = true;
    public boolean[] armorSlotAllowed = new boolean[]{true, true, true, true};

    public DuelRules copy() {
        DuelRules r = new DuelRules();
        r.ranked = ranked;
        r.allowMelee = allowMelee;
        r.allowProjectiles = allowProjectiles;
        r.allowConsumables = allowConsumables;
        r.armorSlotAllowed = (armorSlotAllowed == null) ? new boolean[]{true, true, true, true} : Arrays.copyOf(armorSlotAllowed, armorSlotAllowed.length);
        return r;
    }

    public void normalize() {
        if (armorSlotAllowed == null || armorSlotAllowed.length != 4) {
            armorSlotAllowed = new boolean[]{true, true, true, true};
        }
    }

    public static DuelRules fromDefaults(DuelConfig cfg) {
        DuelRules r = new DuelRules();
        if (cfg == null || cfg.rules == null || cfg.rules.defaults == null) return r;
        r.ranked = cfg.rules.defaults.ranked;
        r.allowMelee = cfg.rules.defaults.allowMelee;
        r.allowProjectiles = cfg.rules.defaults.allowProjectiles;
        r.allowConsumables = cfg.rules.defaults.allowConsumables;
        r.armorSlotAllowed = (cfg.rules.defaults.armorSlotAllowed == null)
                ? new boolean[]{true, true, true, true}
                : Arrays.copyOf(cfg.rules.defaults.armorSlotAllowed, 4);
        r.normalize();
        return r;
    }
}

