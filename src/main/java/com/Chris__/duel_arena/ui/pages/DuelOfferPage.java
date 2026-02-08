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
import com.hypixel.hytale.server.core.entity.entities.Player;
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

public final class DuelOfferPage extends InteractiveCustomUIPage<DuelOfferPage.DuelOfferEventData> {

    public static final class DuelOfferEventData {
        public static final BuilderCodec<DuelOfferEventData> CODEC = BuilderCodec.builder(DuelOfferEventData.class, DuelOfferEventData::new)
                .append(new KeyedCodec<>("Id", Codec.STRING), DuelOfferEventData::setId, DuelOfferEventData::getId)
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

    public DuelOfferPage(@Nonnull PlayerRef playerRef,
                         DuelService duelService,
                         Consumer<DuelSession> onChange,
                         ConfigRepository configRepository) {
        super(playerRef, CustomPageLifetime.CanDismiss, DuelOfferEventData.CODEC);
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
        String uiPath = (cfg == null || cfg.ui == null || cfg.ui.offerUiPath == null || cfg.ui.offerUiPath.isBlank())
                ? "Pages/DuelArena/DuelOffer.ui"
                : cfg.ui.offerUiPath.trim();

        ui.append(uiPath);

        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        String viewerUuid = (pr == null || pr.getUuid() == null) ? null : pr.getUuid().toString();

        DuelSession s = (duelService == null || viewerUuid == null) ? null : duelService.getSessionForPlayer(viewerUuid);
        if (s == null || s.stage != DuelStage.OFFER) {
            ui.set("#TitleLabel.TextSpans", Message.raw("Duel Offer"));
            ui.set("#PlayersLabel.TextSpans", Message.raw("No active duel offer."));
            ui.set("#StatusLabel.TextSpans", Message.raw(""));
            ui.set("#RulesGroup.Visible", false);
            ui.set("#StakesGroup.Visible", false);
            return;
        }

        boolean viewerIsA = viewerUuid.equals(s.playerAUuid);

        String nameA = resolveName(s.playerAUuid);
        String nameB = resolveName(s.playerBUuid);

        ui.set("#TitleLabel.TextSpans", Message.raw(s.tournamentMatch ? "Tournament Match Offer" : "Duel Offer"));
        ui.set("#PlayersLabel.TextSpans", Message.raw(nameA + " vs " + nameB));

        boolean youAccepted = viewerIsA ? s.offerAcceptedA : s.offerAcceptedB;
        boolean oppAccepted = viewerIsA ? s.offerAcceptedB : s.offerAcceptedA;
        ui.set("#StatusLabel.TextSpans", Message.raw("Accepted: you " + yn(youAccepted) + " | opponent " + yn(oppAccepted)));

        boolean showRules = !s.tournamentMatch;
        ui.set("#RulesGroup.Visible", showRules);

        if (showRules) {
            setToggle(ui, "#ToggleRankedButton", "Ranked", s.rules.ranked);
            setToggle(ui, "#ToggleMeleeButton", "Melee", s.rules.allowMelee);
            setToggle(ui, "#ToggleProjectilesButton", "Projectiles", s.rules.allowProjectiles);
            setToggle(ui, "#ToggleConsumablesButton", "Consumables", s.rules.allowConsumables);
            setToggle(ui, "#Armor0Button", "Armor 1", safeArmorAllowed(s, 0));
            setToggle(ui, "#Armor1Button", "Armor 2", safeArmorAllowed(s, 1));
            setToggle(ui, "#Armor2Button", "Armor 3", safeArmorAllowed(s, 2));
            setToggle(ui, "#Armor3Button", "Armor 4", safeArmorAllowed(s, 3));

            events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleRankedButton", EventData.of("Id", "rule:ranked"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleMeleeButton", EventData.of("Id", "rule:melee"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleProjectilesButton", EventData.of("Id", "rule:projectiles"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleConsumablesButton", EventData.of("Id", "rule:consumables"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Armor0Button", EventData.of("Id", "rule:armor0"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Armor1Button", EventData.of("Id", "rule:armor1"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Armor2Button", EventData.of("Id", "rule:armor2"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Armor3Button", EventData.of("Id", "rule:armor3"));
        }

        boolean showStakes = s.stakingEnabled && !s.tournamentMatch;
        ui.set("#StakesGroup.Visible", showStakes);

        DuelStake yourStake = viewerIsA ? s.stakeA : s.stakeB;
        DuelStake oppStake = viewerIsA ? s.stakeB : s.stakeA;
        int yourPoints = (yourStake == null) ? 0 : Math.max(0, yourStake.points);
        int oppPoints = (oppStake == null) ? 0 : Math.max(0, oppStake.points);

        ui.set("#YourPointsStakeLabel.TextSpans", Message.raw("Your points stake: " + yourPoints));
        ui.set("#OppPointsStakeLabel.TextSpans", Message.raw("Opponent points stake: " + oppPoints));

        if (showStakes) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PointsPlus10", EventData.of("Id", "stake:points:+10"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PointsPlus100", EventData.of("Id", "stake:points:+100"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PointsMinus10", EventData.of("Id", "stake:points:-10"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PointsMinus100", EventData.of("Id", "stake:points:-100"));

            events.addEventBinding(CustomUIEventBindingType.Activating, "#AddHeld1Button", EventData.of("Id", "stake:item:add:1"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#AddHeld10Button", EventData.of("Id", "stake:item:add:10"));
        }

        // Item rows (viewer)
        List<SerializedItemStack> yourItems = (yourStake == null || yourStake.items == null) ? List.of() : yourStake.items;
        for (int i = 0; i < MAX_ITEM_ROWS; i++) {
            String row = "#YourItem" + i;
            String label = "#YourItem" + i + "Label.TextSpans";
            String remove = "#YourItem" + i + "RemoveButton";

            if (i >= yourItems.size()) {
                ui.set(row + ".Visible", false);
                continue;
            }

            SerializedItemStack it = yourItems.get(i);
            String text = (it == null) ? "<invalid>" : safe(it.itemId) + " x" + Math.max(0, it.quantity);
            ui.set(row + ".Visible", true);
            ui.set(label, Message.raw(text));
            if (showStakes) {
                events.addEventBinding(CustomUIEventBindingType.Activating, remove, EventData.of("Id", "stake:item:remove:" + i));
            }
        }

        // Actions
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AcceptButton", EventData.of("Id", "offer:accept"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DeclineButton", EventData.of("Id", "offer:decline"));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, DuelOfferEventData data) {
        if (ref == null || store == null || data == null) return;
        if (duelService == null) return;

        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        String viewerUuid = (pr == null || pr.getUuid() == null) ? null : pr.getUuid().toString();
        if (viewerUuid == null || viewerUuid.isBlank()) return;

        DuelSession s = duelService.getSessionForPlayer(viewerUuid);
        if (s == null || s.stage != DuelStage.OFFER) return;

        String id = (data.getId() == null) ? "" : data.getId().trim();
        if (id.isEmpty()) return;

        boolean changed = false;

        if (id.startsWith("rule:")) {
            String rule = id.substring("rule:".length());
            changed = duelService.toggleRule(s.id, viewerUuid, rule);
        } else if (id.startsWith("stake:points:")) {
            try {
                int delta = Integer.parseInt(id.substring("stake:points:".length()));
                changed = duelService.adjustPointStake(s.id, viewerUuid, delta);
            } catch (Throwable ignored) {
            }
        } else if (id.startsWith("stake:item:add:")) {
            try {
                int qty = Integer.parseInt(id.substring("stake:item:add:".length()));
                Player p = store.getComponent(ref, Player.getComponentType());
                changed = duelService.addHeldItemStake(s.id, p, qty);
            } catch (Throwable ignored) {
            }
        } else if (id.startsWith("stake:item:remove:")) {
            try {
                int idx = Integer.parseInt(id.substring("stake:item:remove:".length()));
                changed = duelService.removeStakeItemAt(s.id, viewerUuid, idx);
            } catch (Throwable ignored) {
            }
        } else if ("offer:accept".equalsIgnoreCase(id)) {
            changed = duelService.acceptOffer(s.id, viewerUuid);
        } else if ("offer:decline".equalsIgnoreCase(id)) {
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

    private static boolean safeArmorAllowed(DuelSession s, int idx) {
        if (s == null || s.rules == null || s.rules.armorSlotAllowed == null) return true;
        if (idx < 0 || idx >= s.rules.armorSlotAllowed.length) return true;
        return s.rules.armorSlotAllowed[idx];
    }

    private static void setToggle(UICommandBuilder ui, String buttonId, String name, boolean enabled) {
        ui.set(buttonId + ".Text", name + ": " + yn(enabled));
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
