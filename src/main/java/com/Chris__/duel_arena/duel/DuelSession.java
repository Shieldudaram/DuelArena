package com.Chris__.duel_arena.duel;

import com.Chris__.duel_arena.arena.Arena;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class DuelSession {

    public String id = "";

    public String playerAUuid = "";
    public String playerBUuid = "";

    public DuelStage stage = DuelStage.OFFER;

    public long createdAtMillis = 0L;
    public long stageStartedAtMillis = 0L;

    public boolean offerAcceptedA = false;
    public boolean offerAcceptedB = false;

    public boolean confirmAcceptedA = false;
    public boolean confirmAcceptedB = false;

    public DuelRules rules = new DuelRules();
    public DuelStake stakeA = new DuelStake();
    public DuelStake stakeB = new DuelStake();

    public boolean stakingEnabled = false;
    public boolean tournamentMatch = false;
    public String tournamentId = "";

    public Arena arena = null;
    public String arenaReservationKey = "";

    public long countdownEndsAtMillis = 0L;

    // Escrow (only when stakingEnabled)
    public boolean escrowed = false;
    public int escrowPointsA = 0;
    public int escrowPointsB = 0;
    public List<ItemStack> escrowItemsA = new ArrayList<>();
    public List<ItemStack> escrowItemsB = new ArrayList<>();

    // Finalization
    public DuelCancelReason cancelReason = null;
    public DuelEndReason endReason = null;
    public String winnerUuid = "";
    public String loserUuid = "";

    public boolean involves(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        return uuid.equals(playerAUuid) || uuid.equals(playerBUuid);
    }

    public String other(String uuid) {
        if (uuid == null) return null;
        if (uuid.equals(playerAUuid)) return playerBUuid;
        if (uuid.equals(playerBUuid)) return playerAUuid;
        return null;
    }

    public boolean isActiveCombat() {
        return stage == DuelStage.COUNTDOWN || stage == DuelStage.IN_PROGRESS;
    }
}

