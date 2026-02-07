package com.Chris__.duel_arena.commands;

import com.Chris__.duel_arena.arena.Arena;
import com.Chris__.duel_arena.arena.ArenaRepository;
import com.Chris__.duel_arena.arena.ArenaService;
import com.Chris__.duel_arena.config.ConfigRepository;
import com.Chris__.duel_arena.config.DuelConfig;
import com.Chris__.duel_arena.data.DuelEloRepository;
import com.Chris__.duel_arena.data.DuelPointsRepository;
import com.Chris__.duel_arena.data.PendingDeliveriesRepository;
import com.Chris__.duel_arena.duel.DuelService;
import com.Chris__.duel_arena.shop.DuelShopConfig;
import com.Chris__.duel_arena.shop.DuelShopRepository;
import com.Chris__.duel_arena.ui.DuelUiService;
import com.Chris__.duel_arena.util.InventoryUtil;
import com.Chris__.duel_arena.integration.SimpleClaimsVerifier;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DuelCommand extends CommandBase {

    private static final Message MSG_USAGE = Message.raw(
            "Usage: /duel <player> | forfeit | points | elo | top | shop <list|buy id> | arena <...> | reload"
    );

    private final DuelService duelService;
    private final DuelUiService duelUiService;
    private final ConfigRepository configRepository;
    private final ArenaRepository arenaRepository;
    private final ArenaService arenaService;
    private final DuelPointsRepository pointsRepository;
    private final DuelEloRepository eloRepository;
    private final DuelShopRepository shopRepository;
    private final PendingDeliveriesRepository pendingDeliveriesRepository;
    private final SimpleClaimsVerifier simpleClaimsVerifier;

    public DuelCommand(DuelService duelService,
                       DuelUiService duelUiService,
                       ConfigRepository configRepository,
                       ArenaRepository arenaRepository,
                       ArenaService arenaService,
                       DuelPointsRepository pointsRepository,
                       DuelEloRepository eloRepository,
                       DuelShopRepository shopRepository,
                       PendingDeliveriesRepository pendingDeliveriesRepository,
                       SimpleClaimsVerifier simpleClaimsVerifier) {
        super("duel", "Duel arena commands.");
        this.addAliases("duelarena");
        this.setAllowsExtraArguments(true);
        this.setPermissionGroup(GameMode.Adventure);
        this.duelService = duelService;
        this.duelUiService = duelUiService;
        this.configRepository = configRepository;
        this.arenaRepository = arenaRepository;
        this.arenaService = arenaService;
        this.pointsRepository = pointsRepository;
        this.eloRepository = eloRepository;
        this.shopRepository = shopRepository;
        this.pendingDeliveriesRepository = pendingDeliveriesRepository;
        this.simpleClaimsVerifier = simpleClaimsVerifier;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String[] args = splitArgs(ctx.getInputString());
        if (args.length < 2) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        String sub = args[1];
        if (sub == null || sub.isBlank()) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        String senderUuid = (ctx.sender() == null || ctx.sender().getUuid() == null) ? null : ctx.sender().getUuid().toString();

        if ("forfeit".equalsIgnoreCase(sub) || "ff".equalsIgnoreCase(sub)) {
            if (senderUuid == null || senderUuid.isBlank()) {
                ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                return;
            }
            if (duelService != null) duelService.forfeit(senderUuid);
            ctx.sendMessage(Message.raw("[DuelArena] Forfeit requested."));
            return;
        }

        if ("points".equalsIgnoreCase(sub)) {
            if (senderUuid == null || senderUuid.isBlank()) {
                ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                return;
            }
            int pts = (pointsRepository == null) ? 0 : pointsRepository.getPoints(senderUuid);
            ctx.sendMessage(Message.raw("[DuelArena] Duel points: " + pts));
            return;
        }

        if ("elo".equalsIgnoreCase(sub) || "rating".equalsIgnoreCase(sub)) {
            if (senderUuid == null || senderUuid.isBlank()) {
                ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                return;
            }
            int rating = (eloRepository == null) ? 1000 : eloRepository.getRating(senderUuid);
            ctx.sendMessage(Message.raw("[DuelArena] Duel rating: " + rating));
            return;
        }

        if ("top".equalsIgnoreCase(sub)) {
            int limit = 10;
            if (eloRepository == null) {
                ctx.sendMessage(Message.raw("[DuelArena] Elo is not ready."));
                return;
            }
            var top = eloRepository.topByRating(limit);
            ctx.sendMessage(Message.raw("[DuelArena] Top " + limit + " ratings:"));
            int i = 1;
            for (var e : top) {
                if (e == null || e.getKey() == null || e.getValue() == null) continue;
                String uuid = e.getKey();
                DuelEloRepository.EloEntry entry = e.getValue();
                String name = (entry.lastKnownName != null && !entry.lastKnownName.isBlank()) ? entry.lastKnownName : shortUuid(uuid);
                ctx.sendMessage(Message.raw(" " + (i++) + ") " + name + " - " + entry.rating + " (" + entry.wins + "W/" + entry.losses + "L)"));
            }
            return;
        }

        if ("shop".equalsIgnoreCase(sub)) {
            if (args.length < 3) {
                ctx.sendMessage(Message.raw("Usage: /duel shop <list|buy id>"));
                return;
            }

            String action = args[2];
            if ("list".equalsIgnoreCase(action)) {
                if (shopRepository != null) shopRepository.reload();
                DuelShopConfig cfg = (shopRepository == null) ? null : shopRepository.get();
                if (cfg == null || cfg.items == null) {
                    ctx.sendMessage(Message.raw("[DuelArena] Shop is empty."));
                    return;
                }
                ctx.sendMessage(Message.raw("[DuelArena] Shop items:"));
                for (DuelShopConfig.ShopItem it : cfg.items) {
                    if (it == null || !it.enabled) continue;
                    String id = (it.id == null) ? "" : it.id;
                    String name = (it.name == null || it.name.isBlank()) ? id : it.name;
                    int cost = Math.max(0, it.cost);
                    ctx.sendMessage(Message.raw(" - " + name + " (" + id + "): " + cost + " pts"));
                }
                return;
            }

            if ("buy".equalsIgnoreCase(action)) {
                if (senderUuid == null || senderUuid.isBlank()) {
                    ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                    return;
                }
                if (args.length < 4) {
                    ctx.sendMessage(Message.raw("Usage: /duel shop buy <id>"));
                    return;
                }
                String id = args[3];
                if (id == null || id.isBlank()) return;

                if (shopRepository != null) shopRepository.reload();
                DuelShopConfig cfg = (shopRepository == null) ? null : shopRepository.get();
                DuelShopConfig.ShopItem it = findShopItem(cfg, id);
                if (it == null || !it.enabled) {
                    ctx.sendMessage(Message.raw("[DuelArena] Unknown shop item: " + id));
                    return;
                }
                int cost = Math.max(0, it.cost);
                if (pointsRepository == null || !pointsRepository.spendPoints(senderUuid, cost)) {
                    int have = (pointsRepository == null) ? 0 : pointsRepository.getPoints(senderUuid);
                    ctx.sendMessage(Message.raw("[DuelArena] Not enough points. (" + have + "/" + cost + ")"));
                    return;
                }

                if (it.type == null || !it.type.equalsIgnoreCase("item")) {
                    ctx.sendMessage(Message.raw("[DuelArena] Unsupported shop type: " + String.valueOf(it.type)));
                    return;
                }
                String itemId = (it.itemId == null) ? "" : it.itemId.trim();
                int amount = Math.max(1, it.amount);
                if (itemId.isEmpty() || "REPLACE_ME".equalsIgnoreCase(itemId)) {
                    ctx.sendMessage(Message.raw("[DuelArena] Shop item misconfigured (itemId)."));
                    pointsRepository.addPoints(senderUuid, cost);
                    return;
                }

                ItemStack stack;
                try {
                    stack = new ItemStack(itemId, amount);
                } catch (Throwable t) {
                    stack = null;
                }
                if (stack == null || stack.isEmpty()) {
                    ctx.sendMessage(Message.raw("[DuelArena] Failed to create item: " + itemId));
                    pointsRepository.addPoints(senderUuid, cost);
                    return;
                }

                Player senderPlayer = ctx.senderAs(Player.class);
                if (senderPlayer == null) {
                    if (pendingDeliveriesRepository != null) {
                        pendingDeliveriesRepository.add(senderUuid, stack);
                    }
                    ctx.sendMessage(Message.raw("[DuelArena] Purchased; delivery queued."));
                    return;
                }

                boolean ok = InventoryUtil.tryGive(senderPlayer, stack);
                if (ok) {
                    try {
                        senderPlayer.sendInventory();
                    } catch (Throwable ignored) {
                    }
                    ctx.sendMessage(Message.raw("[DuelArena] Purchased: " + safe(it.name, it.id) + " (" + cost + " pts)"));
                } else {
                    if (pendingDeliveriesRepository != null) pendingDeliveriesRepository.add(senderUuid, stack);
                    ctx.sendMessage(Message.raw("[DuelArena] Inventory full; delivery queued."));
                }
                return;
            }

            ctx.sendMessage(Message.raw("Usage: /duel shop <list|buy id>"));
            return;
        }

        if ("arena".equalsIgnoreCase(sub)) {
            if (args.length < 3) {
                ctx.sendMessage(Message.raw("Usage: /duel arena <list|create|delete|setbounds|setspectatorbounds|setspawna|setspawnb|setspectatorspawn> ..."));
                return;
            }
            if (!isAdmin(ctx)) {
                ctx.sendMessage(Message.raw("[DuelArena] Missing permission: duel_arena.admin"));
                return;
            }

            String action = args[2];
            if ("list".equalsIgnoreCase(action)) {
                if (arenaService != null) arenaService.reloadFromRepo();
                List<Arena> arenas = (arenaService == null) ? List.of() : arenaService.listArenas();
                ctx.sendMessage(Message.raw("[DuelArena] Arenas:"));
                for (Arena a : arenas) {
                    if (a == null) continue;
                    ctx.sendMessage(Message.raw(" - " + a.id + " (world=" + a.world + ")"));
                }
                return;
            }

            if ("create".equalsIgnoreCase(action)) {
                if (args.length < 4) {
                    ctx.sendMessage(Message.raw("Usage: /duel arena create <id>"));
                    return;
                }
                Player senderPlayer = ctx.senderAs(Player.class);
                if (senderPlayer == null) {
                    ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                    return;
                }
                String id = args[3].trim();
                if (id.isEmpty()) return;

                ArenaRepository.ArenaConfigFile file = (arenaRepository == null) ? null : arenaRepository.get();
                if (file == null) {
                    ctx.sendMessage(Message.raw("[DuelArena] Arena repository not ready."));
                    return;
                }

                for (Arena existing : file.arenas) {
                    if (existing != null && existing.id != null && existing.id.equalsIgnoreCase(id)) {
                        ctx.sendMessage(Message.raw("[DuelArena] Arena already exists: " + existing.id));
                        return;
                    }
                }

                Arena a = new Arena();
                a.id = id;
                a.world = senderPlayer.getWorld().getName();

                var tr = senderPlayer.getPlayerRef().getTransform();
                var pos = tr.getPosition();
                var rot = tr.getRotation();
                int bx = (int) Math.floor(pos.getX());
                int by = (int) Math.floor(pos.getY());
                int bz = (int) Math.floor(pos.getZ());

                a.bounds.min = new int[]{bx, by, bz};
                a.bounds.max = new int[]{bx, by, bz};
                a.spectatorBounds.min = new int[]{bx, by, bz};
                a.spectatorBounds.max = new int[]{bx, by, bz};

                a.spawnA.pos = new double[]{pos.getX(), pos.getY(), pos.getZ()};
                a.spawnB.pos = new double[]{pos.getX(), pos.getY(), pos.getZ()};
                a.spectatorSpawn.pos = new double[]{pos.getX(), pos.getY(), pos.getZ()};
                a.spawnA.rot = new float[]{rot.getX(), rot.getY(), rot.getZ()};
                a.spawnB.rot = new float[]{rot.getX(), rot.getY(), rot.getZ()};
                a.spectatorSpawn.rot = new float[]{rot.getX(), rot.getY(), rot.getZ()};

                a.normalize();
                file.arenas.add(a);
                arenaRepository.saveCurrent();
                if (arenaService != null) arenaService.reloadFromRepo();
                if (simpleClaimsVerifier != null) simpleClaimsVerifier.verifyArenasWarnOnly();

                ctx.sendMessage(Message.raw("[DuelArena] Created arena: " + id + " (edit bounds/spawns next)."));
                return;
            }

            if ("delete".equalsIgnoreCase(action)) {
                if (args.length < 4) {
                    ctx.sendMessage(Message.raw("Usage: /duel arena delete <id>"));
                    return;
                }
                String id = args[3].trim();
                if (id.isEmpty()) return;
                ArenaRepository.ArenaConfigFile file = (arenaRepository == null) ? null : arenaRepository.get();
                if (file == null || file.arenas == null) return;
                boolean removed = file.arenas.removeIf(a -> a != null && a.id != null && a.id.equalsIgnoreCase(id));
                if (removed) {
                    arenaRepository.saveCurrent();
                    if (arenaService != null) arenaService.reloadFromRepo();
                    if (simpleClaimsVerifier != null) simpleClaimsVerifier.verifyArenasWarnOnly();
                    ctx.sendMessage(Message.raw("[DuelArena] Deleted arena: " + id));
                } else {
                    ctx.sendMessage(Message.raw("[DuelArena] Arena not found: " + id));
                }
                return;
            }

            if ("setbounds".equalsIgnoreCase(action) || "setspectatorbounds".equalsIgnoreCase(action)) {
                if (args.length < 10) {
                    ctx.sendMessage(Message.raw("Usage: /duel arena " + action + " <id> <minX> <minY> <minZ> <maxX> <maxY> <maxZ>"));
                    return;
                }
                String id = args[3].trim();
                Arena a = findArenaById(id);
                if (a == null) {
                    ctx.sendMessage(Message.raw("[DuelArena] Arena not found: " + id));
                    return;
                }
                try {
                    int minX = Integer.parseInt(args[4]);
                    int minY = Integer.parseInt(args[5]);
                    int minZ = Integer.parseInt(args[6]);
                    int maxX = Integer.parseInt(args[7]);
                    int maxY = Integer.parseInt(args[8]);
                    int maxZ = Integer.parseInt(args[9]);

                    if ("setbounds".equalsIgnoreCase(action)) {
                        a.bounds.min = new int[]{minX, minY, minZ};
                        a.bounds.max = new int[]{maxX, maxY, maxZ};
                    } else {
                        a.spectatorBounds.min = new int[]{minX, minY, minZ};
                        a.spectatorBounds.max = new int[]{maxX, maxY, maxZ};
                    }
                    a.normalize();
                    arenaRepository.saveCurrent();
                    if (arenaService != null) arenaService.reloadFromRepo();
                    if (simpleClaimsVerifier != null) simpleClaimsVerifier.verifyArenasWarnOnly();
                    ctx.sendMessage(Message.raw("[DuelArena] Updated " + action + " for arena: " + a.id));
                } catch (Throwable t) {
                    ctx.sendMessage(Message.raw("[DuelArena] Invalid coordinates."));
                }
                return;
            }

            if ("setspawna".equalsIgnoreCase(action) || "setspawnb".equalsIgnoreCase(action) || "setspectatorspawn".equalsIgnoreCase(action)) {
                if (args.length < 4) {
                    ctx.sendMessage(Message.raw("Usage: /duel arena " + action + " <id>"));
                    return;
                }
                Player senderPlayer = ctx.senderAs(Player.class);
                if (senderPlayer == null) {
                    ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                    return;
                }
                String id = args[3].trim();
                Arena a = findArenaById(id);
                if (a == null) {
                    ctx.sendMessage(Message.raw("[DuelArena] Arena not found: " + id));
                    return;
                }
                var tr = senderPlayer.getPlayerRef().getTransform();
                var pos = tr.getPosition();
                var rot = tr.getRotation();

                if ("setspawna".equalsIgnoreCase(action)) {
                    a.spawnA.pos = new double[]{pos.getX(), pos.getY(), pos.getZ()};
                    a.spawnA.rot = new float[]{rot.getX(), rot.getY(), rot.getZ()};
                } else if ("setspawnb".equalsIgnoreCase(action)) {
                    a.spawnB.pos = new double[]{pos.getX(), pos.getY(), pos.getZ()};
                    a.spawnB.rot = new float[]{rot.getX(), rot.getY(), rot.getZ()};
                } else {
                    a.spectatorSpawn.pos = new double[]{pos.getX(), pos.getY(), pos.getZ()};
                    a.spectatorSpawn.rot = new float[]{rot.getX(), rot.getY(), rot.getZ()};
                }

                a.normalize();
                arenaRepository.saveCurrent();
                if (arenaService != null) arenaService.reloadFromRepo();
                if (simpleClaimsVerifier != null) simpleClaimsVerifier.verifyArenasWarnOnly();
                ctx.sendMessage(Message.raw("[DuelArena] Updated spawn for arena: " + a.id));
                return;
            }

            ctx.sendMessage(Message.raw("Usage: /duel arena <list|create|delete|setbounds|setspectatorbounds|setspawna|setspawnb|setspectatorspawn> ..."));
            return;
        }

        if ("reload".equalsIgnoreCase(sub)) {
            if (!isAdmin(ctx)) {
                ctx.sendMessage(Message.raw("[DuelArena] Missing permission: duel_arena.admin"));
                return;
            }
            if (configRepository != null) configRepository.reload();
            if (arenaService != null) arenaService.reloadFromRepo();
            if (shopRepository != null) shopRepository.reload();
            if (simpleClaimsVerifier != null) simpleClaimsVerifier.verifyArenasWarnOnly();
            ctx.sendMessage(Message.raw("[DuelArena] Reloaded config/arenas/shop."));
            return;
        }

        // Default: /duel <playerName>
        if (senderUuid == null || senderUuid.isBlank()) {
            ctx.sendMessage(Message.raw("[DuelArena] Players only."));
            return;
        }

        PlayerRef target = Universe.get().getPlayerByUsername(sub, NameMatching.STARTS_WITH_IGNORE_CASE);
        if (target == null || target.getUuid() == null) {
            ctx.sendMessage(Message.raw("[DuelArena] Player not found: " + sub));
            return;
        }
        String targetUuid = target.getUuid().toString();

        if (duelService == null) {
            ctx.sendMessage(Message.raw("[DuelArena] Not ready yet."));
            return;
        }

        DuelService.CreateOfferResult res = duelService.createOffer(senderUuid, targetUuid);
        if (res.status() == DuelService.CreateOfferStatus.SAME_PLAYER) {
            ctx.sendMessage(Message.raw("[DuelArena] You can't duel yourself."));
            return;
        }
        if (res.status() == DuelService.CreateOfferStatus.ALREADY_IN_DUEL) {
            ctx.sendMessage(Message.raw("[DuelArena] Either you or that player is already in a duel."));
            return;
        }
        if (res.session() == null) {
            ctx.sendMessage(Message.raw("[DuelArena] Failed to create duel offer."));
            return;
        }

        ctx.sendMessage(Message.raw("[DuelArena] Duel offer sent to " + target.getUsername() + "."));
        try {
            target.sendMessage(Message.raw("[DuelArena] " + ctx.sender().getDisplayName() + " invited you to a duel. Use the duel offer UI."));
        } catch (Throwable ignored) {
        }

        if (duelUiService != null) {
            duelUiService.requestRefreshForSession(res.session());
        }
    }

    private Arena findArenaById(String id) {
        if (id == null || id.isBlank()) return null;
        ArenaRepository.ArenaConfigFile file = (arenaRepository == null) ? null : arenaRepository.get();
        if (file == null || file.arenas == null) return null;
        for (Arena a : file.arenas) {
            if (a == null || a.id == null) continue;
            if (a.id.equalsIgnoreCase(id)) return a;
        }
        return null;
    }

    private static DuelShopConfig.ShopItem findShopItem(DuelShopConfig cfg, String id) {
        if (cfg == null || cfg.items == null) return null;
        if (id == null) return null;
        for (DuelShopConfig.ShopItem it : cfg.items) {
            if (it == null || it.id == null) continue;
            if (it.id.equalsIgnoreCase(id)) return it;
        }
        return null;
    }

    private static boolean isAdmin(CommandContext ctx) {
        if (ctx == null || ctx.sender() == null) return false;
        if (!ctx.isPlayer()) return true; // console
        return ctx.sender().hasPermission("duel_arena.admin");
    }

    private static String safe(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        return (fallback == null) ? "" : fallback;
    }

    private static String[] splitArgs(String input) {
        if (input == null) return new String[0];
        String s = input.trim();
        if (s.startsWith("/")) s = s.substring(1).trim();
        return s.isEmpty() ? new String[0] : s.split("\\s+");
    }

    private static String shortUuid(String uuid) {
        if (uuid == null) return "";
        if (uuid.length() <= 8) return uuid;
        return uuid.substring(0, 8);
    }
}
