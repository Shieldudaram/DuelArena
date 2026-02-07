package com.Chris__.duel_arena.duel;

public interface DuelServiceListener {
    default void onDuelEnded(DuelSession session, DuelResult result) {
    }

    default void onDuelCanceled(DuelSession session, DuelCancelReason reason) {
    }
}

