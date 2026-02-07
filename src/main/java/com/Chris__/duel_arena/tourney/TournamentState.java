package com.Chris__.duel_arena.tourney;

import com.Chris__.duel_arena.arena.Arena;

import java.util.ArrayList;
import java.util.List;

public final class TournamentState {

    public enum Status {
        LOBBY,
        RUNNING,
        COMPLETED,
        CANCELED
    }

    public static final class Match {
        public int roundNumber = 1;
        public int matchNumber = 1;
        public String aUuid = "";
        public String bUuid = "";
        public String winnerUuid = "";
        public String loserUuid = "";
        public String duelSessionId = "";
    }

    public static final class Round {
        public int roundNumber = 1;
        public List<Match> matches = new ArrayList<>();
    }

    public String id = "";
    public Status status = Status.LOBBY;
    public int size = 8;

    public List<String> entrants = new ArrayList<>();

    public Arena arena = null;
    public String arenaReservationKey = "";

    public int currentRoundNumber = 0;
    public int currentMatchIndex = -1;
    public List<String> currentRoundPlayers = new ArrayList<>();
    public List<String> nextRoundPlayers = new ArrayList<>();
    public List<Round> rounds = new ArrayList<>();

    public String championUuid = "";
    public String runnerUpUuid = "";

    public int entryFeePoints = 0;
    public List<String> entryFeePaidUuids = new ArrayList<>();

    public boolean hasEntrant(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        for (String e : entrants) {
            if (uuid.equals(e)) return true;
        }
        return false;
    }
}

