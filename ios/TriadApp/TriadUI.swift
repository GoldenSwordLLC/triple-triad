import SwiftUI
import TriadCore
import ImageIO

// ---------------- palette + shared bits ----------------

enum TriadPalette {
    static let frameBlue = Color(red: 21/255, green: 101/255, blue: 192/255)
    static let frameRed = Color(red: 183/255, green: 28/255, blue: 28/255)
    static let gold = Color(red: 212/255, green: 175/255, blue: 55/255)
    static let goldText = Color(red: 255/255, green: 240/255, blue: 200/255)
    static let cream = Color(red: 245/255, green: 238/255, blue: 220/255)
    static let slotFill = Color(red: 40/255, green: 28/255, blue: 12/255).opacity(0.55)
    static let slotOutline = Color(red: 120/255, green: 95/255, blue: 50/255)
    static let panelBrown = Color(red: 120/255, green: 85/255, blue: 20/255)
    static let chipBg = Color(red: 30/255, green: 22/255, blue: 8/255).opacity(0.85)
    static let chipSelectedText = Color(red: 55/255, green: 38/255, blue: 10/255)
    static let dim = Color(red: 200/255, green: 178/255, blue: 130/255)
    static let label = Color(red: 230/255, green: 205/255, blue: 160/255)
    static let disabled = Color(white: 0.75).opacity(0.45)
    static let slabBrown = Color(red: 70/255, green: 60/255, blue: 40/255)
    static let shadow = Color(red: 80/255, green: 55/255, blue: 10/255)
}

enum CardArtCache {
    static var mat: CGImage?
}

struct CardSpriteView: View {
    let no: Int
    let owner: Owner

    var body: some View {
        if let cg = CardArt.load(no, owner) {
#if canImport(UIKit)
            Image(uiImage: UIImage(cgImage: cg)).resizable().scaledToFit()
#elseif canImport(AppKit)
            Image(nsImage: NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height)))
                .resizable().scaledToFit()
#endif
        } else {
            Text("\(no)").foregroundColor(.white)
        }
    }
}

struct MatBackground: View {
    var dim: Double = 0.12
    var bottomScrim = false

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                if let cg = CardArtCache.mat {
                    platformImage(cg)
                        .scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                }
                Color.black.opacity(dim)
                if bottomScrim {
                    LinearGradient(stops: [
                        .init(color: .clear, location: 0.35),
                        .init(color: Color.black.opacity(0.78), location: 1.0),
                    ], startPoint: .top, endPoint: .bottom)
                }
            }
        }
        .ignoresSafeArea()
        .onAppear { if CardArtCache.mat == nil { CardArtCache.mat = CardArt.playmat() } }
    }
}

@ViewBuilder
func platformImage(_ cg: CGImage) -> some View {
#if canImport(UIKit)
    Image(uiImage: UIImage(cgImage: cg)).resizable()
#elseif canImport(AppKit)
    Image(nsImage: NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))).resizable()
#else
    Color.gray
#endif
}

// ---------------- game pieces ----------------

struct CardThumbView: View {
    let def: CardDef
    var owner: Owner? = nil
    var selected = false
    var conflict = false

    var body: some View {
        let border: Color = selected ? TriadPalette.gold
            : conflict ? .red
            : owner == .blue ? TriadPalette.frameBlue
            : owner == .red ? TriadPalette.frameRed
            : TriadPalette.slotOutline
        CardSpriteView(no: def.no, owner: owner ?? .blue)
            .aspectRatio(CardSheetConstants.aspect, contentMode: .fit)
            .overlay(RoundedRectangle(cornerRadius: 6)
                .stroke(border, lineWidth: selected ? 3 : 2))
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

enum CardSheetConstants {
    static let aspect: CGFloat = CardArt.aspect
}

struct ScorePill: View {
    let red: Int
    let blue: Int
    var body: some View {
        HStack(spacing: 8) {
            Text("RED \(red)").foregroundColor(TriadPalette.frameRed)
            Text("·").foregroundColor(TriadPalette.cream)
            Text("\(blue) BLUE").foregroundColor(Color(red: 120/255, green: 190/255, blue: 1))
        }
        .font(.system(size: 13, weight: .bold))
        .padding(.horizontal, 12).padding(.vertical, 5)
        .background(Color(red: 30/255, green: 22/255, blue: 8/255))
        .clipShape(Capsule())
    }
}

struct TurnPill: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).font(.system(size: 12, weight: .bold)).foregroundColor(.white)
            .padding(.horizontal, 14).padding(.vertical, 5)
            .background(color).clipShape(Capsule())
    }
}

struct HandRowView: View {
    let hand: [CardDef]
    let owner: Owner
    let selected: Int?
    let active: Bool
    var flipped: Bool = false
    let onTap: (Int) -> Void

