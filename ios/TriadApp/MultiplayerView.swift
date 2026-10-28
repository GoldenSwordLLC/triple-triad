import SwiftUI
import TriadCore

struct MultiplayerView: View {
    let save: PlayerSave
    let onPassPlay: () -> Void
    let onHost: () -> Void
    let onJoin: () -> Void
    let onDecks: () -> Void
    let onBack: () -> Void

    @State private var askForDeck: (() -> Void)? = nil
    @State private var tooFewCards = false

    var body: some View {
        ZStack {
            MatBackground(dim: 0.24, bottomScrim: true)
            VStack(spacing: 10) {
                Spacer().frame(height: 24)
                Text("MULTIPLAYER").font(.system(size: 24, weight: .bold)).foregroundColor(TriadPalette.goldText)
                Spacer().frame(height: 24)
                Text("ONE PHONE").font(.system(size: 11, weight: .bold)).foregroundColor(TriadPalette.label)
                GoldButtonView("PASS & PLAY") { gate(onPassPlay) }
                Spacer().frame(height: 18)
                Text("TWO PHONES — WI-FI").font(.system(size: 11, weight: .bold)).foregroundColor(TriadPalette.label)
                GoldButtonView("HOST A MATCH", sub: "show your QR code") { gateTrading(onHost) }
                GoldButtonView("JOIN A MATCH", sub: "scan a QR code") { gateTrading(onJoin) }
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

    private func gate(_ mode: @escaping () -> Void) {
        if AppSave.hasActiveDeck(save) { mode() } else { askForDeck = mode }
    }
    private func gateTrading(_ mode: @escaping () -> Void) {
        if AppSave.cardCount(save) < 6 { tooFewCards = true } else { gate(mode) }
    }
}
