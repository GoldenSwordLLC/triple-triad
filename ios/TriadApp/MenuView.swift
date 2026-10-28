import SwiftUI
import TriadCore

struct MenuView: View {
    let record: PlayerSave
    let onStart: (String) -> Void
    @Environment(\.onTutorial) private var onTutorial
    @Environment(\.onSettings) private var onSettings
    @Environment(\.onDecks) private var onDecks
    @Environment(\.onMultiplayer) private var onMultiplayer

    @State private var difficulty = AppSave.difficulty
    @State private var askForDeck = false
    private let expertUnlocked = AppSave.hardWins >= 3

    private var fan: [(Int, Owner)] {
        (1...110).shuffled().prefix(3).map { ($0, Bool.random() ? Owner.blue : .red) }
    }

    var body: some View {
        ZStack {
            MatBackground(dim: 0.24, bottomScrim: true)
            VStack(spacing: 10) {
                Spacer()
                CardFanView(fan: fan)
                Text("TRIAD").font(.system(size: 44, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    .shadow(color: TriadPalette.shadow, radius: 6, x: 2, y: 2)
                Text("a Triple Triad fan game").font(.system(size: 12)).foregroundColor(TriadPalette.cream)
                Spacer()
                Text("SINGLE PLAYER").font(.system(size: 11, weight: .bold)).foregroundColor(TriadPalette.label)
                chips
                if !expertUnlocked {
                    Text("Beat HARD 3 times to unlock EXPERT")
                        .font(.system(size: 10)).foregroundColor(TriadPalette.dim)
                }
                GoldButtonView("PLAY  ▶", filled: true) {
                    if AppSave.hasActiveDeck(record) { onStart(difficulty) } else { askForDeck = true }
                }
                GoldButtonView("DECKS", sub: "manage your collection") { onDecks() }
                GoldButtonView("MULTIPLAYER", sub: "pass & play · host · join") { onMultiplayer() }
                Spacer()
                HStack(spacing: 24) {
                    Button("HOW TO PLAY") { onTutorial() }
                        .font(.system(size: 12, weight: .bold)).foregroundColor(TriadPalette.label)
                    Button("SETTINGS") { onSettings() }
                        .font(.system(size: 12, weight: .bold)).foregroundColor(TriadPalette.label)
                }
                Text("W \(record.wins) · L \(record.losses)    ·    fan project — Square Enix owns Triple Triad")
                    .font(.system(size: 9)).foregroundColor(TriadPalette.dim)
                    .padding(.bottom, 10)
            }
        }
        .alert("No active deck", isPresented: $askForDeck) {
            Button("BUILD A DECK") { onDecks() }
            Button("USE RANDOM CARDS") { onStart(difficulty) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your deck is the hand you play with — in single player and against friends. Build one from your collection first?")
        }
    }

    private var chips: some View {
        HStack(spacing: 8) {
            let tiers = ["easy", "medium", "hard"] + (expertUnlocked ? ["expert"] : [])
            ForEach(tiers, id: \.self) { diff in
                let sel = difficulty == diff
                Text(diff.uppercase())
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(sel ? TriadPalette.chipSelectedText : TriadPalette.goldText)
                    .padding(.horizontal, 18).padding(.vertical, 10)
                    .background(sel ? TriadPalette.gold : TriadPalette.chipBg)
                    .overlay(RoundedRectangle(cornerRadius: 10)
                        .stroke(TriadPalette.gold, lineWidth: sel ? 2 : 1))
                    .onTapGesture {
                        difficulty = diff
                        AppSave.difficulty = diff
                    }
            }
        }
    }
}

struct CardFanView: View {
    let fan: [(Int, Owner)]
    private let offsets: [(CGFloat, Double)] = [(-12, -8), (14, 12), (0, -2)]
    var body: some View {
        let w: CGFloat = 100, h = w / 0.714
        ZStack {
            ForEach(fan.indices, id: \.self) { i in
                let (dx, rot) = offsets[i]
                CardSpriteView(no: fan[i].0, owner: fan[i].1)
                    .frame(width: w, height: h)
                    .rotationEffect(.degrees(rot))
                    .offset(x: dx)
            }
        }
    }
}

struct GoldButtonView: View {
    let text: String
    var sub: String? = nil
    var filled = false
    var onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 2) {
                Text(text).font(.system(size: 15, weight: .bold)).foregroundColor(TriadPalette.goldText)
                if let sub {
                    Text(sub).font(.system(size: 9)).foregroundColor(TriadPalette.goldText.opacity(0.8))
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, sub == nil ? 13 : 8)
            .background(filled ? TriadPalette.panelBrown : Color.clear)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: filled ? 2 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 36)
    }
}

struct TutorialView: View {
    let onDone: () -> Void
    var body: some View {
        ZStack {
            MatBackground(dim: 0.3)
            VStack(spacing: 12) {
                Text("HOW TO PLAY").font(.system(size: 24, weight: .bold)).foregroundColor(TriadPalette.goldText)
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        section("THE GOAL", "Own the most cards on the 3×3 board when it fills up. Hands don't count — with 9 board cards there's never a tie. Whoever owns more, wins.")
                        section("YOUR TURN", "Tap one of your cards at the bottom, then tap an empty cell. The selected card lifts and glows gold; open cells light up so you know where you can place.")
                        section("CAPTURING", "Every card has four numbers: top, left, right, bottom. When you place a card, its number on a touching side is compared with the neighbour's facing number — if yours is higher, that card flips to your color.")
                        section("ELEMENTS", "On elemental boards, a card sitting on a matching element cell gets +1 on every side; on a mismatched cell it gets −1. Cells and elemental cards show the ◆ mark.")
                        section("WINNING", "When the grid is full, whoever owns more board cards wins. 5/4 is a win — never a draw.")
                    }.padding(18)
                }
                .background(Color(red: 22/255, green: 15/255, blue: 6/255).opacity(0.82))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16)
                Button("GOT IT  ▶") { onDone() }
                    .font(.system(size: 15, weight: .bold)).foregroundColor(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 10)
                    .background(Color.accentColor)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .padding(.horizontal, 16)
                Spacer(minLength: 8)
            }
        }
    }
    private func section(_ t: String, _ b: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(t).font(.system(size: 15, weight: .bold)).foregroundColor(TriadPalette.gold)
            Text(b).font(.system(size: 14)).foregroundColor(TriadPalette.cream).lineSpacing(4)
        }.padding(.bottom, 10)
    }
}

