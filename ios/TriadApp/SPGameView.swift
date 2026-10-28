import SwiftUI
import TriadCore

struct SPGameView: View {
    let catalog: [String: CardDef]
    let difficulty: String
    let myDeck: [CardDef]
    let onRecord: (Bool, Bool) -> Void
    let onCardsGained: ([CardDef]) -> Void
    let onExit: () -> Void

    @StateObject private var game: SinglePlayerGame
    @State private var confirmExit = false
    @State private var infoCard: CardDef? = nil
    @State private var pack: [CardDef]? = nil
    @State private var packClaimed = false

    init(catalog: [String: CardDef], difficulty: String, myDeck: [CardDef],
         onRecord: @escaping (Bool, Bool) -> Void, onCardsGained: @escaping ([CardDef]) -> Void,
         onExit: @escaping () -> Void) {
        self.catalog = catalog
        self.difficulty = difficulty
        self.myDeck = myDeck
        self.onRecord = onRecord
        self.onCardsGained = onCardsGained
        self.onExit = onExit
        _game = StateObject(wrappedValue: SinglePlayerGame(
            catalog: catalog, difficulty: difficulty, myDeck: myDeck, onRecord: onRecord))
    }

    var body: some View {
        let state = game.state
        ZStack {
            MatBackground(dim: 0.14)
            VStack(spacing: 6) {
                HStack {
                    Button { confirmExit = true } label: {
                        Text("✕ Exit").font(.system(size: 15, weight: .bold)).foregroundColor(.white)
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

            if state.isOver { resultPopup(state) }
            if let pack { PackView(cards: pack) {
                onCardsGained(pack)
                packClaimed = true
                self.pack = nil
            } }
            if let infoCard { CardInfoView(def: infoCard) { self.infoCard = nil } }
        }
        .alert("Leave this match?", isPresented: $confirmExit) {
            Button("LEAVE", role: .destructive) { onExit() }
            Button("STAY", role: .cancel) {}
        } message: { Text("Your progress in this game will be lost.") }
        .onChange(of: game.state.isOver) { over in
            if !over { pack = nil; packClaimed = false }
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
            Text("\(blue) — \(red)").font(.system(size: 14)).foregroundColor(TriadPalette.label)
            if blue > red && difficulty != "pass" {
                Button {
                    if pack == nil { pack = rollPack(difficulty: difficulty, catalog: Array(catalog.values)) }
                } label: {
                    Text(packClaimed ? "PACK CLAIMED" : "OPEN PACK  ▶")
                        .font(.system(size: 13, weight: .bold)).foregroundColor(TriadPalette.goldText)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(packClaimed ? Color(red: 40/255, green: 30/255, blue: 14/255)
                                                : Color(red: 46/255, green: 125/255, blue: 50/255))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }.disabled(packClaimed).buttonStyle(.plain)
            }
            Button { game.rematch() } label: {
                Text("REMATCH").font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(TriadPalette.panelBrown)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }.buttonStyle(.plain)
            Button { onExit() } label: {
                Text("MENU").font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(Color(red: 30/255, green: 22/255, blue: 8/255))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }.buttonStyle(.plain)
        }
        .padding(24)
        .background(Color(red: 30/255, green: 22/255, blue: 8/255))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(TriadPalette.gold, lineWidth: 2))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 32)
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
