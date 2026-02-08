package com.Chris__.duel_arena.ui.pages;

import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.config.DuelConfig;
import com.Chris__.duel_arena.data.SerializedItemStack;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.duel.DuelSession;
import com.Chris__.duel_arena.duel.DuelStage;
import com.Chris__.duel_arena.duel.DuelStake;
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
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class DuelConfirmPage extends InteractiveCustomUIPage<DuelConfirmPage.DuelConfirmEventData> {

    public static final class DuelConfirmEventData {
        public static final BuilderCodec<DuelConfirmEventData> CODEC = BuilderCodec.builder(DuelConfirmEventData.class, DuelConfirmEventData::new)
                .append(new KeyedCodec<>("Id", Codec.STRING), DuelConfirmEventData::setId, DuelConfirmEventData::getId)
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

    private static final int MAX_ITEM_ROWS = 4;

    private final DuelService duelService;
    private final Consumer<DuelSession> onChange;
    private final ConfigRepository configRepository;

    public DuelConfirmPage(@Nonnull PlayerRef playerRef,
                           DuelService duelService,
                           Consumer<DuelSession> onChange,
                           ConfigRepository configRepository) {
        super(playerRef, CustomPageLifetime.CanDismiss, DuelConfirmEventData.CODEC);
        this.duelService = duelService;
        this.onChange = onChange;
        this.configRepository = configRepository;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        DuelConfig cfg = (configRepository == null) ? null : configRepository.get();
        String uiPath = (cfg == null || cfg.ui == null || cfg.ui.confirmUiPath == null || cfg.ui.confirmUiPath.isBlank())
                ? "Pages/DuelArena/DuelConfirm.ui"
                : cfg.ui.confirmUiPath.trim();
        ui.append(uiPath);

        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        String viewerUuid = (pr == null || pr.getUuid() == null) ? null : pr.getUuid().toString();

        DuelSession s = (duelService == null || viewerUuid == null) ? null : duelService.getSessionForPlayer(viewerUuid);
        if (s == null || s.stage != DuelStage.CONFIRM) {
            ui.set("#TitleLabel.TextSpans", Message.raw("Confirm Duel"));
            ui.set("#SummaryLabel.TextSpans", Message.raw("No active duel to confirm."));
            ui.set("#YourItemsGroup.Visible", false);
            ui.set("#OppItemsGroup.Visible", false);
            return;
        }

        boolean viewerIsA = viewerUuid.equals(s.playerAUuid);
        boolean youAccepted = viewerIsA ? s.confirmAcceptedA : s.confirmAcceptedB;
        boolean oppAccepted = viewerIsA ? s.confirmAcceptedB : s.confirmAcceptedA;

        String nameA = resolveName(s.playerAUuid);
        String nameB = resolveName(s.playerBUuid);

        ui.set("#TitleLabel.TextSpans", Message.raw(s.tournamentMatch ? "Confirm Tournament Match" : "Confirm Duel"));

        String rules = "Rules: ranked=" + yn(s.rules.ranked) +
                " melee=" + yn(s.rules.allowMelee) +
                " projectiles=" + yn(s.rules.allowProjectiles) +
                " consumables=" + yn(s.rules.allowConsumables);

        DuelStake stakeA = s.stakeA;
        DuelStake stakeB = s.stakeB;

        int aPts = (stakeA == null) ? 0 : Math.max(0, stakeA.points);
        int bPts = (stakeB == null) ? 0 : Math.max(0, stakeB.points);

        String stakes = s.stakingEnabled && !s.tournamentMatch
                ? ("Stakes: " + nameA + "=" + aPts + " pts, " + nameB + "=" + bPts + " pts")
                : "Stakes: disabled";

        ui.set("#SummaryLabel.TextSpans", Message.raw(nameA + " vs " + nameB + "\n" + rules + "\n" + stakes +
                "\nAccepted: you " + yn(youAccepted) + " | opponent " + yn(oppAccepted)));

        boolean showItems = s.stakingEnabled && !s.tournamentMatch;
        ui.set("#YourItemsGroup.Visible", showItems);
        ui.set("#OppItemsGroup.Visible", showItems);

        if (showItems) {
            DuelStake yourStake = viewerIsA ? stakeA : stakeB;
            DuelStake oppStake = viewerIsA ? stakeB : stakeA;
            List<SerializedItemStack> yourItems = (yourStake == null || yourStake.items == null) ? List.of() : yourStake.items;
            List<SerializedItemStack> oppItems = (oppStake == null || oppStake.items == null) ? List.of() : oppStake.items;
            renderItems(ui, yourItems, "#YourItem", "#YourItem");
            renderItems(ui, oppItems, "#OppItem", "#OppItem");
        }

        events.addEventBinding(CustomUIEventBindingType.Activating, "#AcceptButton", EventData.of("Id", "confirm:accept"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DeclineButton", EventData.of("Id", "confirm:decline"));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, DuelConfirmEventData data) {
        if (ref == null || store == null || data == null) return;
        if (duelService == null) return;

        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        String viewerUuid = (pr == null || pr.getUuid() == null) ? null : pr.getUuid().toString();
        if (viewerUuid == null || viewerUuid.isBlank()) return;

        DuelSession s = duelService.getSessionForPlayer(viewerUuid);
        if (s == null || s.stage != DuelStage.CONFIRM) return;

        String id = (data.getId() == null) ? "" : data.getId().trim();
        if (id.isEmpty()) return;

        boolean changed = false;
        if ("confirm:accept".equalsIgnoreCase(id)) {
            changed = duelService.acceptConfirm(s.id, viewerUuid);
        } else if ("confirm:decline".equalsIgnoreCase(id)) {
            duelService.decline(s.id, viewerUuid);
            changed = true;
        }

        if (changed && onChange != null) {
            try {
                onChange.accept(s);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void renderItems(UICommandBuilder ui, List<SerializedItemStack> items, String rowPrefix, String labelPrefix) {
        List<SerializedItemStack> list = (items == null) ? List.of() : items;
        for (int i = 0; i < MAX_ITEM_ROWS; i++) {
            String row = rowPrefix + i;
            String label = labelPrefix + i + "Label.TextSpans";
            if (i >= list.size()) {
                ui.set(row + ".Visible", false);
                continue;
            }
            SerializedItemStack it = list.get(i);
            String text = (it == null) ? "<invalid>" : safe(it.itemId) + " x" + Math.max(0, it.quantity);
            ui.set(row + ".Visible", true);
            ui.set(label, Message.raw(text));
        }
    }

    private static String yn(boolean b) {
        return b ? "ON" : "OFF";
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static String resolveName(String uuid) {
        if (uuid == null || uuid.isBlank()) return "<unknown>";
        try {
            PlayerRef pr = Universe.get().getPlayer(UUID.fromString(uuid));
            if (pr != null) {
                String u = pr.getUsername();
                if (u != null && !u.isBlank()) return u;
            }
        } catch (Throwable ignored) {
        }
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
    }
}
