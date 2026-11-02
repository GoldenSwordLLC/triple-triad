import SwiftUI
import TriadCore

struct SPGameView: View {
    let catalog: [String: CardDef]
    let difficulty: String
    let myDeck: [CardDef]
    let redDeck: [CardDef]?          // pass & play: red's hand, picked pre-match
    let onRecord: (Bool, Bool) -> Void
    let onMemory: ([String], Bool) -> Void
    let pendingPack: [CardDef]       // banked unclaimed wins (survives restarts)
    let onPackPending: ([CardDef]) -> Void
    let onClaimPendingPack: () -> Void
    let onExit: () -> Void

    @StateObject private var game: SinglePlayerGame
    @State private var gameMenuOpen = false
    @State private var infoCard: CardDef? = nil
    @State private var showPack = false
    @State private var packClaimed = false
    @State private var viewBoard = false

    init(catalog: [String: CardDef], difficulty: String, myDeck: [CardDef],
         redDeck: [CardDef]? = nil,
         onRecord: @escaping (Bool, Bool) -> Void,
         onMemory: @escaping ([String], Bool) -> Void = { _, _ in },
         pendingPack: [CardDef] = [],
         onPackPending: @escaping ([CardDef]) -> Void = { _ in },
         onClaimPendingPack: @escaping () -> Void = {},
         onExit: @escaping () -> Void) {
        self.catalog = catalog
        self.difficulty = difficulty
        self.myDeck = myDeck
        self.redDeck = redDeck
        self.onRecord = onRecord
        self.onMemory = onMemory
        self.pendingPack = pendingPack
        self.onPackPending = onPackPending
        self.onClaimPendingPack = onClaimPendingPack
        self.onExit = onExit
        _game = StateObject(wrappedValue: SinglePlayerGame(
            catalog: catalog, difficulty: difficulty, myDeck: myDeck, onRecord: onRecord,
            onMemory: onMemory))
    }

    var body: some View {
        let state = game.state
        ZStack {
            MatBackground(dim: 0.14)
            VStack(spacing: 6) {
                HStack {
                    Button { gameMenuOpen = true } label: {
                        Text("Menu").font(.system(size: 15, weight: .bold))
                            .foregroundColor(Color(red: 1, green: 0.98, blue: 0.92))
                    }
                    Spacer()
                    ScorePill(red: state.score(.red), blue: state.score(.blue))
                }
                HandRowView(hand: state.hand(.red), owner: .red,
                            selected: state.turn == .red ? game.selected : nil,
                            active: game.passAndPlay && !state.isOver,
                            flipped: game.passAndPlay) { game.tapCard(index: $0) }
                    .onLongPressGesture { infoCard = state.hand(.red).first }
                turnPill(state)
                BoardAreaView(state: state, flips: game.lastFlips,
                              selectActive: game.selected != nil,
                              readableFlip: game.passAndPlay,
                              onTapCell: { game.tapCell(cell: $0) },
                              onInfo: { infoCard = $0 })
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                HandRowView(hand: state.hand(.blue), owner: .blue,
                            selected: state.turn == .blue ? game.selected : nil,
                            active: !state.isOver && (game.passAndPlay || state.turn == .blue),
                            flipped: false) { game.tapCard(index: $0) }
                    .onLongPressGesture { infoCard = state.hand(.blue).first }
                if game.aiThinking { Text("AI is thinking…").font(.system(size: 11)).foregroundColor(TriadPalette.dim) }
            }.padding(.horizontal, 8)

            if state.isOver && !viewBoard { resultPopup(state) }
            if viewBoard { viewBoardBar }
            if showPack && !pendingPack.isEmpty {
                PackView(cards: pendingPack) {
                    onClaimPendingPack()
                    packClaimed = true
                    showPack = false
                }
            }
            if let infoCard { CardInfoView(def: infoCard) { self.infoCard = nil } }
        }
        .task {
            // a win rolls its pack IMMEDIATELY and banks it in the save — claimed
            // here, via the pre-match reminder, or forfeited at either point
            if game.state.isOver, !game.passAndPlay,
               game.state.score(.blue) > game.state.score(.red), !packClaimed, !showPack {
                let roll = rollPack(difficulty: difficulty, catalog: Array(catalog.values))
                onPackPending(roll)
            }
        }
        .onChange(of: game.state.isOver) { over in
            if !over { showPack = false; packClaimed = false; viewBoard = false }
        }
    }

