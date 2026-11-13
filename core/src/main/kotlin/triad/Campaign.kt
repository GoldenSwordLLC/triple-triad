package triad.core

import kotlin.math.abs

/** The Card Circuit — single-player campaign data.
 *
 *  Sixteen authored opponents. Every fight is a fixed 5-card deck (learnable),
 *  the first win pays a scripted reward set that arms the player for the NEXT
 *  chapter, and replays drop one random card from the opponent's pool (their
 *  battle deck + themed neighbors). Invariants, test-enforced in CampaignTest:
 *    1. starter ∪ rewards ∪ pools == the whole 110-card catalog (no card ever
 *       requires multiplayer).
 *    2. every chapter is winnable by an AI seat holding exactly starter +
 *       rewards-through-the-previous-chapter (the solvability guarantee).
 */
object Campaign {

    val STARTER: List<String> = listOf(
        "geezard", "funguar", "bitebug", "redbat", "blobra", "gayla", "gesper",
        "fastitocalonf", "bloodsoul", "caterchipillar", "cockatrice", "caterchipillar")

    data class Opponent(
        val chapter: Int,
        val name: String,
        val epithet: String,
        val inspiredBy: String,
        val intro: String,
        val defeat: String,
        val deckIds: List<String>,
        val same: Boolean = false,
        val plus: Boolean = false,
        val sameWall: Boolean = false,
        val elemental: Boolean = false,
        val rewardIds: List<String>,
        val poolIds: List<String>,
        val aiBudgetMs: Long,
    ) {
        val rules: Rules get() = Rules(same = same, plus = plus, sameWall = sameWall, elemental = elemental)
    }

