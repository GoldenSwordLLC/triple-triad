import Foundation

public enum Owner: String, Codable, Hashable {
    case blue, red
    public var other: Owner { self == .blue ? .red : .blue }
    public init() { self = .blue }
}

public enum Side: String, CaseIterable {
    case top, right, bottom, left
    public var opposite: Side {
        switch self { case .top: return .bottom; case .bottom: return .top
                      case .left: return .right; case .right: return .left }
    }
}

// Declaration order is CANONICAL — must match Kotlin's Element enum exactly (ordinals cross the wire).
public enum Element: String, Codable, Hashable, CaseIterable {
    case none, fire, ice, thunder, poison, holy, wind, earth, water, dark
}

public enum CardType: String, Codable, Hashable { case monster, boss, gf, character }
public enum TradeRule: String, Codable, Hashable { case none, one, direct, diff, all }

public struct CardDef: Hashable {
    public let id: String, name: String, level: Int, element: Element
    public let top: Int, right: Int, bottom: Int, left: Int
    public let no: Int, type: CardType
    public init(id: String, name: String, level: Int, element: Element,
                top: Int, right: Int, bottom: Int, left: Int,
                no: Int = 0, type: CardType = .monster) {
        self.id = id; self.name = name; self.level = level; self.element = element
        self.top = top; self.right = right; self.bottom = bottom; self.left = left
        self.no = no; self.type = type
    }
    public func rank(_ side: Side) -> Int {
        switch side { case .top: return top; case .right: return right
                      case .bottom: return bottom; case .left: return left }
    }
}
extension CardDef: Codable {
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decode(String.self, forKey: .name)
        level = try c.decode(Int.self, forKey: .level)
        element = try c.decode(Element.self, forKey: .element)
        top = try c.decode(Int.self, forKey: .top); right = try c.decode(Int.self, forKey: .right)
        bottom = try c.decode(Int.self, forKey: .bottom); left = try c.decode(Int.self, forKey: .left)
        no = try c.decodeIfPresent(Int.self, forKey: .no) ?? 0
        type = try c.decodeIfPresent(CardType.self, forKey: .type) ?? .monster
    }
}

public struct PlacedCard: Hashable, Codable {
    public let def: CardDef
    public let originalOwner: Owner
    public var owner: Owner
    public init(def: CardDef, owner: Owner) { self.def = def; self.originalOwner = owner; self.owner = owner }
}

public struct Cell: Hashable, Codable {
    public var card: PlacedCard? = nil
    public var element: Element = .none
    public init(card: PlacedCard? = nil, element: Element = .none) {
        self.card = card; self.element = element
    }
}

public struct Board: Hashable, Codable {
    public var cells: [Cell] = Array(repeating: Cell(), count: 9)
    public init(cells: [Cell] = Array(repeating: Cell(), count: 9)) { self.cells = cells }
    public subscript(i: Int) -> Cell { cells[i] }
    public var isFull: Bool { cells.allSatisfy { $0.card != nil } }
    public func withCard(_ i: Int, _ card: PlacedCard) -> Board {
        var b = self; b.cells[i].card = card; return b
    }
    public func withOwner(_ i: Int, _ owner: Owner) -> Board {
        var b = self
        if let c = b.cells[i].card { b.cells[i].card = PlacedCard(def: c.def, owner: owner) }
        return b
    }
}

public struct Rules: Codable, Hashable {
    public var same = false, plus = false, sameWall = false, elemental = false
    public init(same: Bool = false, plus: Bool = false,
                sameWall: Bool = false, elemental: Bool = false) {
        self.same = same; self.plus = plus; self.sameWall = sameWall; self.elemental = elemental
    }
}

public struct MatchSettings: Codable, Hashable, Sendable {
    public var rules = Rules()
    public var open = true
    public var randomHands = false
    public var suddenDeath = false
    public var trade = TradeRule.none
    public init(rules: Rules = Rules(), open: Bool = true, randomHands: Bool = false,
                suddenDeath: Bool = false, trade: TradeRule = .none) {
        self.rules = rules; self.open = open; self.randomHands = randomHands
        self.suddenDeath = suddenDeath; self.trade = trade
    }
}

public struct GameState: Hashable, Codable {
    public var board = Board()
    public var blueHand: [CardDef] = []
    public var redHand: [CardDef] = []
    public var turn: Owner = .blue
    public var rules = Rules()
    public init(board: Board = Board(), blueHand: [CardDef] = [], redHand: [CardDef] = [],
                turn: Owner = .blue, rules: Rules = Rules()) {
        self.board = board; self.blueHand = blueHand; self.redHand = redHand
        self.turn = turn; self.rules = rules
    }
    public func hand(_ owner: Owner) -> [CardDef] { owner == .blue ? blueHand : redHand }
    public var isOver: Bool { board.isFull }
    // Board cards owned — hands don't count; with 9 board cards a tie is impossible.
    public func score(_ owner: Owner) -> Int {
        board.cells.filter { $0.card?.owner == owner }.count
    }
}

public enum FlipCause: String, Codable, Hashable { case basic, same, plus, combo }
public struct Flip: Hashable, Codable { public let cell: Int; public let cause: FlipCause; public let wave: Int
    public init(cell: Int, cause: FlipCause, wave: Int) {
        self.cell = cell; self.cause = cause; self.wave = wave
    } }
