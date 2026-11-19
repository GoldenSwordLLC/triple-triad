package com.goldenswordllc.triad

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import triad.core.CardDef
import triad.core.GameState
import triad.core.Ai
import triad.core.activeDeckCards
import triad.core.addCards
import triad.core.Campaign
import triad.core.Rules
import triad.core.Owner
import triad.core.PlayerSave
import triad.core.withRecord
import triad.net.P2pSession
import com.goldenswordllc.triad.ui.CardSheets
import com.goldenswordllc.triad.ui.MatCache
import com.goldenswordllc.triad.ui.MatBackground
import com.goldenswordllc.triad.ui.CollectionScreen
import com.goldenswordllc.triad.ui.HostLobbyScreen
import com.goldenswordllc.triad.ui.JoinFlowScreen
import com.goldenswordllc.triad.ui.CreditsScreen
import com.goldenswordllc.triad.ui.PassRedDeckScreen
import com.goldenswordllc.triad.ui.OnlineHostScreen
import com.goldenswordllc.triad.ui.RELAY_HOST
import com.goldenswordllc.triad.ui.RELAY_PORT
import com.goldenswordllc.triad.ui.OnlineJoinScreen

import com.goldenswordllc.triad.ui.DeckManagerScreen
import com.goldenswordllc.triad.ui.CampaignVersusScreen
import com.goldenswordllc.triad.ui.CircuitScreen
import com.goldenswordllc.triad.ui.MenuScreen
import com.goldenswordllc.triad.ui.MultiplayerScreen
import com.goldenswordllc.triad.ui.P2pMatchScreen
import com.goldenswordllc.triad.ui.SettingsScreen
import com.goldenswordllc.triad.ui.SinglePlayerScreen
import com.goldenswordllc.triad.ui.TutorialScreen

@Serializable
private data class CatalogFile(val cards: List<CardDef>)

private val catalogJson = Json { ignoreUnknownKeys = true }

fun loadCatalog(context: Context): Map<String, CardDef> =
    catalogJson.decodeFromString(
        CatalogFile.serializer(),
        context.assets.open("cards.json").bufferedReader().use { it.readText() },
    ).cards.associateBy { it.id }

private fun battleDeck(save: triad.core.PlayerSave, catalog: Map<String, CardDef>): List<CardDef> {
    val active = save.activeDeckCards()
    val activeOwned = triad.core.validateDeck(
        active, catalog.keys, save.collection, false, emptySet()).isEmpty()
    if (activeOwned && active.size == 5) return active.map { catalog.getValue(it) }
    val ownedDistinct = save.collection.filterValues { it > 0 }.keys.mapNotNull { catalog[it] }
    if (ownedDistinct.size >= 5) return ownedDistinct.shuffled().take(5)
    val lowLevels = catalog.values.filter { it.level in 1..5 }
    return lowLevels.shuffled().take(5)
}

private fun redPassDeck(save: triad.core.PlayerSave, blue: List<CardDef>, catalog: Map<String, CardDef>): List<CardDef> {
    val used = blue.groupingBy { it.id }.eachCount()
    val pool = save.collection.mapNotNull { (id, n) ->
        if (n - (used[id] ?: 0) > 0) catalog[id] else null
    }
    val picked = pool.shuffled().take(5).toMutableList()
    val lowLevels = catalog.values.filter { it.level in 1..5 }
    while (picked.size < 5) {
        val next = lowLevels.filter { l -> picked.none { it.id == l.id } }.randomOrNull() ?: break
        picked += next
    }
    return picked
}

private val STARTER_COLLECTION = listOf(
    "geezard", "funguar", "bitebug", "redbat", "blobra", "gayla", "gesper",
    "fastitocalonf", "bloodsoul", "caterchipillar", "cockatrice", "caterchipillar",
)

private fun ensureStarterCollection(save: triad.core.PlayerSave, catalog: Map<String, CardDef>) =
    if (save.collection.any { it.value > 0 }) save
    else save.addCards(STARTER_COLLECTION.mapNotNull { catalog[it] })

private sealed interface Screen {
    data object Menu : Screen
    data object Tutorial : Screen
    data object Settings : Screen
    data object Credits : Screen
    data object Decks : Screen
    data object Multiplayer : Screen
    data class Game(val difficulty: String, val redDeck: List<CardDef>? = null,
                    val rules: Rules = Rules(),
                    val replayFight: Campaign.Fight? = null) : Screen
    data class Versus(val fight: Campaign.Fight, val replay: Boolean = false) : Screen
    data object Circuit : Screen
    data class PassRedPick(val blueDeck: List<CardDef>) : Screen
    data object Collection : Screen
    data object OnlineHost : Screen
    data object OnlineJoin : Screen
    data object Host : Screen
    data object Join : Screen
    data class P2pMatch(val session: P2pSession, val state: GameState, val myColor: Owner) : Screen
}

