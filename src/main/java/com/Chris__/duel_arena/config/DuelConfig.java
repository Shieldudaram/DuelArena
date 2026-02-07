package com.Chris__.duel_arena.config;

import java.util.ArrayList;
import java.util.List;

public final class DuelConfig {

    public int version = 1;

    public Ui ui = new Ui();
    public Duel duel = new Duel();
    public Rules rules = new Rules();
    public Staking staking = new Staking();
    public Elo elo = new Elo();
    public Tournament tournament = new Tournament();
    public SimpleClaims simpleClaims = new SimpleClaims();
    public Logging logging = new Logging();

    public static final class Ui {
        public String offerUiPath = "Pages/DuelArena/DuelOffer.ui";
        public String confirmUiPath = "Pages/DuelArena/DuelConfirm.ui";
        public String bracketUiPath = "Pages/DuelArena/TournamentBracket.ui";
    }

    public static final class Duel {
        public int requestTimeoutSeconds = 60;
        public int countdownSeconds = 3;
        public boolean koMode = true;
        public boolean forceNoTimeLimit = true;
        public int pointsPerWin = 10;
        public boolean allowSpectators = true;
    }

    public static final class Rules {
        public Defaults defaults = new Defaults();

        public List<String> armorSlotNames = new ArrayList<>(List.of("Armor 1", "Armor 2", "Armor 3", "Armor 4"));
        public List<String> consumableItemIds = new ArrayList<>();

        public static final class Defaults {
            public boolean ranked = true;
            public boolean allowMelee = true;
            public boolean allowProjectiles = true;
            public boolean allowConsumables = true;

            /** Fixed-length (4) array. If the runtime armor container differs, the plugin will clamp. */
            public boolean[] armorSlotAllowed = new boolean[]{true, true, true, true};
        }
    }

    public static final class Staking {
        public boolean enabled = false;
        public boolean allowItemStake = true;
        public boolean allowPointStake = true;

        public int maxPointStake = 100_000;
        public int maxItemEntries = 12;

        public int maxItemQuantityPerClick = Integer.MAX_VALUE;
        public List<String> stakeItemScope = new ArrayList<>(List.of("hotbar", "tools", "utility"));
    }

    public static final class Elo {
        public boolean enabled = true;
        public int initialRating = 1000;
        public int kFactor = 32;
    }

    public static final class Tournament {
        public boolean enabled = true;
        public List<Integer> sizes = new ArrayList<>(List.of(8, 16, 32));
        public boolean oneAtATime = true;
        public boolean autoStartWhenFull = true;
        public boolean lockOneArena = true;
        public boolean useOfferUiForMatches = true;

        public boolean forceRanked = true;
        public boolean cancelIfMatchOfferTimeout = true;

        public EntryFee entryFee = new EntryFee();
        public Rewards rewards = new Rewards();

        public static final class EntryFee {
            public boolean enabled = false;
            public String type = "points";
            public int points = 0;
        }

        public static final class Rewards {
            public int winnerPointsBonus = 50;
            public int runnerUpPointsBonus = 25;
            public int participationPoints = 0;

            public List<ItemReward> winnerItems = new ArrayList<>(List.of(defaultWinnerItem()));
        }

        public static final class ItemReward {
            public String itemId = "REPLACE_ME";
            public int amount = 1;
        }

        private static ItemReward defaultWinnerItem() {
            ItemReward r = new ItemReward();
            r.itemId = "REPLACE_ME";
            r.amount = 1;
            return r;
        }
    }

    public static final class SimpleClaims {
        public boolean verifyClaims = true;

        /**
         * "warn_only" for now. Other future values could be "disable_arena" or "block_all".
         */
        public String strictMode = "warn_only";
    }

    public static final class Logging {
        public boolean historyEnabled = true;
        public String historyFile = "duel_history.log";
    }
}

