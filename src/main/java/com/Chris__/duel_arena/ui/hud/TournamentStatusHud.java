package com.Chris__.duel_arena.ui.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class TournamentStatusHud extends CustomUIHud {

    private boolean visible = false;
    private String title = "";
    private String line1 = "";
    private String line2 = "";

    public TournamentStatusHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    public void show(String title, String line1, String line2) {
        this.visible = true;
        this.title = safe(title);
        this.line1 = safe(line1);
        this.line2 = safe(line2);
    }

    public void hide() {
        this.visible = false;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        if (!visible) return;

        ui.append("HUD/DuelArena/TournamentStatus.ui");
        ui.set("#TitleLabel.TextSpans", Message.raw(title));
        ui.set("#Line1Label.TextSpans", Message.raw(line1));
        ui.set("#Line2Label.TextSpans", Message.raw(line2));
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }
}
