package com.Chris__.duel_arena.ui.pages;

import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.config.DuelConfig;
import com.Chris__.duel_arena.tourney.TournamentService;
import com.Chris__.duel_arena.tourney.TournamentState;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

public final class TournamentBracketPage extends InteractiveCustomUIPage<TournamentBracketPage.BracketEventData> {

    public static final class BracketEventData {
        public static final BuilderCodec<BracketEventData> CODEC = BuilderCodec.builder(BracketEventData.class, BracketEventData::new)
                .append(new KeyedCodec<>("Id", Codec.STRING), BracketEventData::setId, BracketEventData::getId)
                .add()
                .build();

        private String id = "";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = (id == null) ? "" : id;
        }
    }

    private final TournamentService tournamentService;
    private final Consumer<String> onRefresh;
    private final ConfigRepository configRepository;

    public TournamentBracketPage(@Nonnull PlayerRef playerRef,
                                 TournamentService tournamentService,
                                 Consumer<String> onRefresh,
                                 ConfigRepository configRepository) {
        super(playerRef, CustomPageLifetime.CanDismiss, BracketEventData.CODEC);
        this.tournamentService = tournamentService;
        this.onRefresh = onRefresh;
        this.configRepository = configRepository;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        String uiPath = (cfg == null || cfg.ui == null || cfg.ui.bracketUiPath == null || cfg.ui.bracketUiPath.isBlank())
                ? "Pages/DuelArena/TournamentBracket.ui"
                : cfg.ui.bracketUiPath.trim();
        ui.append(uiPath);

        TournamentState t = (tournamentService == null) ? null : tournamentService.getActive();
        if (t == null) {
            ui.set("#TitleLabel.TextSpans", Message.raw("Tournament"));
            ui.set("#StatusLabel.TextSpans", Message.raw("No active tournament."));
            ui.set("#BodyLabel.TextSpans", Message.raw(""));
        } else {
            String status = "Status: " + t.status + " (" + t.entrants.size() + "/" + t.size + ")";
            ui.set("#TitleLabel.TextSpans", Message.raw("Tournament " + t.id));
            ui.set("#StatusLabel.TextSpans", Message.raw(status));

            StringBuilder body = new StringBuilder();
            if (t.rounds != null) {
                for (TournamentState.Round r : t.rounds) {
                    if (r == null || r.matches == null) continue;
                    body.append("Round ").append(r.roundNumber).append("\n");
                    for (TournamentState.Match m : r.matches) {
                        if (m == null) continue;
                        body.append("  ").append(shortUuid(m.aUuid)).append(" vs ").append(shortUuid(m.bUuid));
                        if (m.winnerUuid != null && !m.winnerUuid.isBlank()) {
                            body.append(" -> ").append(shortUuid(m.winnerUuid));
                        }
                        body.append("\n");
                    }
                }
            }
            ui.set("#BodyLabel.TextSpans", Message.raw(body.toString().trim()));
        }

        events.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshButton", EventData.of("Id", "bracket:refresh"));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, BracketEventData data) {
        if (data == null) return;
        String id = (data.getId() == null) ? "" : data.getId().trim();
        if (!"bracket:refresh".equalsIgnoreCase(id)) return;

        if (store == null || ref == null) return;
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        String uuid = (pr == null || pr.getUuid() == null) ? null : pr.getUuid().toString();
        if (uuid == null || uuid.isBlank()) return;

        if (onRefresh != null) {
            try {
                onRefresh.accept(uuid);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String shortUuid(String uuid) {
        if (uuid == null) return "";
        if (uuid.length() <= 8) return uuid;
        return uuid.substring(0, 8);
    }
}
