package triad.core

/** The Card Circuit — single-player campaign data.
 *
 *  Sixteen chapters, 51 fights: 16 authored bosses plus 35 lesser ranks
 *  (1 lesser in chapters 1-4, 2 in chapters 5-9, 3 in chapters 10-16).
 *  Every fight is a fixed 5-card deck; first clears pay the campaign's cards
 *  in pieces — the boss of a chapter always holds its key cards, so each
 *  chapter is a mini-arc that arms you for the next one.
 *
 *  Invariants, test-enforced in CampaignTest:
 *    1. starter ∪ every fight's first-clear reward == the whole 110-card
 *       catalog (no card ever requires multiplayer).
 *    2. every fight is winnable by an AI seat holding exactly starter + all
 *       rewards from earlier fights (the solvability guarantee).
 *  The player always moves first — you challenged them.
 */
object Campaign {

    val STARTER: List<String> = listOf(
        "geezard", "funguar", "bitebug", "redbat", "blobra", "gayla", "gesper",
        "fastitocalonf", "bloodsoul", "caterchipillar", "cockatrice", "caterchipillar")

    data class Fight(
        val chapter: Int,
        val name: String,
        val epithet: String,
        val inspiredBy: String,
        val intro: String,
        val defeat: String,
        val deckIds: List<String>,
        val rewardIds: List<String>,
        val isBoss: Boolean = false,
        val same: Boolean = false,
        val plus: Boolean = false,
        val sameWall: Boolean = false,
        val elemental: Boolean = false,
        val combo: Boolean = false,
        val aiBudgetMs: Long,
    ) {
        val rules: Rules get() = Rules(same = same, plus = plus, sameWall = sameWall, elemental = elemental, combo = combo)
    }

    private fun f(
        chapter: Int, name: String, epithet: String, inspiredBy: String,
        intro: String, defeat: String, deck: List<String>, reward: List<String>,
        boss: Boolean = false,
        same: Boolean = false, plus: Boolean = false, wall: Boolean = false, elem: Boolean = false,
        combo: Boolean = false, budget: Long,
    ) = Fight(chapter, name, epithet, inspiredBy, intro, defeat, deck, reward,
              boss, same, plus, wall, elem, combo, budget)

