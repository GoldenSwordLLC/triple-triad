import Foundation

public protocol Wire: AnyObject {
    var onLine: ((String) -> Void)? { get set }
    var onClose: (() -> Void)? { get set }
    func send(_ line: String)
    func close()
}

struct DeckVerificationView: Sendable {
    let peerName: String, myDeck: [CardDef], peerDeck: [CardDef]
    let bannedByMe: [CardDef], bannedByThem: [CardDef]
    let myColor: Owner, randomHands: Bool
}

enum SessionEvent: Sendable {
    case connected(peerName: String)
    case rulesProposed(MatchSettings)
    case peerAccepted
    case verifyDecks(DeckVerificationView)
    case matchStarted(state: GameState, myColor: Owner)
    case moveApplied(flips: [Flip], state: GameState)
    case rematchStarted(state: GameState)
    case gameOver(outcome: Outcome)
    case tradeResolved(transfers: [CardTransfer])
    case forfeit(winner: Owner, reason: String)
    case declined(detail: String)
    case disconnected(detail: String)
    case protocolError(code: String, detail: String)

    enum Outcome: Sendable {
        case won(transfers: [CardTransfer], pendingPick: PendingPick?)
        case lost
        case drawn
    }
}

struct SessionFault: Error { let code: String; let detail: String }
struct DeckViolation: Error { let cardId: String }

struct DeckReferee {
    private let declared: [String: Int]
    private var played: [String: Int] = [:]
    init(declared: [String: Int]) { self.declared = declared }
    mutating func checkAndConsume(_ id: String) throws {
        if (declared[id] ?? 0) - (played[id] ?? 0) <= 0 { throw DeckViolation(cardId: id) }
        played[id, default: 0] += 1
    }
}

enum ColorResolution {
    // Must match Kotlin resolveColors exactly — pinned by color_resolution_matrix.json.
    static func resolve(hostPref: ColorPref, joinerPref: ColorPref, coin: Int) -> (Owner, Owner) {
        func concrete(_ p: ColorPref) -> Owner { p == .blue ? .blue : .red }
        func contested() -> (Owner, Owner) { coin == 0 ? (.blue, .red) : (.red, .blue) }
        switch (hostPref, joinerPref) {
        case (.any, .any):   return contested()
        case (.any, _):      return (concrete(joinerPref).other, concrete(joinerPref))
        case (_, .any):      return (concrete(hostPref), concrete(hostPref).other)
        case (.blue, .blue), (.red, .red): return contested()
        default:             return (concrete(hostPref), concrete(joinerPref))
        }
    }
}