class MainActivity : ComponentActivity() {
    private fun readLastCrash(): String? = runCatching {
        java.io.File(filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.take(900)
    }.getOrNull()

    private fun saveErrorToFile() {
        runCatching {
            val dir = java.io.File(filesDir, "diagnostics").apply { mkdirs() }
            val out = java.io.File(dir, "triad-diagnostics.txt")
            out.writeText(readLastCrash() ?: "No crashes recorded.")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "com.goldenswordllc.triad.fileprovider", out)
            startActivity(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                java.io.File(filesDir, "last-crash.txt").writeText(
                    "${e::class.java.name}: ${e.message ?: ""}\n\n" + e.stackTraceToString())
            }
            priorHandler?.uncaughtException(thread, e)
        }
        val catalog = loadCatalog(this)
        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) { MatCache.load(this@MainActivity) }
                val save = remember {
                    mutableStateOf(ensureStarterCollection(loadSave(this@MainActivity), catalog))
                }

                val activeIds = save.value.activeDeckCards()
                val hasValidDeck = activeIds.size == 5 && triad.core.validateDeck(
                    activeIds, catalog.keys, save.value.collection, false, emptySet()).isEmpty()
                var p2p by remember { mutableStateOf<Triple<P2pSession, GameState, Owner>?>(null) }
                var screen by remember {
                    mutableStateOf<Screen>(if (save.value.tutorialSeen) Screen.Menu else Screen.Tutorial)
                }