    private var viewBoardBar: some View {
        VStack {
            Spacer()
            Button { viewBoard = false } label: {
                Text("◀  BACK TO RESULT").font(.system(size: 13, weight: .bold))
                    .foregroundColor(TriadPalette.goldText)
                    .padding(.horizontal, 20).padding(.vertical, 10)
                    .background(TriadPalette.panel.opacity(0.88),
                                in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TriadPalette.gold, lineWidth: 1))
            }.padding(.bottom, 10)
        }
    }

    private func turnPill(_ state: GameState) -> some View {
        let (text, color): (String, Color) = {
            if state.isOver { return ("MATCH OVER", TriadPalette.slabBrown) }
            if game.passAndPlay { return state.turn == .blue ? ("BLUE'S TURN", TriadPalette.frameBlue)
                                                              : ("RED'S TURN", TriadPalette.frameRed) }
            if game.aiThinking { return ("AI IS THINKING…", TriadPalette.slabBrown) }
            return state.turn == .blue ? ("YOUR TURN", TriadPalette.frameBlue)
                                       : ("OPPONENT'S TURN", TriadPalette.slabBrown)
        }()
        return TurnPill(text: text, color: color)
    }

    @ViewBuilder private func resultPopup(_ state: GameState) -> some View {
        let blue = state.score(.blue), red = state.score(.red)
        let (label, color): (String, Color) = {
            if game.passAndPlay { return blue > red ? ("BLUE WINS", TriadPalette.frameBlue)
                                                    : ("RED WINS", TriadPalette.frameRed) }
            return blue > red ? ("VICTORY", Color(red: 46/255, green: 125/255, blue: 50/255))
                              : ("DEFEAT", TriadPalette.frameRed)
        }()
        Color.black.opacity(0.55).ignoresSafeArea()
        VStack(spacing: 12) {
            Text(label).font(.system(size: 28, weight: .bold)).foregroundColor(color)
            Text("\(blue) - \(red)").font(.system(size: 14)).foregroundColor(TriadPalette.label)
            if blue > red && difficulty != "pass" && !pendingPack.isEmpty {
                Button { showPack = true } label: {
                    Text(packClaimed ? "PACK CLAIMED" : "OPEN PACK  ▶")
                        .font(.system(size: 13, weight: .bold)).foregroundColor(TriadPalette.goldText)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(packClaimed ? Color(red: 40/255, green: 30/255, blue: 14/255)
                                                : Color(red: 46/255, green: 125/255, blue: 50/255))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }.disabled(packClaimed).buttonStyle(.plain)
            }
            resultButton("REMATCH", bg: TriadPalette.panelBrown) { game.rematch() }
            resultButton("MENU", bg: Color(red: 30/255, green: 22/255, blue: 8/255)) { onExit() }
            resultButton("VIEW BOARD", bg: Color(red: 30/255, green: 22/255, blue: 8/255), border: 1) {
                viewBoard = true
            }
        }
        .padding(24)
        .background(Color(red: 30/255, green: 22/255, blue: 8/255))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(TriadPalette.gold, lineWidth: 2))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 32)
    }

    private func resultButton(_ text: String, bg: Color, border: CGFloat = 2,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(bg)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: border))
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain)
    }
}

struct CardInfoView: View {
    let def: CardDef
    let onClose: () -> Void
    var body: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { onClose() }
            VStack(spacing: 10) {
                Text(def.name).font(.system(size: 20, weight: .bold)).foregroundColor(TriadPalette.goldText)
                Text("No. G-\(def.no)   ·   Level \(def.level)   ·   \(def.type.rawValue)")
                    .font(.system(size: 12)).foregroundColor(TriadPalette.label)
                Text(def.element == .none ? "no element" : "◆ \(def.element.rawValue)")
                    .font(.system(size: 12, weight: .bold)).foregroundColor(TriadPalette.dim)
                CardSpriteView(no: def.no, owner: .blue).frame(width: 240, height: 240 / 0.714)
                VStack(spacing: 4) {
                    rankLine("TOP", def.top)
                    HStack { rankLine("LEFT", def.left); rankLine("RIGHT", def.right) }
                    rankLine("BOTTOM", def.bottom)
                }
                Text("tap to close").font(.system(size: 10)).foregroundColor(TriadPalette.dim)
            }
            .padding(16)
            .background(Color(red: 30/255, green: 22/255, blue: 8/255))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(TriadPalette.gold, lineWidth: 2))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }
    private func rankLine(_ label: String, _ value: Int) -> some View {
        HStack(spacing: 8) {
            Text(label).font(.system(size: 10)).foregroundColor(TriadPalette.dim)
            Text(value == 10 ? "A" : "\(value)").font(.system(size: 16, weight: .bold)).foregroundColor(TriadPalette.goldText)
        }.frame(maxWidth: .infinity)
    }
}
