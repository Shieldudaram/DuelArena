package com.Chris__.duel_arena.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public final class DuelEloRepositoryTest {

    @Test
    void equalRatingsWinUpdatesAsExpected() {
        DuelEloRepository repo = new DuelEloRepository(null, null, 1000, 32);

        String a = "00000000-0000-0000-0000-000000000001";
        String b = "00000000-0000-0000-0000-000000000002";

        DuelEloRepository.EloDelta delta = repo.recordRankedMatch(a, b);
        assertNotNull(delta);

        assertEquals(1000, delta.winnerBefore());
        assertEquals(1000, delta.loserBefore());
        assertEquals(1016, delta.winnerAfter());
        assertEquals(984, delta.loserAfter());

        assertEquals(1016, repo.getRating(a));
        assertEquals(984, repo.getRating(b));
    }

    @Test
    void ratingsNeverDropBelowOne() {
        DuelEloRepository repo = new DuelEloRepository(null, null, 1, 64);

        String winner = "00000000-0000-0000-0000-000000000010";
        String loser = "00000000-0000-0000-0000-000000000011";

        // Force loser down repeatedly.
        for (int i = 0; i < 200; i++) {
            repo.recordRankedMatch(winner, loser);
        }

        assertTrue(repo.getRating(loser) >= 1);
        assertTrue(repo.getRating(winner) >= 1);
    }
}