                when (val s = screen) {
                    Screen.Tutorial ->
                        TutorialScreen(onDone = {
                            save.value = save.value.copy(tutorialSeen = true)
                            persist(this@MainActivity, save.value)
                            screen = Screen.Menu
                        })
                    Screen.Menu ->
                        MenuScreen(
                            record = save.value,
                            hasValidDeck = hasValidDeck,
                            onStart = { tier ->
                                if (tier == "hub") {
                                    screen = Screen.Circuit
                                } else if (tier.startsWith("c:")) {
                                    val n = tier.substringAfter("c:").toInt()
                                    screen = if (n < save.value.campaignCleared.size && Campaign.isComplete(save.value.campaignCleared))
                                        Screen.Versus(Campaign.fightAt(n), replay = true)
                                    else Screen.Versus(Campaign.fightAt(n))
                                } else screen = Screen.Game(tier)
                            },
                            onTutorial = { screen = Screen.Tutorial },
                            onSettings = { screen = Screen.Settings },
                            onDecks = { screen = Screen.Decks },
                            onMultiplayer = { screen = Screen.Multiplayer },
                        )
                    Screen.OnlineHost -> {
                        OnlineHostScreen(
                            relayHost = RELAY_HOST, relayPort = RELAY_PORT,
                            catalog = catalog,
                            myDeckIds = battleDeck(save.value, catalog).map { it.id },
                            onMatchStarted = { session, state, color ->
                                screen = Screen.P2pMatch(session, state, color)
                            },
                            onExit = { screen = Screen.Multiplayer },
                        )
                    }
                    Screen.OnlineJoin -> {
                        OnlineJoinScreen(
                            relayHost = RELAY_HOST, relayPort = RELAY_PORT,
                            catalog = catalog,
                            myDeckIds = battleDeck(save.value, catalog).map { it.id },
                            onMatchStarted = { session, state, color ->
                                screen = Screen.P2pMatch(session, state, color)
                            },
                            onExit = { screen = Screen.Multiplayer },
                        )
                    }
                    Screen.Circuit ->
                        CircuitScreen(
                            circuitWins = save.value.circuitWins,
                            circuitLosses = save.value.circuitLosses,
                            onFight = { screen = Screen.Versus(it, replay = true) },
                            onExit = { screen = Screen.Menu },
                        )
                    Screen.Multiplayer ->
                        MultiplayerScreen(
                            hasActiveDeck = hasValidDeck,
                            onDecks = { screen = Screen.Decks },
                            onPassPlay = { screen = Screen.PassRedPick(battleDeck(save.value, catalog)) },
                            onHost = { screen = Screen.Host },
                            onJoin = { screen = Screen.Join },
                            onHostOnline = { screen = Screen.OnlineHost },
                            onJoinOnline = { screen = Screen.OnlineJoin },
                            onBack = { screen = Screen.Menu },
                        )
                    Screen.Decks ->
                        DeckManagerScreen(
                            catalog = catalog,
                            save = save.value,
                            onUpdate = {
                                save.value = it
                                persist(this@MainActivity, save.value)
                            },
                            onDone = { screen = Screen.Menu },
                            onViewCollection = { screen = Screen.Collection },
                        )
                    is Screen.PassRedPick -> {
                        val blue = s.blueDeck
                        val pool = remember(s) {
                            save.value.collection.filterValues { it > 0 }.keys
                                .mapNotNull { catalog[it] }
                                .filter { c -> blue.none { it.id == c.id } }
                                .sortedWith(compareBy({ it.level }, { it.no }))
                        }
                        PassRedDeckScreen(
                            pool = pool,
                            onReady = { red, rules -> screen = Screen.Game("pass", redDeck = red, rules = rules) },
                            onBack = { screen = Screen.Multiplayer },
                        )
                    }
                    Screen.Collection ->
                        CollectionScreen(catalog = catalog, save = save.value, onBack = { screen = Screen.Decks })
                    Screen.Settings ->
                        SettingsScreen(
                            record = save.value,
                            lastCrash = readLastCrash(),
                            onWipeRecord = {
                                save.value = save.value.copy(wins = 0, losses = 0, draws = 0)
                                persist(this@MainActivity, save.value)
                            },
                            onSaveErrorToFile = { saveErrorToFile() },
                            onCredits = { screen = Screen.Credits },
                            onBackToMain = { screen = Screen.Menu },
                        )
                    Screen.Credits ->
                        CreditsScreen(catalog = catalog, onBack = { screen = Screen.Settings })
                    is Screen.Versus ->
                        CampaignVersusScreen(
                            catalog = catalog,
                            cleared = save.value.campaignCleared,
                            replayFight = if (s.replay) s.fight else null,
                            onFight = { screen = Screen.Game("campaign", replayFight = if (s.replay) s.fight else null) },
                            onExit = { screen = Screen.Menu },
                        )
                    is Screen.Game -> {
                        if (s.difficulty == "campaign") {
                            // snapshot ONCE per navigation: the fight you entered with stays
                            // yours through the win's save-write — re-deriving here teleported
                            // players straight into the next fight before the result showed.
                            // Replay fights (post-run circuit hub) pass the fight explicitly and
                            // field the ARSENAL of that point — no endgame decks cheesing ch1.
                            val fight = remember { s.replayFight ?: Campaign.fightAt(save.value.campaignCleared.size) }
                            val idx = remember { Campaign.FIGHTS.indexOf(fight) }
                            val isReplay = remember { s.replayFight != null }
                            val blueDeck = remember {
                                if (isReplay) Campaign.campaignHand(Campaign.playerArsenal(idx, catalog))
                                else battleDeck(save.value, catalog)
                            }
                            SinglePlayerScreen(
                                catalog = catalog,
                                difficulty = "campaign",
                                rules = s.rules,
                                myDeck = blueDeck,
                                campaign = fight,
                                isReplay = isReplay,
                                aiBudgetMs = fight.aiBudgetMs,
                                onRecord = { won, _ ->
                                    if (won) {
                                        save.value = run {
                                            val cleared = save.value.campaignCleared
                                            val firstClear = !isReplay && idx !in cleared
                                            val base = if (firstClear)
                                                save.value.addCards(fight.rewardIds.mapNotNull { catalog[it] })
                                                    .copy(campaignCleared = cleared + idx)
                                            else save.value
                                            base.copy(wins = base.wins + 1,
                                                      circuitWins = base.circuitWins + 1)
                                        }
                                        persist(this@MainActivity, save.value)
                                    } else {
                                        save.value = save.value.copy(losses = save.value.losses + 1,
                                                                      circuitLosses = save.value.circuitLosses + 1)
                                        persist(this@MainActivity, save.value)
                                    }
                                },
                                onNext = {
                                    // route through the versus screen so the next opponent
                                    // is presented before the fight starts; replay chains keep
                                    // replay semantics (arsenal decks, no rewards)
                                    val nextIdx = idx + 1
                                    if (nextIdx < Campaign.FIGHTS.size) {
                                        screen = Screen.Versus(Campaign.fightAt(nextIdx), replay = isReplay)
                                    } else {
                                        screen = if (isReplay) Screen.Circuit else Screen.Menu
                                    }
                                },
                                onManageDeck = if (isReplay) null else ({ screen = Screen.Decks }),
                                onExit = { screen = Screen.Menu },
                            )
                        } else {
                        // pass & play — the only non-campaign single player left
                        val blueDeck = remember { battleDeck(save.value, catalog) }
                        SinglePlayerScreen(
                            catalog = catalog,
                            difficulty = s.difficulty,
                            rules = s.rules,
                            myDeck = blueDeck,
                            redDeck = s.redDeck,
                            onRecord = { won, drawn ->
                                save.value = save.value.withRecord(won = won, drawn = drawn)
                                persist(this@MainActivity, save.value)
                            },
                            onExit = { screen = Screen.Menu },
                        )
                        }
                    }
                    Screen.Host ->
                        HostLobbyScreen(
                            catalog = catalog,
                            myDeckIds = battleDeck(save.value, catalog).map { it.id },
                            onMatchStarted = { session, state, color ->
                                p2p = Triple(session, state, color)
                                screen = Screen.P2pMatch(session, state, color)
                            },
                            onExit = { screen = Screen.Menu },
                        )
                    Screen.Join ->
                        JoinFlowScreen(
                            catalog = catalog,
                            myDeckIds = battleDeck(save.value, catalog).map { it.id },
                            onMatchStarted = { session, state, color ->
                                p2p = Triple(session, state, color)
                                screen = Screen.P2pMatch(session, state, color)
                            },
                            onExit = { screen = Screen.Menu },
                        )
                    is Screen.P2pMatch ->
                        P2pMatchScreen(
                            session = s.session,
                            catalog = catalog,
                            initialState = s.state,
                            initialColor = s.myColor,
                            onExit = { screen = Screen.Menu },
                        )
                }
            }
        }
    }
}
