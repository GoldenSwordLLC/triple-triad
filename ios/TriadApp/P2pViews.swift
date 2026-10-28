import AVFoundation
import CoreImage.CIFilterBuiltins
import SwiftUI
import TriadCore

// MARK: - QR generation

func qrImage(for payload: String) -> Image? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(payload.utf8)
    filter.correctionLevel = "M"
    guard let output = filter.outputImage else { return nil }
    let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
    #if canImport(UIKit)
    if let cg = CIContext().createCGImage(scaled, from: scaled.extent) {
        return Image(uiImage: UIImage(cgImage: cg)).interpolation(.none)
    }
    #elseif canImport(AppKit)
    if let rep = NSCIImageRep(ciImage: scaled), let ns = NSImage(rep: rep) {
        return Image(nsImage: ns).interpolation(.none)
    }
    #endif
    return nil
}

// MARK: - Host lobby

@MainActor
final class HostModel: ObservableObject {
    @Published var started = false
    @Published var payload: String? = nil
    @Published var error: String? = nil
    @Published var matchStarted: ((P2pSession, GameState, Owner) -> Void)? = nil

    private var listener: TcpMatchListener? = nil
    private var collected = false

    func start(name: String, trade: TradeRule, deckIds: [String], catalog: [String: CardDef]) {
        let token = (0..<4).map { _ in String(format: "%02x", Int.random(in: 0..<256)) }.joined()
        guard let listener = try? TcpMatchListener() else {
            error = "could not open a listener"; return
        }
        let ip = lanIpAddress()
        guard let ip else {
            error = "No Wi-Fi — both phones must share a network"; return
        }
        listener.onAccept = { [weak self] wire in
            Task { @MainActor in
                guard let self, !self.collected else { wire.close(); return }
                self.collected = true   // exactly ONE challenger
                self.listener?.cancel()
                let session = P2pSession(wire: wire, isHost: true, myName: name, token: token,
                                         catalog: catalog,
                                         me: .init(deckIds: deckIds, neverFace: [], pref: .any),
                                         hostSettings: MatchSettings(rules: Rules(), open: true,
                                                                     randomHands: false, suddenDeath: false,
                                                                     trade: trade))
                session.start()
                self.observe(session)
            }
        }
        listener.start()
        self.listener = listener
        payload = invitePayload(ip: ip, port: listener.port, name: name, token: token)
        started = true
    }

    private func observe(_ session: P2pSession) {
        Task { @MainActor in
            for await event in session.events {
                if case let .matchStarted(state, color) = event {
                    matchStarted?(session, state, color)
                    return
                }
                if case let .declined(detail) = event { error = detail }
                if case let .disconnected(detail) = event { error = detail }
                if case let .protocolError(code, detail) = event { error = "\(code): \(detail)" }
            }
        }
    }

    func shutdown() {
        listener?.cancel()
    }
}

struct HostLobbyView: View {
    let catalog: [String: CardDef]
    let deckIds: [String]
    let cardCount: Int
    let onMatchStarted: (P2pSession, GameState, Owner) -> Void
    let onExit: () -> Void

    @StateObject private var model = HostModel()
    @State private var name = "Host"
    @State private var trade: TradeRule = .one

