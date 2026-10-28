import SwiftUI
import TriadCore

struct DeckManagerView: View {
    let catalog: [String: CardDef]
    let save: PlayerSave
    let onUpdate: (PlayerSave) -> Void
    let onDone: () -> Void

    @State private var editing: String? = nil   // nil = list, "" = new, else deck id
    @State private var deleting: NamedDeck? = nil

    var body: some View {
        if let id = editing {
            DeckEditorView(catalog: catalog, save: save, deckId: id.isEmpty ? nil : id,
                           onUpdate: onUpdate, onBack: { editing = nil })
        } else {
            list
        }
    }

    private var list: some View {
        ZStack {
            MatBackground(dim: 0.28)
            VStack(spacing: 8) {
                Text("DECKS").font(.system(size: 24, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    .padding(.top, 8)
                let distinct = save.collection.values.filter { $0 > 0 }.count
                Text("COLLECTION: \(distinct) cards (\(AppSave.cardCount(save)) total)")
                    .font(.system(size: 12)).foregroundColor(TriadPalette.dim)
                ScrollView {
                    VStack(spacing: 10) {
                        if save.decks.isEmpty {
                            Text("No decks yet — build one from your collection.")
                                .font(.system(size: 13)).foregroundColor(TriadPalette.dim)
                        }
                        ForEach(save.decks, id: \.id) { deck in
                            deckRow(deck)
                        }
                    }.padding(12)
                }
                .background(Color(red: 22/255, green: 15/255, blue: 6/255).opacity( 0.85))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal, 16)
                GoldButtonView("NEW DECK") { editing = "" }
                GoldButtonView("DONE  ▶", filled: true) { onDone() }
            }
        }
        .alert("Delete \"\(deleting?.name ?? "")\"?", isPresented: Binding(
            get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("DELETE", role: .destructive) {
                if let deck = deleting {
                    onUpdate(PlayerSave(collection: save.collection,
                                        decks: save.decks.filter { $0.id != deck.id },
                                        activeDeck: save.activeDeck == deck.id ? nil : save.activeDeck,
                                        neverUse: save.neverUse, neverFace: save.neverFace,
                                        wins: save.wins, losses: save.losses, draws: save.draws,
                                        tutorialSeen: save.tutorialSeen))
                }
                deleting = nil
            }
            Button("KEEP", role: .cancel) { deleting = nil }
        } message: {
            Text("The deck is removed — your cards stay in the collection.")
        }
    }

    private func deckRow(_ deck: NamedDeck) -> some View {
        let active = deck.id == save.activeDeck
        return HStack {
            Button { editing = deck.id } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(deck.name).font(.system(size: 15, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    Text(active ? "ACTIVE DECK" : "tap to edit")
                        .font(.system(size: 10)).foregroundColor(active ? TriadPalette.gold : TriadPalette.dim)
                }
            }.buttonStyle(.plain)
            Spacer()
            HStack(spacing: 2) {
                ForEach(deck.ids, id: \.self) { id in
                    if let def = catalog[id] {
                        CardThumbView(def: def, owner: .blue)
                            .frame(width: 34, height: 34 / 0.714)
                    }
                }
            }
            Text("✕").font(.system(size: 14, weight: .bold)).foregroundColor(Color(red: 1, green: 120/255, blue: 100/255))
                .padding(6)
                .background(Color.black.opacity(0.4))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .onTapGesture { deleting = deck }
        }
        .padding(10)
        .background(active ? TriadPalette.panelBrown : Color(red: 30/255, green: 22/255, blue: 8/255))
        .overlay(RoundedRectangle(cornerRadius: 12)
            .stroke(active ? TriadPalette.gold : Color(red: 120/255, green: 95/255, blue: 50/255), lineWidth: 2))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct DeckEditorView: View {
    let catalog: [String: CardDef]
    let save: PlayerSave
    let deckId: String?
    let onUpdate: (PlayerSave) -> Void
    let onBack: () -> Void

    @State private var name: String
    @State private var picked: [String]
    private let existing: NamedDeck?

    init(catalog: [String: CardDef], save: PlayerSave, deckId: String?,
         onUpdate: @escaping (PlayerSave) -> Void, onBack: @escaping () -> Void) {
        self.catalog = catalog
        self.save = save
        self.deckId = deckId
        self.onUpdate = onUpdate
        self.onBack = onBack
        let ex = deckId.flatMap { id in save.decks.first { $0.id == id } }
        self.existing = ex
        _name = State(initialValue: ex?.name ?? "Deck \(save.decks.count + 1)")
        _picked = State(initialValue: ex?.ids ?? [])
    }

    private func used(_ id: String) -> Int { picked.filter { $0 == id }.count }
    private var collection: [String: Int] { save.collection.filter { $0 > 0 } }
    private var available: [CardDef] {
        collection.keys.compactMap { catalog[$0] }
            .filter { (collection[$0.id] ?? 0) - used($0.id) > 0 }
            .sorted { a, b in a.level != b.level ? a.level < b.level : a.no < b.no }
    }

    var body: some View {
        ZStack {
            MatBackground(dim: 0.28)
            VStack(spacing: 8) {
                HStack {
                    Text("BUILD A DECK").font(.system(size: 20, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    Spacer()
                    Text("\(picked.count)/5").font(.system(size: 16, weight: .bold))
                        .foregroundColor(picked.count == 5 ? TriadPalette.gold : TriadPalette.dim)
                }
                TextField("Deck name", text: $name)
                    .textFieldStyle(.roundedBorder)
                HStack(spacing: 6) {
                    ForEach(0..<5, id: \.self) { slot in
                        ZStack {
                            if slot < picked.count, let def = catalog[picked[slot]] {
                                CardThumbView(def: def, owner: .blue)
                            } else {
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(TriadPalette.slotOutline, lineWidth: 1)
                            }
                        }
                        .aspectRatio(0.714, contentMode: .fit)
                        .onTapGesture { if slot < picked.count { picked.remove(at: slot) } }
                    }
                }
                Text("COLLECTION — tap a card to add it")
                    .font(.system(size: 11)).foregroundColor(TriadPalette.dim)
                if available.isEmpty {
                    Text("Every card you own is already in the deck.")
                        .font(.system(size: 13)).foregroundColor(TriadPalette.dim)
                    Spacer()
                } else {
                    ScrollView {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
                            ForEach(available, id: \.id) { def in
                                let remaining = (collection[def.id] ?? 0) - used(def.id)
                                ZStack(alignment: .bottomTrailing) {
                                    CardThumbView(def: def, owner: .blue)
                                        .aspectRatio(0.714, contentMode: .fit)
                                    Text("×\(remaining)")
                                        .font(.system(size: 10, weight: .bold)).foregroundColor(TriadPalette.goldText)
                                        .padding(.horizontal, 3)
                                        .background(Color.black.opacity(0.6))
                                }
                                .onTapGesture { if picked.count < 5 { picked.append(def.id) } }
                            }
                        }.padding(8)
                    }
                    .background(Color(red: 22/255, green: 15/255, blue: 6/255).opacity(0.85))
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                }
                HStack(spacing: 8) {
                    let canSave = picked.count == 5 && !name.trimmingCharacters(in: .whitespaces).isEmpty
                    Button {
                        let id = existing?.id ?? UUID().uuidString
                        let deck = NamedDeck(id: id, name: name.trimmingCharacters(in: .whitespaces), ids: picked)
                        let decks = save.decks.filter { $0.id != id } + [deck]
                        onUpdate(PlayerSave(collection: save.collection, decks: decks,
                                            activeDeck: save.activeDeck ?? id,
                                            neverUse: save.neverUse, neverFace: save.neverFace,
                                            wins: save.wins, losses: save.losses, draws: save.draws,
                                            tutorialSeen: save.tutorialSeen))
                        onBack()
                    } label: {
                        Text("SAVE").font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .background(canSave ? TriadPalette.panelBrown : Color(red: 40/255, green: 30/255, blue: 14/255))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }.disabled(!canSave).buttonStyle(.plain)
                    if let existing {
                        Button {
                            onUpdate(PlayerSave(collection: save.collection, decks: save.decks,
                                                activeDeck: existing.id,
                                                neverUse: save.neverUse, neverFace: save.neverFace,
                                                wins: save.wins, losses: save.losses, draws: save.draws,
                                                tutorialSeen: save.tutorialSeen))
                            onBack()
                        } label: {
                            Text("SET ACTIVE").font(.system(size: 12, weight: .bold)).foregroundColor(TriadPalette.goldText)
                                .frame(maxWidth: .infinity).padding(.vertical, 12)
                                .background(TriadPalette.panelBrown)
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        }.buttonStyle(.plain)
                    }
                    Button {
                        onBack()
                    } label: {
                        Text("CANCEL").font(.system(size: 12, weight: .bold)).foregroundColor(TriadPalette.goldText)
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .background(Color(red: 30/255, green: 22/255, blue: 8/255))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }.buttonStyle(.plain)
                }
            }.padding(16)
        }
    }
}

struct PackView: View {
    let cards: [CardDef]
    let onClaim: () -> Void
    @State private var revealed: [Bool]

    init(cards: [CardDef], onClaim: @escaping () -> Void) {
        self.cards = cards
        self.onClaim = onClaim
        _revealed = State(initialValue: Array(repeating: false, count: cards.count))
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.7).ignoresSafeArea()
            VStack(spacing: 14) {
                Text("VICTORY PACK").font(.system(size: 22, weight: .bold)).foregroundColor(TriadPalette.goldText)
                Text(revealed.allSatisfy { $0 } ? "Added to your collection!" : "Tap each card to reveal it")
                    .font(.system(size: 12)).foregroundColor(TriadPalette.dim)
                HStack(spacing: 8) {
                    ForEach(cards.indices, id: \.self) { i in
                        Group {
                            if revealed[i] {
                                CardThumbView(def: cards[i], owner: .blue)
                            } else {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 8)
                                        .fill(Color(red: 25/255, green: 32/255, blue: 62/255))
                                    Text("◆").font(.system(size: 22)).foregroundColor(TriadPalette.gold)
                                }.overlay(RoundedRectangle(cornerRadius: 8).stroke(TriadPalette.gold, lineWidth: 2))
                            }
                        }
                        .aspectRatio(0.714, contentMode: .fit)
                        .onTapGesture { if !revealed[i] { revealed[i] = true } }
                    }
                }
                let all = revealed.allSatisfy { $0 }
                Button {
                    onClaim()
                } label: {
                    Text(all ? "ADD TO COLLECTION  ▶" : "REVEAL ALL FIRST")
                        .font(.system(size: 13, weight: .bold)).foregroundColor(TriadPalette.goldText)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(all ? Color(red: 46/255, green: 125/255, blue: 50/255)
                                        : Color(red: 40/255, green: 30/255, blue: 14/255))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }.disabled(!all).buttonStyle(.plain)
            }.padding(16)
                .background(Color(red: 30/255, green: 22/255, blue: 8/255))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(TriadPalette.gold, lineWidth: 2))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(24)
        }
    }
}
