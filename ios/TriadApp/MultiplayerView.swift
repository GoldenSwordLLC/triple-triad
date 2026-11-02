import SwiftUI
import TriadCore

/// Tabbed hub: one decision at a time — PLAY A MATCH or TRADE CARDS, then how.
struct MultiplayerView: View {
    let save: PlayerSave
    let onPassPlay: () -> Void
    let onHost: () -> Void
    let onJoin: () -> Void
    let onHostOnline: () -> Void
    let onJoinOnline: () -> Void
    let onTradeHost: () -> Void
    let onTradeJoin: () -> Void
    let onDecks: () -> Void
    let onBack: () -> Void

    @State private var askForDeck: (() -> Void)? = nil
    @State private var tooFewCards = false
    @State private var tab = "match"

    var body: some View {
        ZStack {
            MatBackground(dim: 0.24, bottomScrim: true)
            VStack(spacing: 10) {
                Spacer().frame(height: 24)
                Text("MULTIPLAYER").font(.system(size: 24, weight: .bold)).foregroundColor(TriadPalette.goldText)
                Spacer().frame(height: 12)
                tabChips
                Spacer().frame(height: 14)
                if tab == "match" { matchTab } else { tradeTab }
                Spacer()
                GoldButtonView("◀ BACK") { onBack() }
            }
        }
        .alert("No active deck", isPresented: Binding(get: { askForDeck != nil }, set: { if !$0 { askForDeck = nil } })) {
            Button("BUILD A DECK") { askForDeck = nil; onDecks() }
            Button("USE RANDOM CARDS") { let m = askForDeck; askForDeck = nil; m?() }
            Button("Cancel", role: .cancel) { askForDeck = nil }
        } message: {
            Text("Your deck is the hand you play with. Build one from your collection first?")
        }
        .alert("Not enough cards", isPresented: $tooFewCards) {
            Button("PLAY SINGLE PLAYER") { tooFewCards = false; onBack() }
            Button("OK", role: .cancel) { tooFewCards = false }
        } message: {
            Text("Two-phone matches need at least 6 cards — trading can take the 5 you play with, and the floor guarantees you always keep a full deck. Win single-player packs to grow your collection.")
        }
    }

    private var tabChips: some View {
        HStack(spacing: 8) {
            tabChip("match", "PLAY A MATCH")
            tabChip("trade", "TRADE CARDS")
        }
    }

    private func tabChip(_ key: String, _ label: String) -> some View {
        let sel = tab == key
        return Button { tab = key } label: {
            Text(label).font(.system(size: 12, weight: .bold))
                .foregroundColor(sel ? Color(red: 55/255, green: 38/255, blue: 10/255) : TriadPalette.goldText)
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(sel ? TriadPalette.gold : TriadPalette.panel.opacity(0.85),
                            in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(TriadPalette.gold, lineWidth: sel ? 2 : 1))
        }.buttonStyle(.plain)
    }

    private var matchTab: some View {
        VStack(spacing: 10) {
            Text("ONE PHONE").font(.system(size: 11, weight: .bold)).foregroundColor(TriadPalette.label)
            GoldButtonView("PASS & PLAY") { gate(onPassPlay) }
            Spacer().frame(height: 14)
            Text("TWO PHONES, NEARBY").font(.system(size: 11, weight: .bold)).foregroundColor(TriadPalette.label)
            GoldButtonView("HOST A MATCH") { gateTrading(onHost) }
            GoldButtonView("JOIN A MATCH") { gateTrading(onJoin) }
            Spacer().frame(height: 14)
            Text("ANY DISTANCE, ONLINE").font(.system(size: 11, weight: .bold)).foregroundColor(TriadPalette.label)
            GoldButtonView("HOST A ROOM") { gateTrading(onHostOnline) }
            GoldButtonView("JOIN WITH A CODE") { gateTrading(onJoinOnline) }
        }
    }

    private var tradeTab: some View {
        VStack(spacing: 10) {
            Text("swap cards with a friend, no match needed")
                .font(.system(size: 11)).foregroundColor(TriadPalette.dim)
            GoldButtonView("HOST A TRADE") { gateTrading(onTradeHost) }
            GoldButtonView("JOIN A TRADE") { gateTrading(onTradeJoin) }
        }
    }

    private func gate(_ mode: @escaping () -> Void) {
        if AppSave.hasActiveDeck(save) { mode() } else { askForDeck = mode }
    }
    private func gateTrading(_ mode: @escaping () -> Void) {
        if AppSave.cardCount(save) < 6 { tooFewCards = true } else { gate(mode) }
    }
}