    var body: some View {
        ZStack {
            MatBackground(dim: 0.24, bottomScrim: true)
            VStack(spacing: 12) {
                HStack {
                    Button("✕ Cancel") { model.shutdown(); onExit() }
                        .font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    Spacer()
                }
                if let payload = model.payload {
                    Text("SHOW THIS QR TO YOUR FRIEND").font(.system(size: 14, weight: .bold))
                        .foregroundColor(TriadPalette.cream)
                    if let img = qrImage(for: payload) {
                        img.resizable().scaledToFit()
                            .frame(maxWidth: .infinity).padding(.horizontal, 32)
                            .background(Color.white).cornerRadius(8)
                    }
                    Text("Waiting for a challenger to scan…").font(.system(size: 12)).foregroundColor(TriadPalette.cream)
                } else {
                    Text("HOST A MATCH").font(.system(size: 18, weight: .bold)).foregroundColor(TriadPalette.cream)
                    TextField("Your name", text: $name).textFieldStyle(.roundedBorder)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("TRADE RULE — what the loser gives up")
                            .font(.system(size: 11, weight: .bold)).foregroundColor(TriadPalette.label)
                        HStack(spacing: 6) {
                            ForEach([TradeRule.none, .one, .direct, .diff, .all], id: \.self) { rule in
                                let sel = trade == rule
                                let allowed = cardCount - rule.maxLoss >= 5
                                Text(rule.rawValue.uppercase())
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(sel && allowed ? TriadPalette.chipSelectedText
                                              : allowed ? TriadPalette.goldText : TriadPalette.disabled)
                                    .padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(sel && allowed ? TriadPalette.gold : TriadPalette.chipBg)
                                    .overlay(RoundedRectangle(cornerRadius: 10)
                                        .stroke(allowed ? TriadPalette.gold : TriadPalette.disabled.opacity(0.35),
                                                lineWidth: sel ? 2 : 1))
                                    .onTapGesture { if allowed { trade = rule } }
                            }
                        }
                        if cardCount - TradeRule.all.maxLoss < 5 {
                            Text("Grayed rules could leave you without a deck if you lose — win packs first.")
                                .font(.system(size: 10)).foregroundColor(TriadPalette.dim)
                        }
                    }
                    GoldButtonView("SHOW QR  ▶", filled: true) {
                        model.start(name: name, trade: trade, deckIds: deckIds, catalog: catalog)
                    }
                }
                if let err = model.error {
                    Text("ERROR: \(err)").font(.system(size: 13, weight: .bold))
                        .foregroundColor(Color(red: 1, green: 120/255, blue: 100/255))
                }
                Spacer()
            }.padding(16)
            if let started = model.matchStarted {
                PreMatchFlow(session: started.0, initialState: started.1, myColor: started.2,
                             onMatchStarted: onMatchStarted, onExit: onExit)
                    .background(Color.black.opacity(0.7))
            }
        }
    }
}

// MARK: - Join flow (camera scanner)

struct ScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onExit: () -> Void

    func makeUIViewController(context: Context) -> ScannerVC {
        ScannerVC(onCode: onCode, onExit: onExit)
    }
    func updateUIViewController(_ vc: ScannerVC, context: Context) {}
}

final class ScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    let onCode: (String) -> Void
    let onExit: () -> Void
    private let session = AVCaptureSession()

    init(onCode: @escaping (String) -> Void, onExit: @escaping () -> Void) {
        self.onCode = onCode
        self.onExit = onExit
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              obj.type == .qr, let value = obj.stringValue else { return }
        onCode(value)
    }
}

struct JoinFlowView: View {
    let catalog: [String: CardDef]
    let deckIds: [String]
    let cardCount: Int
    let onMatchStarted: (P2pSession, GameState, Owner) -> Void
    let onTransfers: ([CardTransfer], Owner) -> Void
    let onExit: () -> Void

    @State private var phase = "scan"   // scan | connecting | prematch
    @State private var session: P2pSession? = nil
    @State private var error: String? = nil

    var body: some View {
        ZStack {
            switch phase {
            case "scan":
                VStack(spacing: 0) {
                    HStack {
                        Button("✕ Cancel") { onExit() }
                            .font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                            .padding(12)
                        Spacer()
                    }
                    ScannerView(onCode: handleScan, onExit: onExit)
                }
                .ignoresSafeArea()
            case "connecting":
                VStack(spacing: 12) {
                    Text("Connecting…").font(.system(size: 16)).foregroundColor(TriadPalette.cream)
                    Button("Cancel") { onExit() }.foregroundColor(TriadPalette.goldText)
                }
            default:
                EmptyView()
            }
            if let session {
                PreMatchFlow(session: session, initialState: nil, myColor: nil,
                             onMatchStarted: onMatchStarted, onTransfers: onTransfers, onExit: onExit)
            }
            if let error {
                VStack {
                    Spacer()
                    Text(error).font(.system(size: 13, weight: .bold))
                        .foregroundColor(Color(red: 1, green: 140/255, blue: 120/255))
                        .padding(12).background(Color.black.opacity(0.7)).cornerRadius(8)
                }
            }
        }
    }

    private func handleScan(_ raw: String) {
        guard phase == "scan" else { return }
        guard let invite = parseInvite(raw), invite.transport == "tcp", let ip = invite.ip, let port = invite.port else {
            error = "That QR isn't a Triad Wi-Fi invite — try again."
            return
        }
        phase = "connecting"
        let wire = TcpWire(host: ip, port: UInt16(port))
        let s = P2pSession(wire: wire, isHost: false, myName: "Joiner", token: invite.token,
                           catalog: catalog,
                           me: .init(deckIds: deckIds, neverFace: [], pref: .any),
                           hostSettings: nil)
        s.start()
        session = s
        phase = "prematch"
    }
}