    val OPPONENTS: List<Opponent> = listOf(
        Opponent(1, "Pip", "the dock kid", "the hall-circling boy",
            "You wanna play? Loser buys the fish bait!", "Aw, fishicles.",
            listOf("geezard", "gayla", "gesper", "bloodsoul", "fastitocalonf"),
            rewardIds = listOf("mesmerize", "grandmantis"),
            poolIds = listOf("geezard", "gayla", "gesper", "bloodsoul", "fastitocalonf", "grat", "buel", "jelleye"),
            aiBudgetMs = 150),
        Opponent(2, "Bram", "the pub keeper", "the Dollet pub owner",
            "House rules, kid: I pour, I shuffle, I win.", "First round's on me.",
            listOf("funguar", "buel", "grat", "glacialeye", "jelleye"),
            rewardIds = listOf("belhelmel", "thrustaevis", "forbidden"),
            poolIds = listOf("funguar", "buel", "grat", "glacialeye", "jelleye", "anacondaur", "mesmerize", "grandmantis", "creeps"),
            aiBudgetMs = 150),
        Opponent(3, "Nadia", "the cafeteria socialite", "the Trepie groupies",
            "Everyone plays at my table, darling.", "Whatever. It's just a card game.",
            listOf("jelleye", "anacondaur", "glacialeye", "buel", "tonberry"),
            rewardIds = listOf("triface", "snowlion", "cactuar"),
            poolIds = listOf("grendel", "anacondaur", "glacialeye", "forbidden", "tonberry", "belhelmel", "thrustaevis", "creeps", "mesmerize"),
            aiBudgetMs = 150),
        Opponent(4, "Marta", "the boarding-house mom", "Ma Dincht",
            "Sit. Eat. Shuffle.", "Oh, sweetheart — well played.",
            listOf("ochu", "armadodo", "tonberry", "geezard", "redbat"),
            rewardIds = listOf("deathclaw", "bluedragon", "torama", "hexadragon"),
            poolIds = listOf("ochu", "armadodo", "fastitocalon", "tonberry", "geezard", "snowlion", "sam08g", "abyssworm"),
            aiBudgetMs = 150),
        Opponent(5, "Sable", "the gambler", "the man in black",
            "Double or nothing, freshman.", "Read 'em and weep. I am.",
            listOf("trexaur", "blitz", "forbidden", "ochu", "tonberry"),
            elemental = true,
            rewardIds = listOf("irongiant", "behemoth", "elastoid", "wendigo"),
            poolIds = listOf("trexaur", "blitz", "forbidden", "ochu", "tonberry", "vysage", "bomb", "imp"),
            aiBudgetMs = 300),
        Opponent(6, "Dr. Vell", "the archivist", "Dr. Odine",
            "Fascinating! Your deck — let me dissect it.", "Fascinating! You WIN. How?",
            listOf("adamantoise", "gim47n", "rubydragon", "vysage", "turtapod"),
            elemental = true,
            rewardIds = listOf("chimera", "malboro", "elnoyle", "tonberryking", "wedgebiggs"),
            poolIds = listOf("adamantoise", "gim47n", "rubydragon", "vysage", "turtapod", "bomb", "blitz", "behemoth"),
            aiBudgetMs = 300),
        Opponent(7, "Iris", "the harbor mayor", "Mayor Dobe",
            "FH plays by OUR rules. Learn them.", "The harbor bows to you.",
            listOf("fujinraijin", "elvoret", "gerogero", "grendel", "anacondaur"),
            same = true,
            rewardIds = listOf("xatm092", "iguion", "abadon", "trauma", "krysta"),
            poolIds = listOf("fujinraijin", "elvoret", "granaldo", "oilboyle", "gerogero", "xatm092", "shumitribe", "chimera"),
            aiBudgetMs = 300),
        Opponent(8, "Jinx", "the junk-shop magician", "Joker",
            "Every game's a scam. Some just favor you.", "A clean loss. How novel.",
            listOf("gerogero", "oilboyle", "fujinraijin", "granaldo", "iguion"),
            elemental = true,
            rewardIds = listOf("propagator", "jumbocactuar", "tripoint", "mobiletype8", "gargantua"),
            poolIds = listOf("gerogero", "oilboyle", "fujinraijin", "granaldo", "iguion", "pupu", "malboro", "elnoyle", "wedgebiggs"),
            aiBudgetMs = 300),
        Opponent(9, "Sgt. Ash", "the drill corporal", "Xu",
            "Drop and give me five. Cards.", "Dismissed. Well earned.",
            listOf("abadon", "trauma", "shumitribe", "krysta", "elvoret"),
            same = true, elemental = true,
            rewardIds = listOf("sphinxara", "tiamat", "redgiant", "bgh251f2", "ultimaweapon"),
            poolIds = listOf("abadon", "trauma", "shumitribe", "krysta", "elvoret", "iguion", "tonberryking", "irongiant"),
            aiBudgetMs = 500),
        Opponent(10, "Vex & Vyl", "the twins", "the Diamond duo",
            "Two of us. One of you. Numbers, darling.", "Impossible! We SHARE a brain!",
            listOf("jumbocactuar", "catoblepas", "gargantua", "granaldo", "elvoret"),
            plus = true,
            rewardIds = listOf("chubbychocobo", "angelo", "gilgamesh", "quezacotl", "shiva"),
            poolIds = listOf("jumbocactuar", "catoblepas", "gargantua", "granaldo", "elvoret", "propagator", "tripoint", "behemoth"),
            aiBudgetMs = 400),
        Opponent(11, "Clover", "the club loyalist", "CC Club",
            "For the Club! For the LUCK of it!", "The Club will hear of this!",
            listOf("sphinxara", "bgh251f2", "redgiant", "ultimaweapon", "catoblepas"),
            plus = true,
            rewardIds = listOf("ifrit", "siren", "sacred", "minotaur", "chicobo"),
            poolIds = listOf("sphinxara", "bgh251f2", "redgiant", "ultimaweapon", "catoblepas", "jumbocactuar", "tripoint", "gim47n", "rubydragon"),
            aiBudgetMs = 500),
        Opponent(12, "Headmaster Crane", "the dean of the Garden", "Headmaster Cid",
            "Show me what my academy taught you.", "Taught you well, it did.",
            listOf("gargantua", "mobiletype8", "sphinxara", "bgh251f2", "tripoint"),
            same = true, plus = true,
            rewardIds = listOf("carbuncle", "diablos", "leviathan", "odin", "pandemona"),
            poolIds = listOf("gargantua", "mobiletype8", "sphinxara", "bgh251f2", "tripoint", "redgiant", "tiamat", "malboro"),
            aiBudgetMs = 500),
        Opponent(13, "Cassia", "the bodyguard", "CC Spade",
            "The Queen's door. I'm the lock.", "…The Queen will hear about this.",
            listOf("minimog", "cerberus", "phoenix", "chubbychocobo", "chicobo"),
            same = true, plus = true, elemental = true,
            rewardIds = listOf("alexander", "bahamut", "eden", "ward", "kiros"),
            poolIds = listOf("minimog", "cerberus", "phoenix", "chubbychocobo", "chicobo", "angelo", "gilgamesh", "odin", "diablos"),
            aiBudgetMs = 800),
        Opponent(14, "General Marek", "the iron wall", "General Caraway",
            "War is cards. I never lose wars.", "…A tactical withdrawal.",
            listOf("cerberus", "phoenix", "odin", "leviathan", "alexander"),
            same = true, plus = true, sameWall = true, elemental = true,
            rewardIds = listOf("laguna", "selphie", "quistis", "irvine", "zell"),
            poolIds = listOf("cerberus", "phoenix", "odin", "leviathan", "alexander", "carbuncle", "pandemona", "eden", "bahamut", "ward", "kiros"),
            aiBudgetMs = 800),
        Opponent(15, "Madame Vess", "the Card Queen", "the Queen of Cards",
            "I collect. You are simply the next collection.", "Keep it. Kings are made of losses.",
            listOf("edea", "seifer", "squall", "rinoa", "doomtrain"),
            same = true, plus = true, sameWall = true, elemental = true,
            rewardIds = listOf("doomtrain", "rinoa", "seifer"),
            poolIds = listOf("edea", "seifer", "squall", "rinoa", "doomtrain", "bahamut", "eden", "alexander", "ward"),
            aiBudgetMs = 1200),
        Opponent(16, "Rex", "the Card Monarch", "Card Master King",
            "The Circuit ends where it began. With me.", "Then the crown is yours. Wear it loudly.",
            listOf("squall", "seifer", "edea", "rinoa", "bahamut"),
            same = true, plus = true, sameWall = true, elemental = true,
            rewardIds = listOf("squall", "edea"),
            poolIds = listOf("squall", "seifer", "edea", "rinoa", "bahamut", "zell", "kiros", "laguna", "selphie", "quistis", "irvine", "ward"),
            aiBudgetMs = 1400),
    )

