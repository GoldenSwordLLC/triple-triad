import SwiftUI
import TriadCore

/// Pass & play: hand the phone to RED, who builds their own 5-card hand from
/// whatever the collection has left after BLUE's deck.
struct RedPickView: View {
    let pool: [CardDef]
    let onReady: ([CardDef]) -> Void
    let onBack: () -> Void

    @State private var picked: [CardDef] = []

    var body: some View {
        ZStack {
            MatBackground(dim: 0.3)
            VStack(spacing: 8) {
                Text("RED, BUILD YOUR HAND").font(.system(size: 20, weight: .bold))
                    .foregroundColor(TriadPalette.goldText).padding(.vertical, 8)
                Text("pass the phone to the red player").font(.system(size: 10)).foregroundColor(TriadPalette.dim)
                HStack(spacing: 6) {
                    ForEach(0..<5, id: \.self) { slot in
                        if let def = picked[safe: slot] {
                            CardThumbView(def: def, conflict: false, owner: .red)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        } else {
                            RoundedRectangle(cornerRadius: 8)
                                .strokeBorder(Color(red: 120/255, green: 95/255, blue: 50/255), lineWidth: 1)
                        }
                    }
                }.frame(height: 80).padding(.horizontal, 12)
                if pool.isEmpty {
                    Text("Nothing left after BLUE's deck — the match will deal a random red hand.")
                        .font(.system(size: 12)).foregroundColor(TriadPalette.dim)
                } else {
                    ScrollView {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 5),
                                  spacing: 6) {
                            ForEach(pool, id: \.id) { def in
                                CardThumbView(def: def, conflict: false,
                                              selected: picked.contains(where: { $0.id == def.id }),
                                              owner: .red)
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    .aspectRatio(0.714, contentMode: .fit)
                                    .onTapGesture { toggle(def) }
                            }
                        }.padding(8)
                    }
                    .background(TriadPalette.panel.opacity(0.75), in: RoundedRectangle(cornerRadius: 12))
                }
                Spacer().frame(height: 8)
                HStack(spacing: 10) {
                    Button { onBack() } label: {
                        Text("◀  BACK").font(.system(size: 14, weight: .bold))
                            .foregroundColor(TriadPalette.label)
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .background(TriadPalette.panel.opacity(0.85),
                                        in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TriadPalette.gold, lineWidth: 1))
                    }.buttonStyle(.plain)
                    Button {
                        if picked.count == 5 { onReady(picked) }
                        else if pool.isEmpty { onReady([]) }   // empty pool => random later
                    } label: {
                        Text(picked.count == 5 || pool.isEmpty ? "START  ▶" : "PICK \(5 - picked.count) MORE")
                            .font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .background(TriadPalette.panelBrown,
                                        in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TriadPalette.gold, lineWidth: 2))
                    }.buttonStyle(.plain)
                    .disabled(picked.count != 5 && !pool.isEmpty)
                    .opacity(picked.count == 5 || pool.isEmpty ? 1 : 0.5)
                }.padding(.horizontal, 16)
            }
        }
    }

    private func toggle(_ def: CardDef) {
        if let i = picked.firstIndex(where: { $0.id == def.id }) {
            picked.remove(at: i)
        } else if picked.count < 5 {
            picked.append(def)
        }
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
