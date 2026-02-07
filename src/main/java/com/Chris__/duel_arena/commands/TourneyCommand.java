package com.Chris__.duel_arena.commands;

import com.Chris__.duel_arena.tourney.TournamentService;
import com.Chris__.duel_arena.tourney.TournamentState;
import com.Chris__.duel_arena.ui.DuelUiService;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;

public final class TourneyCommand extends CommandBase {

    private static final Message MSG_USAGE = Message.raw("Usage: /tourney <join size|leave|start|cancel|status|bracket>");

    private final TournamentService tournamentService;
    private final DuelUiService duelUiService;

    public TourneyCommand(TournamentService tournamentService, DuelUiService duelUiService) {
        super("tourney", "Duel arena tournaments.");
        this.addAliases("tournament");
        this.setAllowsExtraArguments(true);
        this.setPermissionGroup(GameMode.Adventure);
        this.tournamentService = tournamentService;
        this.duelUiService = duelUiService;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String[] args = splitArgs(ctx.getInputString());
        if (args.length < 2) {
            ctx.sendMessage(MSG_USAGE);
            return;
        }

        String senderUuid = (ctx.sender() == null || ctx.sender().getUuid() == null) ? null : ctx.sender().getUuid().toString();
        String action = args[1];

        if ("status".equalsIgnoreCase(action)) {
            TournamentState t = (tournamentService == null) ? null : tournamentService.getActive();
            if (t == null) {
                ctx.sendMessage(Message.raw("[DuelArena] No active tournament."));
            } else {
                ctx.sendMessage(Message.raw("[DuelArena] Tournament " + t.id + " status=" + t.status + " size=" + t.size + " entrants=" + t.entrants.size()));
            }
            return;
        }

        if ("bracket".equalsIgnoreCase(action) || "ui".equalsIgnoreCase(action)) {
            if (senderUuid == null || senderUuid.isBlank()) {
                ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                return;
            }
            if (duelUiService != null) duelUiService.requestBracket(senderUuid);
            ctx.sendMessage(Message.raw("[DuelArena] Opening bracket..."));
            return;
        }

        if ("join".equalsIgnoreCase(action)) {
            if (senderUuid == null || senderUuid.isBlank()) {
                ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                return;
            }
            if (args.length < 3) {
                ctx.sendMessage(Message.raw("Usage: /tourney join <size>"));
                return;
            }
            int size;
            try {
                size = Integer.parseInt(args[2]);
            } catch (Throwable ignored) {
                ctx.sendMessage(Message.raw("[DuelArena] Invalid size."));
                return;
            }

            TournamentService.JoinResult res = (tournamentService == null) ? null : tournamentService.join(senderUuid, size);
            if (res == null) {
                ctx.sendMessage(Message.raw("[DuelArena] Tournament system not ready."));
                return;
            }

            switch (res.status()) {
                case JOINED -> ctx.sendMessage(Message.raw("[DuelArena] Joined tournament lobby (" + size + ")."));
                case ALREADY_JOINED -> ctx.sendMessage(Message.raw("[DuelArena] You're already in the tournament lobby."));
                case FULL -> ctx.sendMessage(Message.raw("[DuelArena] Tournament lobby is full."));
                case TOURNAMENT_RUNNING -> ctx.sendMessage(Message.raw("[DuelArena] Tournament already running."));
                case DIFFERENT_SIZE_ACTIVE -> ctx.sendMessage(Message.raw("[DuelArena] A tournament lobby is already active with a different size."));
                case NOT_ENOUGH_POINTS -> ctx.sendMessage(Message.raw("[DuelArena] Not enough points for entry fee."));
                case NOT_ENABLED -> ctx.sendMessage(Message.raw("[DuelArena] Tournaments are disabled."));
                case INVALID_SIZE -> ctx.sendMessage(Message.raw("[DuelArena] Invalid tournament size."));
            }

            return;
        }

        if ("leave".equalsIgnoreCase(action)) {
            if (senderUuid == null || senderUuid.isBlank()) {
                ctx.sendMessage(Message.raw("[DuelArena] Players only."));
                return;
            }
            boolean ok = tournamentService != null && tournamentService.leave(senderUuid);
            ctx.sendMessage(ok ? Message.raw("[DuelArena] Left tournament lobby.") : Message.raw("[DuelArena] You're not in the tournament lobby (or it already started)."));
            return;
        }

        if ("start".equalsIgnoreCase(action)) {
            boolean ok = tournamentService != null && tournamentService.startIfFull();
            ctx.sendMessage(ok ? Message.raw("[DuelArena] Starting tournament...") : Message.raw("[DuelArena] Tournament not ready (need full lobby)."));
            return;
        }

        if ("cancel".equalsIgnoreCase(action)) {
            if (!isAdmin(ctx)) {
                ctx.sendMessage(Message.raw("[DuelArena] Missing permission: duel_arena.admin"));
                return;
            }
            boolean ok = tournamentService != null && tournamentService.cancelActive();
            ctx.sendMessage(ok ? Message.raw("[DuelArena] Tournament canceled.") : Message.raw("[DuelArena] No active tournament."));
            return;
        }

        ctx.sendMessage(MSG_USAGE);
    }

    private static boolean isAdmin(CommandContext ctx) {
        if (ctx == null || ctx.sender() == null) return false;
        if (!ctx.isPlayer()) return true; // console
        return ctx.sender().hasPermission("duel_arena.admin");
    }

    private static String[] splitArgs(String input) {
        if (input == null) return new String[0];
        String s = input.trim();
        if (s.startsWith("/")) s = s.substring(1).trim();
        return s.isEmpty() ? new String[0] : s.split("\\s+");
    }
}

