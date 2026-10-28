import SwiftUI
import TriadCore

@main
struct TriadAppMain: App {
    var body: some Scene {
        WindowGroup { RootView() }
    }
}

enum Screen {
    case menu, tutorial, settings, decks
    case game(difficulty: String)
    case multiplayer
    case host
    case join
    case p2pMatch(session: P2pSession, state: GameState, color: Owner)
}

struct RootView: View {
    @State private var save = PlayerSave()
    @State private var screen: Screen = .menu
    private let catalog = CardCatalog().byId

    var body: some View {
        Group { current }
            .onAppear {
                save = AppSave.load(catalog: catalog)
                if !save.tutorialSeen { screen = .tutorial }
            }
    }

    @ViewBuilder private var current: some View {
        switch screen {
        case .tutorial:
            TutorialView(onDone: {
                save.tutorialSeen = true
                AppSave.persist(save)
                screen = .menu
            })
        case .menu:
            MenuView(record: save) { screen = .game(difficulty: $0) }
                .onTutorial { screen = .tutorial }
                .onSettings { screen = .settings }
                .onDecks { screen = .decks }
                .onMultiplayer { screen = .multiplayer }
        case .settings:
            SettingsView(record: save,
                         onWipe: { save.wins = 0; save.losses = 0; AppSave.persist(save) },
                         onDone: { screen = .menu })
        case .decks:
            DeckManagerView(catalog: catalog, save: save,
                            onUpdate: { save = $0; AppSave.persist(save) },
                            onDone: { screen = .menu })
        case .game(let difficulty):
            SPGameView(catalog: catalog, difficulty: difficulty,
                       myDeck: AppSave.battleDeck(save, catalog: catalog),
                       onRecord: { won, _ in
                           save = save.withRecord(won: won, drawn: false)
                           AppSave.persist(save)
                           if won && difficulty == "hard" { AppSave.hardWins += 1 }
                       },
                       onCardsGained: { cards in
                           save = save.addCards(cards)
                           AppSave.persist(save)
                       },
                       onExit: { screen = .menu })
        case .multiplayer:
            MultiplayerView(save: save,
                            onPassPlay: { screen = .game(difficulty: "pass") },
                            onHost: { screen = .host },
                            onJoin: { screen = .join },
                            onDecks: { screen = .decks },
                            onBack: { screen = .menu })
        case .host:
            HostLobbyView(catalog: catalog,
                          deckIds: AppSave.battleDeck(save, catalog: catalog).map { $0.id },
                          cardCount: AppSave.cardCount(save),
                          onMatchStarted: { session, state, color in
                              screen = .p2pMatch(session: session, state: state, color: color)
                          },
                          onExit: { screen = .menu })
        case .join:
            JoinFlowView(catalog: catalog,
                         deckIds: AppSave.battleDeck(save, catalog: catalog).map { $0.id },
                         cardCount: AppSave.cardCount(save),
                         onMatchStarted: { session, state, color in
                             screen = .p2pMatch(session: session, state: state, color: color)
                         },
                         onTransfers: { transfers, myColor in
                             save = save.applyTransfers(transfers, myColor: myColor)
                             AppSave.persist(save)
                         },
                         onExit: { screen = .menu })
        case .p2pMatch(let session, let state, let color):
            P2pMatchView(session: session, initialState: state, myColor: color,
                         onExit: { screen = .menu })
        }
    }
}

// small helper to attach menu callbacks without bloating the init
extension View {
    func onTutorial(_ a: @escaping () -> Void) -> some View { environment(\.onTutorial, a) }
    func onSettings(_ a: @escaping () -> Void) -> some View { environment(\.onSettings, a) }
    func onDecks(_ a: @escaping () -> Void) -> some View { environment(\.onDecks, a) }
    func onMultiplayer(_ a: @escaping () -> Void) -> some View { environment(\.onMultiplayer, a) }
}

private struct OnTutorialKey: EnvironmentKey { static let defaultValue: () -> Void = {} }
private struct OnSettingsKey: EnvironmentKey { static let defaultValue: () -> Void = {} }
private struct OnDecksKey: EnvironmentKey { static let defaultValue: () -> Void = {} }
private struct OnMultiplayerKey: EnvironmentKey { static let defaultValue: () -> Void = {} }

extension EnvironmentValues {
    var onTutorial: () -> Void {
        get { self[OnTutorialKey.self] } set { self[OnTutorialKey.self] = newValue }
    }
    var onSettings: () -> Void {
        get { self[OnSettingsKey.self] } set { self[OnSettingsKey.self] = newValue }
    }
    var onDecks: () -> Void {
        get { self[OnDecksKey.self] } set { self[OnDecksKey.self] = newValue }
    }
    var onMultiplayer: () -> Void {
        get { self[OnMultiplayerKey.self] } set { self[OnMultiplayerKey.self] = newValue }
    }
}
