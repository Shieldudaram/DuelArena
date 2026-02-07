package com.Chris__.duel_arena.duel;

import com.Chris__.duel_arena.data.SerializedItemStack;

import java.util.ArrayList;
import java.util.List;

public final class DuelStake {
    public int points = 0;
    public List<SerializedItemStack> items = new ArrayList<>();

    public DuelStake copy() {
        DuelStake s = new DuelStake();
        s.points = points;
        if (items != null) {
            s.items = new ArrayList<>(items);
        }
        return s;
    }
}