public struct PlacementResult { public let state: GameState; public let flips: [Flip]
    public init(state: GameState, flips: [Flip]) { self.state = state; self.flips = flips } }

public struct CardTransfer: Hashable, Sendable { public let card: CardDef; public let from: Owner; public let to: Owner
    public init(card: CardDef, from: Owner, to: Owner) { self.card = card; self.from = from; self.to = to } }
public struct PendingPick: Sendable { public let count: Int; public let candidates: [CardDef]
    public init(count: Int, candidates: [CardDef]) { self.count = count; self.candidates = candidates } }

public enum Engine {
    private static let wall = 10

    public static func neighborIndex(_ index: Int, _ side: Side) -> Int? {
        let row = index / 3, col = index % 3
        switch side {
        case .top:    return row > 0 ? index - 3 : nil
        case .bottom: return row < 2 ? index + 3 : nil
        case .left:   return col > 0 ? index - 1 : nil
        case .right:  return col < 2 ? index + 1 : nil
        }
    }

    public static func effectiveValue(_ card: CardDef, _ side: Side,
                                      cellElement: Element, elementalOn: Bool) -> Int {
        guard elementalOn, cellElement != .none else { return card.rank(side) }
        return card.rank(side) + (card.element == cellElement ? 1 : -1)
    }

    public static func place(_ state: GameState, cardIndex: Int, cellIndex: Int) -> PlacementResult {
        let hand = state.hand(state.turn)
        precondition(cardIndex >= 0 && cardIndex < hand.count, "cardIndex out of bounds")
        precondition(cellIndex >= 0 && cellIndex < 9 && state.board[cellIndex].card == nil, "cell unavailable")
        let card = hand[cardIndex]
        let me = state.turn

        let afterPlace = state.board.withCard(cellIndex, PlacedCard(def: card, owner: me))
        let flips = computeFlips(afterPlace, placedIndex: cellIndex, rules: state.rules)

        var board = afterPlace
        for f in flips { board = board.withOwner(f.cell, me) }

        var next = state
        next.board = board
        switch me {
        case .blue: next.blueHand.remove(at: cardIndex)
        case .red:  next.redHand.remove(at: cardIndex)
        }
        next.turn = me.other
        return PlacementResult(state: next, flips: flips)
    }

    private struct Neighbor { let side: Side; let index: Int; let card: PlacedCard }

    static func computeFlips(_ board: Board, placedIndex: Int, rules: Rules) -> [Flip] {
        let me = board[placedIndex].card!.owner

        func effAt(_ b: Board, _ index: Int, _ side: Side) -> Int {
            effectiveValue(b[index].card!.def, side,
                           cellElement: b[index].element, elementalOn: rules.elemental)
        }
        func myEff(_ side: Side) -> Int {
            effectiveValue(board[placedIndex].card!.def, side,
                           cellElement: board[placedIndex].element, elementalOn: rules.elemental)
        }

        let neighbors: [Neighbor] = Side.allCases.compactMap { side in
            guard let idx = neighborIndex(placedIndex, side), let card = board[idx].card else { return nil }
            return Neighbor(side: side, index: idx, card: card)
        }

        // 1) Basic
        let basic = neighbors.filter { n in
            n.card.owner != me && myEff(n.side) > effAt(board, n.index, n.side.opposite)
        }.map { Flip(cell: $0.index, cause: .basic, wave: 0) }

        // 2) Same
        var special: [Flip] = []
        if rules.same {
            let matches = Side.allCases.filter { side in
                guard let idx = neighborIndex(placedIndex, side) else {
                    return rules.sameWall && myEff(side) == wall
                }
                guard board[idx].card != nil else { return false }
                return myEff(side) == effAt(board, idx, side.opposite)
            }.count
            if matches >= 2 {
                special = neighbors.filter { n in
                    n.card.owner != me && myEff(n.side) == effAt(board, n.index, n.side.opposite)
                }.map { Flip(cell: $0.index, cause: .same, wave: 0) }
            }
        }

        // 3) Plus (only if Same didn't trigger)
        if special.isEmpty && rules.plus && neighbors.count >= 2 {
            let bySum = Dictionary(grouping: neighbors) { n in
                myEff(n.side) + effAt(board, n.index, n.side.opposite)
            }
            special = bySum.values
                .filter { $0.count >= 2 }.flatMap { $0 }
                .filter { $0.card.owner != me }
                .map { Flip(cell: $0.index, cause: .plus, wave: 0) }
        }

        // 4) Combo cascade
        var work = board
        for f in basic + special { work = work.withOwner(f.cell, me) }

        var combo: [Flip] = []
        var frontier = special.map { $0.cell }
        var wave = 1
        while !frontier.isEmpty {
            var next: [Int] = []
            for src in frontier {
                for side in Side.allCases {
                    guard let dst = neighborIndex(src, side),
                          let target = work[dst].card,
                          target.owner != me else { continue }
                    if effAt(work, src, side) > effAt(work, dst, side.opposite) {
                        work = work.withOwner(dst, me)
                        combo.append(Flip(cell: dst, cause: .combo, wave: wave))
                        next.append(dst)
                    }
                }
            }
            frontier = next
            wave += 1
        }
        return basic + special + combo
    }
}