// MARK: - Pre-match (rules review + deck verify)

struct PreMatchFlow: View {
    let session: P2pSession
    let initialState: GameState?
    let myColor: Owner?
    var onMatchStarted: ((P2pSession, GameState, Owner) -> Void)? = nil
    var onTransfers: (([CardTransfer], Owner) -> Void)? = nil
    let onExit: () -> Void

    @State private var phase = "prematch"   // prematch | match
    @State private var state: GameState? = nil

    var body: some View {
        Group {
            if phase == "match", let state {
                P2pMatchView(session: session, initialState: state,
                             myColor: session.myColor, onTransfers: onTransfers, onExit: onExit)
            } else {
                PrematchBody(session: session, onMatchStarted: { st in
                    state = st
                    phase = "match"
                    onMatchStarted?(session, st, session.myColor)
                }, onExit: onExit)
            }
        }
        .onAppear {
            if let initialState { state = initialState; phase = "match" }
        }
    }
}

struct PrematchBody: View {
    let session: P2pSession
    let onMatchStarted: (GameState) -> Void
    let onExit: () -> Void

    @State private var ui = "connecting…"

    var body: some View {
        VStack(spacing: 16) {
            Text("MULTIPLAYER").font(.system(size: 18, weight: .bold)).foregroundColor(TriadPalette.goldText)
            Text(ui).font(.system(size: 14)).foregroundColor(TriadPalette.cream)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
            Button("✕ Leave") { session.resign(); onExit() }
                .font(.system(size: 13, weight: .bold)).foregroundColor(TriadPalette.goldText)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task {
            for await event in session.events {
                switch event {
                case .connected(let peer):
                    ui = "Connected to \(peer)…"
                case .rulesProposed(let settings):
                    ui = "Rules proposed: trade \(settings.trade.rawValue)\nReviewing…"
                    session.acceptRules()
                case .peerAccepted:
                    ui = "Peer accepted — exchanging decks…"
                case .verifyDecks:
                    ui = "Decks verified — ready…"
                    session.ready()
                case .matchStarted(let state, _):
                    onMatchStarted(state)
                    return
                case .declined(let detail):
                    ui = "Declined: \(detail)"
                case .disconnected(let detail):
                    ui = "Disconnected: \(detail)"
                case .protocolError(let code, let detail):
                    ui = "Error \(code): \(detail)"
                default:
                    break
                }
            }
        }
    }
}

// MARK: - P2P match

@MainActor
final class P2pMatchModel: ObservableObject {
    @Published var state: GameState
    @Published var lastFlips: [Flip] = []
    @Published var ended: String? = nil
    @Published var transfers: [CardTransfer] = []
    @Published var pendingPick: PendingPick? = nil
    @Published var result: String? = nil

    let session: P2pSession
    private var onTransfers: (([CardTransfer], Owner) -> Void)?

    init(session: P2pSession, initialState: GameState, onTransfers: (([CardTransfer], Owner) -> Void)?) {
        self.session = session
        self.state = initialState
        self.onTransfers = onTransfers
        Task { @MainActor in
            for await event in session.events {
                switch event {
                case .matchStarted(let s, _): state = s
                case .moveApplied(_, let s): state = s
                case .gameOver(let outcome):
                    switch outcome {
                    case .won(let t, let pick):
                        result = "VICTORY"
                        transfers = t
                        pendingPick = pick
                    case .lost: result = "DEFEAT"
                    case .drawn: result = "DRAW"
                    }
                case .tradeResolved(let t):
                    transfers = t
                    pendingPick = nil
                    onTransfers?(t, session.myColor)
                    session.teardown()
                case .forfeit(_, let reason): ended = reason
                case .declined(let detail): ended = detail
                case .disconnected(let detail): ended = detail
                case .protocolError(let code, let detail): ended = "\(code): \(detail)"
                default: break
                }
            }
        }
    }

    var myTurn: Bool { !state.isOver && state.turn == session.myColor }
}

struct P2pMatchView: View {
    let session: P2pSession
    let initialState: GameState
    let myColor: Owner
    var onTransfers: (([CardTransfer], Owner) -> Void)? = nil
    let onExit: () -> Void

    @StateObject private var model: P2pMatchModel
    @State private var selected: Int? = nil
    @State private var confirmExit = false

