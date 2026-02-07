package com.Chris__.duel_arena;

import com.Chris__.duel_arena.arena.ArenaRepository;
import com.Chris__.duel_arena.arena.ArenaService;
import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.config.DuelConfig;
import com.Chris__.duel_arena.data.DuelEloRepository;
import com.Chris__.duel_arena.data.DuelHistoryLogger;
import com.Chris__.duel_arena.data.DuelPointsRepository;
import com.Chris__.duel_arena.data.PendingDeliveriesRepository;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.shop.DuelShopRepository;
import com.Chris__.duel_arena.systems.ArenaBreakBlockEventSystem;
import com.Chris__.duel_arena.systems.ArenaDamageBlockEventSystem;
import com.Chris__.duel_arena.systems.ArenaDropItemDropEventSystem;
import com.Chris__.duel_arena.systems.ArenaDropItemRequestEventSystem;
import com.Chris__.duel_arena.systems.ArenaPickupItemEventSystem;
import com.Chris__.duel_arena.systems.ArenaPlaceBlockEventSystem;
import com.Chris__.duel_arena.systems.ArenaUseBlockEventSystem;
import com.Chris__.duel_arena.systems.DuelArenaDamageSystem;
import com.Chris__.duel_arena.systems.DuelArenaTickSystem;
import com.Chris__.duel_arena.tourney.TournamentService;
import com.Chris__.duel_arena.ui.DuelUiService;
import com.Chris__.duel_arena.util.TeleportService;
import com.Chris__.duel_arena.commands.DuelCommand;
import com.Chris__.duel_arena.commands.TourneyCommand;
import com.Chris__.duel_arena.integration.SimpleClaimsVerifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class DuelArenaPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private ConfigRepository configRepository;
    private ArenaRepository arenaRepository;
    private ArenaService arenaService;

    private DuelPointsRepository pointsRepository;
    private DuelEloRepository eloRepository;
    private PendingDeliveriesRepository pendingDeliveriesRepository;
    private DuelShopRepository shopRepository;
    private DuelHistoryLogger historyLogger;

    private TeleportService teleportService;
    private DuelService duelService;
    private TournamentService tournamentService;
    private DuelUiService duelUiService;
    private SimpleClaimsVerifier simpleClaimsVerifier;

    public DuelArenaPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Loaded %s v%s", this.getName(), this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up DuelArenaPlugin...");

        this.configRepository = new ConfigRepository(this.getDataDirectory(), LOGGER);
        DuelConfig cfg = this.configRepository.get();

        this.arenaRepository = new ArenaRepository(this.getDataDirectory(), LOGGER);
        this.arenaService = new ArenaService(this.arenaRepository, LOGGER);
        this.simpleClaimsVerifier = new SimpleClaimsVerifier(this.configRepository, this.arenaService, LOGGER);

        this.pointsRepository = new DuelPointsRepository(this.getDataDirectory(), LOGGER);
        this.eloRepository = new DuelEloRepository(this.getDataDirectory(), LOGGER,
                (cfg == null || cfg.elo == null) ? 1000 : cfg.elo.initialRating,
                (cfg == null || cfg.elo == null) ? 32 : cfg.elo.kFactor
        );
        this.pendingDeliveriesRepository = new PendingDeliveriesRepository(this.getDataDirectory(), LOGGER);
        this.shopRepository = new DuelShopRepository(this.getDataDirectory(), LOGGER);
        this.historyLogger = new DuelHistoryLogger(this.getDataDirectory(), this.configRepository, LOGGER);

        this.teleportService = new TeleportService();
        this.duelService = new DuelService(
                this.configRepository,
                this.arenaService,
                this.pointsRepository,
                this.eloRepository,
                this.pendingDeliveriesRepository,
                this.historyLogger,
                this.teleportService,
                LOGGER
        );
        this.tournamentService = new TournamentService(
                this.configRepository,
                this.arenaService,
                this.duelService,
                this.pointsRepository,
                this.pendingDeliveriesRepository,
                this.teleportService,
                (s) -> {
                    if (this.duelUiService != null) this.duelUiService.requestRefreshForSession(s);
                },
                LOGGER
        );
        this.duelService.addListener(this.tournamentService);

        this.duelUiService = new DuelUiService(this.duelService, this.tournamentService, this.configRepository);

        // ---------------------------------------------------------------------
        // ECS systems (events + ticking)
        // ---------------------------------------------------------------------

        this.getEntityStoreRegistry().registerSystem(new DuelArenaTickSystem(
                this.duelService,
                this.tournamentService,
                this.teleportService,
                this.pendingDeliveriesRepository
        ));
        this.getEntityStoreRegistry().registerSystem(new DuelArenaDamageSystem(this.duelService, this.arenaService));

        this.getEntityStoreRegistry().registerSystem(new ArenaPlaceBlockEventSystem(this.arenaService));
        this.getEntityStoreRegistry().registerSystem(new ArenaBreakBlockEventSystem(this.arenaService));
        this.getEntityStoreRegistry().registerSystem(new ArenaDamageBlockEventSystem(this.arenaService));
        this.getEntityStoreRegistry().registerSystem(new ArenaUseBlockEventSystem(this.arenaService));

        this.getEntityStoreRegistry().registerSystem(new ArenaDropItemRequestEventSystem(this.arenaService, this.duelService));
        this.getEntityStoreRegistry().registerSystem(new ArenaDropItemDropEventSystem(this.arenaService, this.duelService));
        this.getEntityStoreRegistry().registerSystem(new ArenaPickupItemEventSystem(this.arenaService, this.duelService));

        if (this.duelUiService != null) {
            this.getEntityStoreRegistry().registerSystem(this.duelUiService.createSystem());
        }

        // ---------------------------------------------------------------------
        // EventRegistry hooks (non-ECS events)
        // ---------------------------------------------------------------------

        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onAddPlayerToWorld);
        this.getEventRegistry().register(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, this::onPlayerInteract);

        // ---------------------------------------------------------------------
        // Commands
        // ---------------------------------------------------------------------

        this.getCommandRegistry().registerCommand(new DuelCommand(
                this.duelService,
                this.duelUiService,
                this.configRepository,
                this.arenaRepository,
                this.arenaService,
                this.pointsRepository,
                this.eloRepository,
                this.shopRepository,
                this.pendingDeliveriesRepository,
                this.simpleClaimsVerifier
        ));
        this.getCommandRegistry().registerCommand(new TourneyCommand(
                this.tournamentService,
                this.duelUiService
        ));

        if (this.simpleClaimsVerifier != null) {
            this.simpleClaimsVerifier.verifyArenasWarnOnly();
        }
    }

    private void onAddPlayerToWorld(AddPlayerToWorldEvent event) {
        if (event == null || event.getHolder() == null) return;

        try {
            var holder = event.getHolder();
            Player player = holder.getComponent(Player.getComponentType());
            PlayerRef pr = holder.getComponent(PlayerRef.getComponentType());
            if (player == null || pr == null || pr.getUuid() == null) return;

            String uuid = pr.getUuid().toString();
            String name = player.getDisplayName();

            if (pointsRepository != null) pointsRepository.setLastKnownName(uuid, name);
            if (eloRepository != null) eloRepository.setLastKnownName(uuid, name);

            int delivered = (pendingDeliveriesRepository == null) ? 0 : pendingDeliveriesRepository.tryDeliver(player);
            if (delivered > 0) {
                player.sendMessage(Message.raw("[DuelArena] Delivered " + delivered + " pending item(s)."));
            }
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[DuelArena] onAddPlayerToWorld failed.");
        }
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        if (event == null || duelService == null) return;
        try {
            PlayerRef pr = event.getPlayerRef();
            if (pr == null || pr.getUuid() == null) return;
            duelService.onDisconnect(pr.getUuid().toString());
        } catch (Throwable ignored) {
        }
    }

    private void onPlayerInteract(PlayerInteractEvent event) {
        if (event == null || duelService == null) return;
        if (event.isCancelled()) return;
        if (event.getActionType() != InteractionType.Use) return;

        Player p = event.getPlayer();
        if (p == null || p.getUuid() == null) return;

        ItemStack held = event.getItemInHand();
        if (held == null || held.isEmpty() || held.getItemId() == null) return;

        String uuid = p.getUuid().toString();
        if (duelService.shouldCancelConsumableUse(uuid, held.getItemId())) {
            event.setCancelled(true);
            try {
                p.sendMessage(Message.raw("[DuelArena] Consumables are disabled for this duel."));
            } catch (Throwable ignored) {
            }
        }
    }
}
