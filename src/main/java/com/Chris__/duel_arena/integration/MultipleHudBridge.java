package com.Chris__.duel_arena.integration;

import com.Chris__.duel_arena.ui.hud.DuelStatusHud;
import com.Chris__.duel_arena.ui.hud.TournamentStatusHud;
import com.buuz135.mhud.MultipleHUD;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class MultipleHudBridge {

    public static final String DUEL_HUD_KEY = "DuelArena_DuelStatus";
    public static final String TOURNAMENT_HUD_KEY = "DuelArena_TournamentStatus";

    private final MultipleHUD multipleHUD;

    public MultipleHudBridge(@Nonnull MultipleHUD multipleHUD) {
        this.multipleHUD = multipleHUD;
    }

    public void showDuelHud(Player player, PlayerRef playerRef, DuelStatusHud hud) {
        if (player == null || playerRef == null || hud == null) return;
        multipleHUD.setCustomHud(player, playerRef, DUEL_HUD_KEY, hud);
    }

    public void hideDuelHud(Player player) {
        if (player == null) return;
        multipleHUD.hideCustomHud(player, DUEL_HUD_KEY);
    }

    public void showTournamentHud(Player player, PlayerRef playerRef, TournamentStatusHud hud) {
        if (player == null || playerRef == null || hud == null) return;
        multipleHUD.setCustomHud(player, playerRef, TOURNAMENT_HUD_KEY, hud);
    }

    public void hideTournamentHud(Player player) {
        if (player == null) return;
        multipleHUD.hideCustomHud(player, TOURNAMENT_HUD_KEY);
    }
}