    init(session: P2pSession, initialState: GameState, myColor: Owner,
         onTransfers: (([CardTransfer], Owner) -> Void)? = nil, onExit: @escaping () -> Void) {
        self.session = session
        self.initialState = initialState
        self.myColor = myColor
        self.onTransfers = onTransfers
        self.onExit = onExit
        _model = StateObject(wrappedValue: P2pMatchModel(session: session, initialState: initialState,
                                                         onTransfers: onTransfers))
    }

    var body: some View {
        let state = model.state
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
                HandRowView(hand: state.hand(myColor.other), owner: myColor.other,
                            selected: nil, active: false, flipped: false) { _ in }
                TurnPill(text: state.isOver ? "MATCH OVER"
                          : model.myTurn ? "YOUR TURN" : "OPPONENT'S TURN",
                         color: model.myTurn && !state.isOver ? TriadPalette.frameBlue : TriadPalette.slabBrown)
                BoardAreaView(state: state, flips: model.lastFlips,
                              selectActive: selected != nil && model.myTurn,
                              onTapCell: { cell in
                                  if let sel = selected, model.myTurn {
                                      selected = nil
                                      session.play(sel, cell: cell)
                                  }
                              },
                              onInfo: { _ in })
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                HandRowView(hand: state.hand(myColor), owner: myColor,
                            selected: model.myTurn ? selected : nil,
                            active: model.myTurn, flipped: false) { idx in
                    if model.myTurn { selected = selected == idx ? nil : idx }
                }
            }.padding(.horizontal, 8)

            if let ended = model.ended {
                popup("MATCH ENDED", ended) { session.teardown(); onExit() }
            } else if let pick = model.pendingPick {
                TradePicker(pick: pick) { ids in session.tradePick(ids) }
            } else if let result = model.result {
                let gained = model.transfers.filter { $0.to == myColor }.map { $0.card }
                popup(result, gained.isEmpty ? "" : "Cards gained: \(gained.map { $0.name }.joined(separator: ", "))") {
                    session.teardown(); onExit()
                }
            }
        }
        .alert("Leave this match?", isPresented: $confirmExit) {
            Button("LEAVE", role: .destructive) { session.resign(); onExit() }
            Button("STAY", role: .cancel) {}
        } message: { Text("Leaving now counts as a resignation — your opponent will be awarded the win.") }
    }

    private func popup(_ title: String, _ detail: String, onDone: @escaping () -> Void) -> some View {
        VStack(spacing: 12) {
            Text(title).font(.system(size: 26, weight: .bold)).foregroundColor(TriadPalette.goldText)
            if !detail.isEmpty { Text(detail).font(.system(size: 13)).foregroundColor(TriadPalette.label) }
            Button("DONE  ▶") { onDone() }
                .font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(TriadPalette.panelBrown)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .padding(24)
        .background(Color(red: 30/255, green: 22/255, blue: 8/255))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(TriadPalette.gold, lineWidth: 2))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 32)
    }
}

struct TradePicker: View {
    let pick: PendingPick
    let onConfirm: ([String]) -> Void
    @State private var selected: [Int] = []

    var body: some View {
        VStack(spacing: 12) {
            Text("VICTORY").font(.system(size: 24, weight: .bold)).foregroundColor(Color(red: 46/255, green: 125/255, blue: 50/255))
            Text(pick.count == pick.candidates.count
                 ? "Review the cards you're taking:"
                 : "Choose \(pick.count) card\(pick.count > 1 ? "s" : ""):")
                .font(.system(size: 13)).foregroundColor(TriadPalette.label)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(pick.candidates.indices, id: \.self) { i in
                    CardThumbView(def: pick.candidates[i], owner: .red)
                        .aspectRatio(0.714, contentMode: .fit)
                        .overlay(RoundedRectangle(cornerRadius: 6)
                            .stroke(selected.contains(i) ? TriadPalette.gold : .clear, lineWidth: 3))
                        .onTapGesture {
                            if let idx = selected.firstIndex(of: i) { selected.remove(at: idx) }
                            else if selected.count < pick.count { selected.append(i) }
                        }
                }
            }
            Button {
                onConfirm(selected.map { pick.candidates[$0].id })
            } label: {
                Text("TAKE \(selected.count)/\(pick.count)")
                    .font(.system(size: 14, weight: .bold)).foregroundColor(TriadPalette.goldText)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(TriadPalette.panelBrown)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(TriadPalette.gold, lineWidth: 2))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }.disabled(selected.count != pick.count).buttonStyle(.plain)
        }
        .padding(20)
        .background(Color(red: 30/255, green: 22/255, blue: 8/255).opacity(0.97))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(TriadPalette.gold, lineWidth: 2))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(16)
    }
}