struct SettingsView: View {
    let record: PlayerSave
    let onWipe: () -> Void
    let onDone: () -> Void
    @State private var confirmWipe = false

    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "Version \(v)  (build \(b))"
    }

    var body: some View {
        ZStack {
            MatBackground(dim: 0.3)
            VStack(spacing: 12) {
                Text("SETTINGS").font(.system(size: 24, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    .padding(.top, 20)
                VStack(alignment: .leading, spacing: 10) {
                    Text("RECORD").font(.system(size: 15, weight: .bold)).foregroundColor(TriadPalette.gold)
                    HStack(spacing: 20) {
                        stat("WINS", record.wins, Color(red: 120/255, green: 190/255, blue: 1))
                        stat("LOSSES", record.losses, Color(red: 1, green: 120/255, blue: 100/255))
                    }
                    Text(confirmWipe ? "TAP AGAIN TO WIPE — THIS CANNOT BE UNDONE" : "WIPE WIN / LOSS RECORD")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(confirmWipe ? Color.red : TriadPalette.dim)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Color(red: 30/255, green: 22/255, blue: 8/255))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .onTapGesture {
                            if confirmWipe { onWipe(); confirmWipe = false } else { confirmWipe = true }
                        }
                    Text("ABOUT").font(.system(size: 15, weight: .bold)).foregroundColor(TriadPalette.gold)
                        .padding(.top, 8)
                    Text("Triad — a Triple Triad fan game").font(.system(size: 14)).foregroundColor(TriadPalette.cream)
                    Text(version).font(.system(size: 12)).foregroundColor(TriadPalette.dim)
                    Text("fan project — Square Enix owns Triple Triad")
                        .font(.system(size: 10)).foregroundColor(TriadPalette.dim)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(18)
                .background(Color(red: 22/255, green: 15/255, blue: 6/255).opacity(0.85))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16)
                GoldButtonView("DONE  ▶", filled: true) { onDone() }
                Spacer()
            }
        }
    }
    private func stat(_ label: String, _ value: Int, _ color: Color) -> some View {
        HStack(alignment: .bottom) {
            Text("\(value)").font(.system(size: 26, weight: .bold)).foregroundColor(color)
            Text(label).font(.system(size: 11, weight: .bold)).foregroundColor(TriadPalette.dim).padding(.bottom, 4)
        }
    }
}
