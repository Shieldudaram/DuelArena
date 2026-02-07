package com.Chris__.duel_arena.duel;

import com.Chris__.duel_arena.data.DuelEloRepository;

public record DuelResult(
        String winnerUuid,
        String loserUuid,
        DuelEndReason reason,
        boolean ranked,
        DuelEloRepository.EloDelta eloDelta
) {
}