    val FIGHTS: List<Fight> = listOf(
        // ---- CHAPTER 1 · BALAMB DOCKS (1 lesser + boss, no rules) ----
        f(1, "Skip", "the class-skipper", "the student skipping class",
          "Skipped class for THIS. Best decision ever.", "Worth every detention.",
          listOf("funguar", "bitebug", "redbat", "blobra", "cockatrice"),
          listOf("grat", "buel"), budget = 150),
        f(1, "Pip", "the dock kid", "the hall-circling boy",
          "You wanna play? Loser buys the fish bait!", "Aw, fishicles.",
          listOf("geezard", "gayla", "gesper", "bloodsoul", "fastitocalonf"),
          listOf("mesmerize", "grandmantis", "jelleye"), boss = true, budget = 150),
        // ---- CHAPTER 2 · DOLLET PUB ----
        f(2, "Watts", "the forest owl", "Watts",
          "Resistance business can wait. Shuffle up.", "Don't tell my commander.",
          listOf("geezard", "gayla", "gesper", "fastitocalonf", "bloodsoul"),
          listOf("anacondaur", "belhelmel"), budget = 150),
        f(2, "Sarin", "the barfly", "the pub regular",
          "I live here. You're just passing through.", "Drinks on the house. Once.",
          listOf("geezard", "redbat", "blobra", "gayla", "bloodsoul"),
          listOf("creeps", "glacialeye"), budget = 150),
        f(2, "Bram", "the pub keeper", "the Dollet pub owner",
          "House rules, kid: I pour, I shuffle, I win.", "First round's on me.",
          listOf("funguar", "buel", "grat", "glacialeye", "jelleye"),
          listOf("thrustaevis", "forbidden"), boss = true, budget = 150),
        // ---- CHAPTER 3 · GARDEN HALL ----
        f(3, "Jack", "the CC initiate", "Card Club's Jack",
          "First rule of the Club: there are no rules yet.", "Okay, ONE rule: you win.",
          listOf("funguar", "bitebug", "caterchipillar", "grat", "armadodo"),
          listOf("triface"), budget = 150),
        f(3, "Rina", "the trepie", "Trepie Groupie #2",
          "I only play cards with feeling.", "That was... actually beautiful.",
          listOf("funguar", "gesper", "fastitocalonf", "cockatrice", "creeps"),
          listOf("snowlion", "tonberry"), budget = 150),
        f(3, "Nadia", "the cafeteria socialite", "the Trepie groupies",
          "Everyone plays at my table, darling.", "Whatever. It's just a card game.",
          listOf("jelleye", "anacondaur", "glacialeye", "buel", "tonberry"),
          listOf("cactuar", "grendel"), boss = true, budget = 150),
        // ---- CHAPTER 4 · BALAMB HOUSES ----
        f(4, "Dorn", "the dockworker", "the Galbadia dockhand",
          "Union rules. I set the stakes.", "Fine. Take the lot.",
          listOf("blobra", "grat", "buel", "glacialeye", "jelleye"),
          listOf("deathclaw", "armadodo", "fastitocalon", "ochu"), budget = 150),
        f(4, "Marta", "the boarding-house mom", "Ma Dincht",
          "Sit. Eat. Shuffle.", "Oh, sweetheart — well played.",
          listOf("ochu", "armadodo", "tonberry", "geezard", "redbat"),
          listOf("bluedragon", "torama", "hexadragon", "sam08g", "abyssworm"),
          boss = true, budget = 150),
        // ---- CHAPTER 5 · GALBADIA BACK ROOM (ELEMENTAL debuts) ----
        f(5, "Carlo", "the card shark", "the arcade hustler",
          "Fresh meat for the felt.", "Cleared out. By a tourist.",
          listOf("mesmerize", "belhelmel", "thrustaevis", "grendel", "vysage"),
          listOf("vysage", "bomb", "blitz", "turtapod", "imp"), budget = 150),
        f(5, "Martine", "the Galbadia dean", "Headmaster Martine",
          "My academy educates. Consider this tuition.", "Scholarship granted.",
          listOf("caterchipillar", "anacondaur", "grandmantis", "fastitocalon", "snowlion"),
          listOf("trexaur", "adamantoise", "elastoid"), elem = true, budget = 150),
        f(5, "Sable", "the gambler", "the man in black",
          "Double or nothing.", "Read 'em and weep. I am.",
          listOf("trexaur", "blitz", "forbidden", "ochu", "tonberry"),
          listOf("irongiant", "behemoth", "wendigo"), elem = true, boss = true, budget = 300),
        // ---- CHAPTER 6 · ESTHAR ARCHIVE ----
        f(6, "Piet", "the lunar scientist", "Piet",
          "My hypothesis: you lose. Let's test it.", "Data conclusive. You win.",
          listOf("forbidden", "triface", "ochu", "sam08g", "irongiant"),
          listOf("gim47n", "rubydragon"), elem = true, budget = 150),
        f(6, "Lys", "Odine's aide", "Dr. Odine's assistant",
          "Dr. Vell says beat you! I say easy!", "I am NOT telling Dr. Odine.",
          listOf("deathclaw", "cactuar", "tonberry", "abyssworm", "wendigo"),
          listOf("elnoyle", "wedgebiggs"), elem = true, budget = 150),
        f(6, "Dr. Vell", "the archivist", "Dr. Odine",
          "Fascinating! Your deck — let me dissect it.", "Fascinating! You WIN. How?",
          listOf("adamantoise", "gim47n", "rubydragon", "vysage", "turtapod"),
          listOf("chimera", "malboro", "tonberryking"), elem = true, boss = true, budget = 300),
        // ---- CHAPTER 7 · FH HARBOR (SAME debuts) ----
        f(7, "Flo", "the mayor's wife", "Flo",
          "My husband plays games of state. I play to WIN.", "Oh-ho! Dobe never does that!",
          listOf("buel", "turtapod", "trexaur", "torama", "pupu"),
          listOf("xatm092", "shumitribe", "granaldo"), budget = 150),
        f(7, "Rico", "the maintenance kid", "the FH lift operator",
          "I fix the elevators. I break faces. Card faces.", "Gears and gratitude!",
          listOf("geezard", "glacialeye", "bomb", "blitz", "bluedragon"),
          listOf("iguion", "trauma", "fujinraijin"), same = true, combo = true, budget = 150),
        f(7, "Iris", "the harbor mayor", "Mayor Dobe",
          "This harbor plays by OUR rules. Learn them.", "The harbor bows to you.",
          listOf("fujinraijin", "elvoret", "gerogero", "grendel", "anacondaur"),
          listOf("abadon", "krysta"), same = true, combo = true, boss = true, budget = 300),
        // ---- CHAPTER 8 · THE JUNK SHOP ----
        f(8, "Gimmer", "the tinker", "the junk-shop regular",
          "Everything's for sale. Even your losses.", "Bah. Take your trash.",
          listOf("mesmerize", "imp", "adamantoise", "hexadragon", "tonberryking"),
          listOf("propagator", "pupu", "oilboyle"), elem = true, budget = 150),
        f(8, "Zone", "the supply officer", "Zone",
          "You get supplies. I get your cards. Fair.", "Keep the magazine. And my dignity.",
          listOf("belhelmel", "thrustaevis", "behemoth", "malboro", "rubydragon"),
          listOf("gerogero"), elem = true, budget = 150),
        f(8, "Jinx", "the junk-shop magician", "Joker",
          "Every game's a scam. Some just favor you.", "A clean loss. How novel.",
          listOf("gerogero", "oilboyle", "fujinraijin", "granaldo", "iguion"),
          listOf("jumbocactuar", "mobiletype8"), elem = true, boss = true, budget = 300),
        // ---- CHAPTER 9 · GARDEN DRILLS (SAME + ELEMENTAL) ----
        f(9, "Nida", "the ace cadet", "Cadet Nida",
          "Simulations are over. This one counts.", "Logged. And respected.",
          listOf("chimera", "elastoid", "gim47n", "elnoyle", "gerogero"),
          listOf("sphinxara"), budget = 150),
        f(9, "Mox", "the drill partner", "the sparring partner",
          "I've never lost twice in a row. Watch this.", "There's a first time for everything.",
          listOf("anacondaur", "wedgebiggs", "fujinraijin", "iguion", "oilboyle"),
          listOf("tiamat", "catoblepas"), same = true, combo = true, budget = 150),
        f(9, "Sgt. Ash", "the drill corporal", "Xu",
          "Drop and give me five. Cards.", "Dismissed. Well earned.",
          listOf("abadon", "trauma", "shumitribe", "krysta", "elvoret"),
          listOf("ultimaweapon"), same = true, elem = true, combo = true, boss = true, budget = 500),
        // ---- CHAPTER 10 · THE CLUB COURT (PLUS debuts) ----
        f(10, "Fenn", "club initiate", "the Club's newest",
          "Initiation game. Try to be gentle.", "Gentle?! I demand a rematch!",
          listOf("creeps", "xatm092", "abadon", "propagator", "tiamat"),
          listOf("chubbychocobo"), plus = true, combo = true, budget = 150),
        f(10, "Ora", "club initiate", "the Club's other newest",
          "Fenn went easy. I won't.", "Not easy. Just better.",
          listOf("bitebug", "trauma", "shumitribe", "krysta", "tripoint"),
          listOf("angelo", "elvoret"), plus = true, combo = true, budget = 150),
        f(10, "Deuce", "club enforcer", "the Club's muscle",
          "The Diamond sisters asked for a warm-up. That's you.", "Warm-up complete. They'll crush you.",
          listOf("redbat", "jelleye", "mobiletype8", "sphinxara", "bgh251f2"),
          listOf("gilgamesh"), plus = true, combo = true, budget = 150),
        f(10, "Vex & Vyl", "the twins", "the Diamond duo",
          "Two of us. One of you. Numbers, darling.", "Impossible! We SHARE a brain!",
          listOf("jumbocactuar", "catoblepas", "gargantua", "granaldo", "elvoret"),
          listOf("quezacotl", "shiva"), plus = true, combo = true, boss = true, budget = 400),
        // ---- CHAPTER 11 · THE CLUB HALL ----
        f(11, "Club", "the club rank", "CC Club",
          "Rank means everything. I have it. You don't.", "Rank demoted. Respect earned.",
          listOf("vysage", "elvoret", "granaldo", "jumbocactuar", "gargantua"),
          listOf("ifrit"), plus = true, combo = true, budget = 150),
        f(11, "Rone", "club enforcer", "the Club's shield",
          "Membership has privileges. You have problems.", "Consider us even.",
          listOf("grendel", "triface", "tonberryking", "gargantua", "tiamat"),
          listOf("siren"), plus = true, combo = true, budget = 150),
        f(11, "Pia", "club collector", "the Club's archivist",
          "I collect rare wins. Yours will be common.", "Add it to the archive. Painfully.",
          listOf("grandmantis", "forbidden", "irongiant", "abadon", "propagator"),
          listOf("sacred"), plus = true, combo = true, budget = 150),
        f(11, "Clover", "the club loyalist", "CC Club",
          "For the Club! For the LUCK of it!", "The Club will hear of this!",
          listOf("sphinxara", "bgh251f2", "redgiant", "ultimaweapon", "catoblepas"),
          listOf("minotaur", "chicobo"), plus = true, combo = true, boss = true, budget = 500),
        // ---- CHAPTER 12 · THE ACADEMY ----
        f(12, "Instructor Vin", "the strict instructor", "Garden faculty",
          "Pop quiz. Your deck is the answer sheet.", "B-minus. For effort.",
          listOf("ochu", "jumbocactuar", "redgiant", "catoblepas", "ultimaweapon"),
          listOf("carbuncle"), same = true, combo = true, budget = 150),
        f(12, "Proctor Dalsh", "the exam proctor", "Proctor Dalsh",
          "See me after class. Bring your cards.", "No detention. Astonishing.",
          listOf("armadodo", "trexaur", "trauma", "redgiant", "ultimaweapon"),
          listOf("diablos"), same = true, plus = true, combo = true, budget = 150),
        f(12, "Rei", "the dorm monitor", "the dorm supervisor",
          "Lights out means cards out. Deal them.", "Fine. Lights on. Play on.",
          listOf("fastitocalon", "sam08g", "fujinraijin", "shumitribe", "catoblepas"),
          listOf("leviathan"), same = true, plus = true, combo = true, budget = 150),
        f(12, "Headmaster Crane", "the dean of the Garden", "Headmaster Cid",
          "Show me what my academy taught you.", "Taught you well, it did.",
          listOf("gargantua", "mobiletype8", "sphinxara", "bgh251f2", "tripoint"),
          listOf("pandemona"), same = true, plus = true, combo = true, boss = true, budget = 500),
        // ---- CHAPTER 13 · THE QUEEN'S COURT ----
        f(13, "Usher Moen", "the court usher", "the Queen's court",
          "Her Majesty sees no one. I see everyone.", "Presented and dismissed.",
          listOf("snowlion", "angelo", "gilgamesh", "shiva", "carbuncle"),
          listOf("alexander", "cerberus"), same = true, plus = true, elem = true, combo = true, budget = 150),
        f(13, "Count Zefer", "the court gambler", "the Deling aristocracy",
          "Aristocracy of cards, my friend. Old money.", "New money beats old. Ask any revolution.",
          listOf("gayla", "quezacotl", "ifrit", "siren", "eden"),
          listOf("bahamut", "phoenix"), same = true, plus = true, elem = true, combo = true, budget = 150),
        f(13, "Lady Mireille", "the court collector", "the Deling salon set",
          "I only complete sets. You complete mine.", "How vulgar. How impressive.",
          listOf("gesper", "deathclaw", "diablos", "odin", "bahamut"),
          listOf("eden", "minimog"), same = true, plus = true, elem = true, combo = true, budget = 150),
        f(13, "Cassia", "the bodyguard", "CC Spade",
          "The Queen's door. I'm the lock.", "…The Queen will hear about this.",
          listOf("minimog", "cerberus", "phoenix", "chubbychocobo", "chicobo"),
          listOf("ward", "kiros"), same = true, plus = true, elem = true, combo = true, boss = true, budget = 800),
        // ---- CHAPTER 14 · DELING COMMAND ----
        f(14, "Major Biggs", "the major", "Biggs",
          "The command counts cards AND soldiers.", "Counted wrong. As usual.",
          listOf("behemoth", "chubbychocobo", "chicobo", "minotaur", "pandemona"),
          listOf("laguna"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(14, "Sergeant Wedge", "the sergeant", "Wedge",
          "Biggs counts. I cash in.", "Sir, we need to talk about our strategy.",
          listOf("cactuar", "mobiletype8", "minimog", "sacred", "eden"),
          listOf("selphie"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(14, "Lieutenant Corve", "the strategist", "the Galbadia staff",
          "Every placement you make, I predicted.", "Prediction failed. Spectacularly.",
          listOf("tonberry", "abyssworm", "tripoint", "shiva", "carbuncle"),
          listOf("quistis"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(14, "General Marek", "the iron wall", "General Caraway",
          "War is cards. I never lose wars.", "…A tactical withdrawal.",
          listOf("cerberus", "phoenix", "odin", "leviathan", "alexander"),
          listOf("irvine", "zell"), same = true, plus = true, wall = true, elem = true,
          boss = true, budget = 800),
        // ---- CHAPTER 15 · THE QUEEN'S ROAD ----
        f(15, "The Herald", "the Queen's voice", "the Queen's entourage",
          "Her Majesty arrives. Bring your best or go home.", "Announced: your victory. She heard.",
          listOf("turtapod", "leviathan", "cerberus", "ward", "kiros"),
          listOf("bgh251f2"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(15, "Xan", "the card courier", "the Queen's courier",
          "I deliver decks. You deliver losses.", "Delivered: an upset.",
          listOf("fastitocalonf", "alexander", "laguna", "selphie", "zell"),
          listOf("redgiant"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(15, "The Shadow", "the Queen's shadow", "the Queen's protector",
          "You won't see the flip coming.", "I didn't. It hurt.",
          listOf("bomb", "pupu", "phoenix", "quistis", "irvine"),
          listOf("odin", "rinoa"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(15, "Madame Vess", "the Card Queen", "the Queen of Cards",
          "I collect. You are simply the next collection.", "Keep it. Kings are made of losses.",
          listOf("edea", "seifer", "squall", "rinoa", "doomtrain"),
          listOf("doomtrain", "seifer"),
          same = true, plus = true, wall = true, elem = true, boss = true, budget = 1200),
        // ---- CHAPTER 16 · THE MONARCH'S THRONE ----
        f(16, "The Gatekeeper", "the throne's wall", "the Monarch's guard",
          "None pass. Especially card players.", "Pass. And prosper.",
          listOf("blitz", "sphinxara", "kiros", "selphie", "zell"),
          listOf("gargantua"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(16, "Jester Fen", "the court jester", "the Monarch's fool",
          "Amuse the Monarch! Or amuse me. Same thing.", "The funniest thing I've ever seen.",
          listOf("wendigo", "bgh251f2", "diablos", "cerberus", "ward"),
          listOf("tripoint"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(16, "Ellone", "the dreamer", "Ellone",
          "I dreamed someone would finally reach him. Hello.", "The dream was right. Good luck.",
          listOf("torama", "imp", "chubbychocobo", "odin", "laguna"),
          listOf("squall"), same = true, plus = true, wall = true, elem = true, budget = 150),
        f(16, "Rex", "the Card Monarch", "Card Master King",
          "The Circuit ends where it began. With me.", "Then the crown is yours. Wear it loudly.",
          listOf("squall", "seifer", "edea", "rinoa", "bahamut"),
          listOf("edea"),
          same = true, plus = true, wall = true, elem = true, boss = true, budget = 1400),
    )

    val CHAPTERS: Int get() = FIGHTS.maxOf { it.chapter }
    val TOTAL_FIGHTS: Int get() = FIGHTS.size

    fun fightsIn(chapter: Int): List<Fight> = FIGHTS.filter { it.chapter == chapter }
    fun fightAt(globalIndex: Int): Fight = FIGHTS[globalIndex.coerceIn(0, FIGHTS.size - 1)]

    /** The next uncleared fight in the linear run — the only match the menu can
     *  start. Cleared fights only ever get ADDED, so progress can't regress. */
    fun nextUncleared(cleared: List<Int>): Int =
        FIGHTS.indices.firstOrNull { it !in cleared.toSet() } ?: FIGHTS.size
    fun isComplete(cleared: List<Int>): Boolean = cleared.toSet().size >= FIGHTS.size

    /** Cards the player owns before global fight [index] (starter + all earlier
     *  first-clear rewards). */
    fun playerArsenal(index: Int, catalog: Map<String, CardDef>): List<CardDef> =
        (STARTER + FIGHTS.take(index).flatMap { it.rewardIds })
            .mapNotNull { catalog[it] }

    /** The player's strongest legal five — stands in for a human picking their deck. */
    fun campaignHand(arsenal: List<CardDef>): List<CardDef> =
        arsenal.distinctBy { it.id }
            .sortedByDescending { it.top + it.right + it.bottom + it.left }
            .take(5)

    /** Deterministic match state for a fight. The PLAYER ALWAYS MOVES FIRST —
     *  you challenged them — which is also the linchpin of the solvability
     *  guarantee: this engine's first mover wins ~65% of equal-deck games, so a
     *  coin flip would decide fights more than the cards would. Elemental
     *  cells still roll 1-in-5 off the fight seed. */
    fun campaignState(fight: Fight, catalog: Map<String, CardDef>,
                      playerHand: List<CardDef>, seed: Long): GameState {
        val r = SplitMix64(seed)
        val cells = List(9) {
            if (fight.elemental && r.nextInt(5) == 0)
                Cell(element = Element.entries[r.nextInt(9) + 1])
            else Cell()
        }
        fun pick5(cards: List<CardDef>): List<CardDef> {
            val pool = cards.toMutableList()
            val out = ArrayList<CardDef>(5)
            while (out.size < 5) out.add(pool.removeAt(r.nextInt(pool.size)))
            return out
        }
        return GameState(
            board = Board(cells),
            blueHand = pick5(playerHand),            // player seat
            redHand = pick5(fight.deckIds.map { catalog.getValue(it) }),  // opponent seat
            turn = Owner.blue,                        // the player is always the challenger
            rules = fight.rules,
        )
    }
}