    var body: some View {
        ZStack {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(hand.indices, id: \.self) { i in
                        let isSel = selected == i && active
                        CardThumbView(def: hand[i], owner: owner, selected: isSel)
                            .frame(width: 96)
                            .offset(y: isSel ? -12 : 0)
                            .rotationEffect(.degrees(flipped ? 180 : 0))
                            .onTapGesture { onTap(i) }
                    }
                    Color.clear.frame(width: 8, height: 1)
                }
                .padding(.horizontal, 6)
            }
            if hand.count > 3 {
                Text("›").font(.system(size: 26, weight: .bold)).foregroundColor(.white)
                    .padding(.horizontal, 5).background(Color.black.opacity(0.35))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    .padding(.trailing, 2)
                    .allowsHitTesting(false)
            }
        }
    }
}

struct BoxedTurnPill: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).font(.system(size: 12, weight: .bold)).foregroundColor(.white)
            .padding(.horizontal, 14).padding(.vertical, 5)
            .background(color).clipShape(Capsule())
    }
}

// MARK: - Board (slots + placed cards with flip animation and read-flip)

struct BoardAreaView: View {
    let state: GameState
    let flips: [Flip]
    let selectActive: Bool
    var boardRotation: Double = 0
    let onTapCell: (Int) -> Void
    var onCardTap: (() -> Void)? = nil
    var onInfo: (CardDef) -> Void = { _ in }

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let pad: CGFloat = 8, gap: CGFloat = 6
            let cell = (side - pad * 2 - gap * 2) / 3
            VStack(spacing: gap) {
                ForEach(0..<3, id: \.self) { r in
                    HStack(spacing: gap) {
                        ForEach(0..<3, id: \.self) { c in
                            let idx = r * 3 + c
                            BoardSlotView(index: idx, cell: state.board[idx], flips: flips,
                                          highlight: selectActive && state.board[idx].card == nil,
                                          onTap: { onTapCell(idx) },
                                          onCardTap: onCardTap,
                                          onInfo: onInfo)
                                .frame(width: cell, height: cell)
                        }
                    }
                }
            }
            .frame(width: side, height: side)
            .rotationEffect(.degrees(boardRotation))
            .position(x: geo.size.width / 2, y: geo.size.height / 2)
        }
    }
}

private struct BoardSlotView: View {
    let index: Int
    let cell: Cell
    let flips: [Flip]
    let highlight: Bool
    let onTap: () -> Void
    var onCardTap: (() -> Void)? = nil
    let onInfo: (CardDef) -> Void

    @State private var displayed: Owner? = nil
    @State private var angle: Double = 0

    var body: some View {
        ZStack {
            if let card = cell.card {
                CardSpriteView(no: card.def.no, owner: displayed ?? card.owner)
                    .rotation3DEffect(.degrees(angle), axis: (x: 0, y: 1, z: 0))
                    .onTapGesture { onCardTap?() }
                    .onLongPressGesture { onInfo(card.def) }
            } else {
                RoundedRectangle(cornerRadius: 8)
                    .fill(TriadPalette.slotFill)
                    .overlay(RoundedRectangle(cornerRadius: 8)
                        .stroke(highlight ? TriadPalette.gold : TriadPalette.slotOutline, lineWidth: 1))
                    .overlay {
                        if cell.element != .none {
                            Text("◆").font(.system(size: 18)).foregroundColor(elementColor(cell.element))
                        }
                    }
                    .onTapGesture(perform: onTap)
            }
        }
        .onAppear { if displayed == nil { displayed = cell.card?.owner } }
        .onChange(of: cell.card?.owner) { newOwner in
            guard let new = newOwner, let old = displayed, new != old,
                  let flip = flips.first(where: { $0.cell == index }) else {
                if displayed == nil { displayed = newOwner }
                return
            }
            let delay = Double(flip.wave) * 0.35
            withAnimation(.easeIn(duration: 0.14).delay(delay)) { angle = 90 }
            DispatchQueue.main.asyncAfter(deadline: .now() + delay + 0.14) {
                displayed = new
                withAnimation(.easeOut(duration: 0.14)) { angle = 0 }
            }
        }
    }
}

private func elementColor(_ e: Element) -> Color {
    switch e {
    case .fire: return Color(red: 216/255, green: 67/255, blue: 21/255)
    case .ice: return Color(red: 79/255, green: 195/255, blue: 247/255)
    case .thunder: return Color(red: 249/255, green: 168/255, blue: 37/255)
    case .poison: return Color(red: 142/255, green: 36/255, blue: 170/255)
    case .holy: return Color(red: 224/255, green: 214/255, blue: 194/255)
    case .wind: return Color(red: 124/255, green: 179/255, blue: 66/255)
    case .earth: return Color(red: 141/255, green: 110/255, blue: 99/255)
    case .water: return Color(red: 0, green: 131/255, blue: 143/255)
    case .dark: return Color(red: 69/255, green: 90/255, blue: 100/255)
    case .none: return Color(red: 97/255, green: 97/255, blue: 97/255)
    }
}