    /** Cards the player owns for chapter N (starter + every earlier first-win reward). */
    fun playerArsenal(chapter: Int, catalog: Map<String, CardDef>): List<CardDef> =
        (STARTER + OPPONENTS.filter { it.chapter < chapter }.flatMap { it.rewardIds })
            .mapNotNull { catalog[it] }

    /** The player's strongest legal five — stands in for a human picking their deck. */
    fun campaignHand(arsenal: List<CardDef>): List<CardDef> =
        arsenal.distinctBy { it.id }
            .sortedByDescending { it.top + it.right + it.bottom + it.left }
            .take(5)

    /** Deterministic match state for a chapter. The PLAYER ALWAYS MOVES FIRST —
     *  you challenged them — which is also the linchpin of the solvability
     *  guarantee: this engine's first mover wins ~65% of equal-deck games, so a
     *  coin flip would decide chapters more than the cards would. Elemental
     *  cells still roll 1-in-5 off the chapter seed. */
    fun campaignState(opponent: Opponent, catalog: Map<String, CardDef>,
                      playerHand: List<CardDef>, seed: Long): GameState {
        val r = SplitMix64(seed)
        val cells = List(9) {
            if (opponent.elemental && r.nextInt(5) == 0)
                Cell(element = Element.entries[r.nextInt(9) + 1])
            else Cell()
        }
        fun pick5(ids: List<String>): List<CardDef> {
            val pool = ids.map { catalog.getValue(it) }.toMutableList()
            val out = ArrayList<CardDef>(5)
            while (out.size < 5) out.add(pool.removeAt(r.nextInt(pool.size)))
            return out
        }
        fun pick5Cards(cards: List<CardDef>): List<CardDef> {
            val pool = cards.toMutableList()
            val out = ArrayList<CardDef>(5)
            while (out.size < 5) out.add(pool.removeAt(r.nextInt(pool.size)))
            return out
        }
        return GameState(
            board = Board(cells),
            blueHand = pick5Cards(playerHand),  // player seat
            redHand = pick5(opponent.deckIds),  // opponent seat
            turn = Owner.blue,                  // the player is always the challenger
            rules = opponent.rules,
        )
    }
}