actor P2pSession {
    struct PlayerLocal: Sendable {
        var deckIds: [String]
        var neverFace: [String]
        var pref: ColorPref
    }
    private struct DeckDecl: Sendable { let ids: [String]; let bans: [String]; let pref: ColorPref }

    private let wire: Wire
    private let isHost: Bool
    private let myName: String
    private let token: String
    private let catalog: [String: CardDef]
    private var me: PlayerLocal
    private let hostSettings: MatchSettings?

    private let eventsCont: AsyncStream<SessionEvent>.Continuation
    public let events: AsyncStream<SessionEvent>
    private let lineCont: AsyncStream<String>.Continuation
    private let lineStream: AsyncStream<String>

    public private(set) var peerName = "?"
    var deckSize: Int { me.deckIds.count }
    private var deckSent = false
    private let myNonce = randomHex(16)
    private var peerCommit: String?
    private var revealed = false
    private var seedHex = ""
    private var seed: UInt64 = 0
    private var settings: MatchSettings?
    private var peerDeck: DeckDecl?

    private var state: GameState?
    public internal(set) var myColor = Owner.blue
    private var seq = 0
    private var referee: DeckReferee?
    private var broughtBlue: [CardDef] = []
    private var broughtRed: [CardDef] = []
    private var pendingPick: PendingPick?
    private var concluded = false   // GameOver delivered — a socket close now is cleanup, not an error
    private var myAckSent = false
    private var peerAcked = false
    private var done = false

    public init(wire: Wire, isHost: Bool, myName: String, token: String,
         catalog: [String: CardDef], me: PlayerLocal, hostSettings: MatchSettings? = nil) {
        precondition(me.deckIds.count >= 5 && me.deckIds.count <= 10, "deck must be 5–10 cards")
        for id in me.deckIds { precondition(catalog[id] != nil, "unknown card in deck: \(id)") }
        self.wire = wire; self.isHost = isHost; self.myName = myName; self.token = token
        self.catalog = catalog; self.me = me; self.hostSettings = hostSettings

        var ec: AsyncStream<SessionEvent>.Continuation!
        self.events = AsyncStream(bufferingPolicy: .unbounded) { ec = $0 }
        self.eventsCont = ec
        var lc: AsyncStream<String>.Continuation!
        self.lineStream = AsyncStream(bufferingPolicy: .unbounded) { lc = $0 }
        self.lineCont = lc
    }

    func start() {
        let lc = lineCont
        wire.onLine = { line in lc.yield(line) }
        // EOF must be handled IN ORDER, after every queued line: a close event that overtakes
        // the final Move/GameOver would fault a healthy session with "connection lost".
        wire.onClose = { lc.yield(P2pSession.closedSentinel) }
        send(.hello(name: myName, token: token, proto: 1))
        send(.commit(hash: sha256Hex(myNonce)))
        Task { await self.consumeLines() }
    }

    private static let closedSentinel = "\u{0}__wire_closed__"

    private func consumeLines() async {
        for await line in lineStream {
            if line == Self.closedSentinel {
                if !concluded { terminate(.disconnected(detail: "connection lost")) }
                return
            }
            guard let msg = decodeMessage(line) else {
                terminate(.protocolError(code: "json", detail: String(line.prefix(80)))); return
            }
            do { try handle(msg) }
            catch let f as SessionFault {
                send(.error(code: f.code, detail: f.detail))
                terminate(.protocolError(code: f.code, detail: f.detail)); return
            } catch {
                terminate(.protocolError(code: "internal", detail: "\(error)")); return
            }
        }
    }

    // MARK: - inbound

    private func handle(_ m: Message) throws {
        switch m {
        case let .hello(name, token, proto):
            guard proto == 1 else { try fault("proto", "version \(proto)") }
            if isHost, token != self.token { try fault("token", "wrong join token") }
            peerName = name
            eventsCont.yield(.connected(peerName: name))

        case let .commit(hash):
            peerCommit = hash
            send(.reveal(nonce: myNonce))              // reveal only AFTER their commit

        case let .reveal(nonce):
            guard sha256Hex(nonce) == peerCommit else { try fault("commit", "reveal ≠ commit") }
            guard !revealed else { try fault("protocol", "double reveal") }
            revealed = true
            seedHex = sha256Hex(isHost ? myNonce + nonce : nonce + myNonce)   // host's bytes first
            seed = seedFromHex(seedHex)
            if isHost {
                guard let s = hostSettings else { try fault("host", "settings missing") }
                settings = s
                send(.settings(settings: s))
            }

        case let .settings(s):
            guard !isHost else { try fault("protocol", "settings from joiner") }
            guard s.randomHands || me.deckIds.count == 5 else {
                try fault("deck", "host requires 5-card decks; yours is \(me.deckIds.count)")
            }
            settings = s
            eventsCont.yield(.rulesProposed(s))

        case .accept:
            guard isHost else { try fault("protocol", "accept from joiner") }
            eventsCont.yield(.peerAccepted)
            deckSent = true
            send(.deck(ids: me.deckIds, bans: me.neverFace, pref: me.pref))

        case .decline:
            terminate(.declined(detail: "Opponent declined"))

        case let .deck(ids, bans, pref):
            guard settings != nil else { try fault("protocol", "deck before settings") }
            guard peerDeck == nil else { try fault("protocol", "duplicate deck") }
            let d = DeckDecl(ids: ids, bans: bans, pref: pref)
            try validatePeerDeck(d)
            peerDeck = d
            eventsCont.yield(.verifyDecks(buildVerification()))

        case .deckAck:
            peerAcked = true
            maybeBegin()

        case let .move(handIndex, cell, card, seq, hash):
            try onPeerMove(handIndex: handIndex, cell: cell, card: card, seq: seq, hash: hash)

        case let .tradePick(ids):
            try onTradePick(ids)

        case .resign:
            terminate(.forfeit(winner: myColor, reason: "Opponent resigned"))

        case let .error(code, detail):
            terminate(.protocolError(code: code, detail: detail))
        }
    }

    private func validatePeerDeck(_ d: DeckDecl) throws {
        let s = settings!
        let ok = s.randomHands ? (d.ids.count >= 5 && d.ids.count <= 10) : d.ids.count == 5
        guard ok else { try fault("deck", "size \(d.ids.count)") }
        for id in d.ids where catalog[id] == nil { try fault("deck", "unknown card \(id)") }
        for id in d.bans where catalog[id] == nil { try fault("deck", "unknown ban \(id)") }
    }

    private func maybeBegin() {
        guard myAckSent, peerAcked, state == nil, let deck = peerDeck else { return }
        state = buildMatch()
        referee = DeckReferee(declared: Dictionary(grouping: deck.ids, by: { $0 }).mapValues(\.count))
        eventsCont.yield(.matchStarted(state: state!, myColor: myColor))
    }

    // MARK: - determinism contract (identical to the Kotlin session)
    // Round 1, seed = SplitMix64(first 16 hex of SHA-256(hostNonce ‖ joinerNonce)):
    //   1. color coin — nextInt(2), ALWAYS consumed
    //   2. if elemental: cells 0..8, each: nextInt(5); if 0 → nextInt(9)+1 (Element ordinal)
    //   3. if randomHands: BLUE's pool sampled (5 × nextInt(remaining)), then RED's
    //   4. first player — nextInt(2)
    // Round r>1: seed = SplitMix64(first 16 hex of SHA-256(seedHex + ":" + r)); only step 2.

    private func buildMatch() -> GameState {
        var r = SplitMix64(seed: seed)
        let coin = r.nextInt(2)
        let hostPref = isHost ? me.pref : peerDeck!.pref
        let joinerPref = isHost ? peerDeck!.pref : me.pref
        let (hostColor, joinerColor) = ColorResolution.resolve(hostPref: hostPref, joinerPref: joinerPref, coin: coin)
        myColor = isHost ? hostColor : joinerColor
        let st = settings!
        var cells: [Cell] = []
        for _ in 0..<9 {
            if st.rules.elemental && r.nextInt(5) == 0 { cells.append(Cell(element: Element.allCases[r.nextInt(9) + 1])) }
            else { cells.append(Cell()) }
        }
        let hostPool = isHost ? me.deckIds : peerDeck!.ids
        let joinerPool = isHost ? peerDeck!.ids : me.deckIds
        func sample(_ pool: [String]) -> [String] {
            st.randomHands && pool.count > 5 ? r.sample5(pool) : Array(pool.prefix(5))
        }
        let blueHand = sample(hostColor == .blue ? hostPool : joinerPool).map { catalog[$0]! }   // BLUE first
        let redHand  = sample(hostColor == .red  ? hostPool : joinerPool).map { catalog[$0]! }
        let first = r.nextInt(2) == 0 ? Owner.blue : Owner.red
        broughtBlue = blueHand; broughtRed = redHand
        return GameState(board: Board(cells: cells), blueHand: blueHand, redHand: redHand,
                         turn: first, rules: st.rules)
    }

    private func buildVerification() -> DeckVerificationView {
        let hostPref = isHost ? me.pref : peerDeck!.pref
        let joinerPref = isHost ? peerDeck!.pref : me.pref
        // Throwaway rng — same seed, same first draw: agrees with buildMatch, disturbs nothing.
        var verificationRng = SplitMix64(seed: seed)
        let (h, j) = ColorResolution.resolve(hostPref: hostPref, joinerPref: joinerPref,
                                             coin: verificationRng.nextInt(2))
        let myDeck = me.deckIds.map { catalog[$0]! }
        let theirDeck = peerDeck!.ids.map { catalog[$0]! }
        return DeckVerificationView(
            peerName: peerName, myDeck: myDeck, peerDeck: theirDeck,
            bannedByMe: theirDeck.filter { me.neverFace.contains($0.id) },
            bannedByThem: myDeck.filter { peerDeck!.bans.contains($0.id) },
            myColor: isHost ? h : j, randomHands: settings!.randomHands)
    }

    // MARK: - my moves

    public func acceptRules() {                      // joiner, after reviewing the proposal
        send(.accept)
        deckSent = true
        send(.deck(ids: me.deckIds, bans: me.neverFace, pref: me.pref))
    }
    func switchDeck(_ ids: [String]) { precondition(!deckSent, "deck locked"); me.deckIds = ids }
    public func ready() { send(.deckAck); myAckSent = true; maybeBegin() }
    func decline() { send(.decline); terminate(.declined(detail: "You declined")) }
    public func resign() { send(.resign); terminate(.forfeit(winner: myColor.other, reason: "You resigned")) }

    func play(handIndex: Int, cell: Int) {
        guard !done, let s = state else { return }
        guard !s.isOver, s.turn == myColor, cell >= 0, cell < 9, s.board[cell].card == nil else { return }
        let hand = s.hand(myColor)
        guard handIndex >= 0, handIndex < hand.count else { return }
        let card = hand[handIndex]
        let res = Engine.place(s, cardIndex: handIndex, cellIndex: cell)
        state = res.state; seq += 1
        send(.move(handIndex: handIndex, cell: cell, card: card.id,
                   seq: seq, hash: canonical(res.state, seq)))
        eventsCont.yield(.moveApplied(flips: res.flips, state: res.state))
        if res.state.isOver { resolveEnd() }
    }

    // MARK: - peer moves: validate EVERYTHING

    private func onPeerMove(handIndex: Int, cell: Int, card: String, seq: Int, hash: String) throws {
        guard let s = state else { try fault("protocol", "move before match") }
        if seq != self.seq + 1 { try fault("seq", "expected \(self.seq + 1), got \(seq)") }
        if s.isOver { try fault("protocol", "move after game over") }
        if s.turn != myColor.other { try fault("turn", "move out of turn") }
        if cell < 0 || cell > 8 || s.board[cell].card != nil { try fault("cell", "cell \(cell)") }
        let theirHand = s.hand(myColor.other)
        if handIndex < 0 || handIndex >= theirHand.count { try fault("hand", "index \(handIndex)") }
        do { try referee?.checkAndConsume(card) }
        catch let v as DeckViolation {
            send(.error(code: "deck_violation", detail: v.cardId))
            terminate(.forfeit(winner: myColor,
                               reason: "Opponent played an undeclared card: \(v.cardId)"))
            return
        }
        guard theirHand[handIndex].id == card else { try fault("hand", "card outside declared hand") }
        let res = Engine.place(s, cardIndex: handIndex, cellIndex: cell)
        self.seq += 1
        guard canonical(res.state, self.seq) == hash else { try fault("desync", "engines disagree") }
        state = res.state
        eventsCont.yield(.moveApplied(flips: res.flips, state: res.state))
        if res.state.isOver { resolveEnd() }
    }

    // MARK: - endgame, trades, sudden death

    private func resolveEnd() {
        guard let s = state, let st = settings else { return }
        let blue = s.score(.blue), red = s.score(.red)
        // Board-only scoring: 9 board cards can never split evenly — there is always a winner.
        let winner: Owner = blue > red ? .blue : .red
        concluded = true
        do {
            let iWon = winner == myColor
            let loserBrought = winner == .blue ? broughtRed : broughtBlue
            switch st.trade {
            case .direct:   // originalOwner is public — no messages
                let t = s.board.cells.compactMap { $0.card }
                    .filter { $0.owner != $0.originalOwner }
                    .map { CardTransfer(card: $0.def, from: $0.originalOwner, to: $0.owner) }
                eventsCont.yield(.gameOver(outcome: iWon ? .won(transfers: t, pendingPick: nil) : .lost))
                eventsCont.yield(.tradeResolved(transfers: t))
            case .none:
                eventsCont.yield(.gameOver(outcome: iWon ? .won(transfers: [], pendingPick: nil) : .lost))
            default:
                let n: Int
                switch st.trade {
                case .one: n = 1
                case .diff: n = min(abs(blue - red), loserBrought.count)
                default: n = loserBrought.count
                }
                if iWon {
                    pendingPick = PendingPick(count: n, candidates: loserBrought)
                    eventsCont.yield(.gameOver(outcome: .won(transfers: [], pendingPick: pendingPick)))
                } else {
                    eventsCont.yield(.gameOver(outcome: .lost))
                }
            }
        }
    }

    public func tradePick(_ ids: [String]) {          // winner side
        guard !done, let p = pendingPick else { return }
        let counts = Dictionary(grouping: p.candidates.map(\.id), by: { $0 }).mapValues(\.count)
        let picked = Dictionary(grouping: ids, by: { $0 }).mapValues(\.count)
        guard ids.count == p.count,
              picked.allSatisfy({ (counts[$0.key] ?? 0) >= $0.value }) else { return }
        send(.tradePick(ids: ids))
        pendingPick = nil
        eventsCont.yield(.tradeResolved(
            transfers: ids.map { CardTransfer(card: catalog[$0]!, from: myColor.other, to: myColor) }))
    }

    private func onTradePick(_ ids: [String]) throws {   // loser side: validate their pick
        guard let s = state else { try fault("protocol", "trade before match") }
        guard s.isOver else { try fault("protocol", "trade while playing") }
        let blue = s.score(.blue), red = s.score(.red)
        let winner: Owner = blue > red ? .blue : .red
        guard winner == myColor.other else { try fault("protocol", "tradePick but I won") }
        let mine = myColor == .blue ? broughtBlue : broughtRed
        let n: Int
        switch settings!.trade {
        case .one: n = 1
        case .diff: n = min(abs(blue - red), mine.count)
        default: n = mine.count
        }
        guard ids.count == n else { try fault("trade", "picked \(ids.count), expected \(n)") }
        let counts = Dictionary(grouping: mine.map(\.id), by: { $0 }).mapValues(\.count)
        let picked = Dictionary(grouping: ids, by: { $0 }).mapValues(\.count)
        for (id, c) in picked where (counts[id] ?? 0) < c { try fault("trade", "picked outside my hand") }
        eventsCont.yield(.tradeResolved(
            transfers: ids.map { CardTransfer(card: catalog[$0]!, from: myColor, to: myColor.other) }))
    }


    // MARK: - plumbing

    private func canonical(_ s: GameState, _ seq: Int) -> String {
        var out = "\(seq)|"
        for (i, c) in s.board.cells.enumerated() {
            if i > 0 { out += ";" }
            if let p = c.card {
                out += p.def.id
                out += p.originalOwner == .blue ? "b" : "r"
                out += p.owner == .blue ? "b" : "r"
            } else { out += "-" }
            out += ":\(Element.allCases.firstIndex(of: c.element)!)"   // declaration order == Kotlin ordinal
        }
        return out
    }

    private func fault(_ code: String, _ detail: String) throws -> Never {
        throw SessionFault(code: code, detail: detail)
    }
    private func send(_ m: Message) {
        guard !done else { return }
        wire.send(encodeMessage(m))
    }
    private func terminate(_ e: SessionEvent) {
        guard !done else { return }
        done = true
        wire.close()
        eventsCont.yield(e)
        eventsCont.finish()
        lineCont.finish()
    }
    public func teardown() {   // clean exit after the result screen — no terminal event
        guard !done else { return }
        done = true
        wire.close()
        eventsCont.finish()
        lineCont.finish()
    }
}
