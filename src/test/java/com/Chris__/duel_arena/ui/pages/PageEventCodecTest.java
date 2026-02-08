package com.Chris__.duel_arena.ui.pages;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class PageEventCodecTest {

    @Test
    void pageEventCodecsInitialize() {
        assertAll(
                () -> assertNotNull(DuelOfferPage.DuelOfferEventData.CODEC),
                () -> assertNotNull(DuelConfirmPage.DuelConfirmEventData.CODEC),
                () -> assertNotNull(TournamentBracketPage.BracketEventData.CODEC)
        );
    }
}
