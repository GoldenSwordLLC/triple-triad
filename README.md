# Triad — fan-made Triple Triad (FF8) for Android + iOS

Dual-native: Kotlin/Jetpack Compose (Android) + Swift/SwiftUI (iOS), two independent
rule engines kept in lockstep by a shared JSON parity-test suite. P2P multiplayer
(QR join over LAN TCP or Bluetooth LE) with no server. Single player vs AI with
four tiers (Easy / Medium / Hard / Expert), a card collection economy, packs, and
cross-phone trading.

**Status: Android is playable end to end** (single player, pass & play, two-phone
Wi-Fi and Bluetooth matches, collection/decks/packs/trading). The Swift core is
portable and CI-tested; the SwiftUI app layer awaits its first compile.

## Build

```bash
./gradlew :core:test            # JVM engine + protocol tests
./gradlew :androidApp:assembleDebug   # debug APK
```

APK lands in `androidApp/build/outputs/apk/debug/`. Release APKs are built by CI
when a `v*` tag is pushed. The iOS package builds/tests on macOS:
`cd ios/TriadCore && swift test`.

## Repo layout

| Path | Contents |
|---|---|
| `core/` | Pure-JVM engine: rules, AI, determinism, save model, P2P session + protocol |
| `androidApp/` | Compose app: screens, BLE transport, QR scanner, camera, art cache |
| `ios/TriadCore/` | Swift engine mirror (parity-tested) |
| `ios/TriadApp/` | SwiftUI app layer (pending first compile) |
| `shared-data/` | JSON parity fixtures shared by both engines |
| `design/` | Design mockups from the UI rounds |
| `tools/` | Catalog build/audit scripts |

## Game rules (deliberate divergences from FF8)

- **Board-only scoring, no draws.** Hands don't count; with 9 board cards a tie is
  impossible, so every match ends decisively (5/4 is a win for whoever holds 5).
- **Single-instance decks.** A deck may contain each card at most once, regardless
  of owned copies.
- **Decks are 5 cards.** The deck you bring is the hand you play.

## Collection economy

- Fixed deterministic starter (12 level-1 cards). Win single-player matches to earn
  packs: Easy 3×(L1–3), Medium 4×(L2–6, one guaranteed L4+), Hard 3×(L3–10, one
  guaranteed L7+, never the hidden five), Expert 2×(L9–10, one guaranteed hidden five).
- The hidden five (Doomtrain, Edea, Rinoa, Squall, Seifer) drop ONLY from Expert packs.
- Two-phone matches trade cards by the host's chosen rule (None/One/Direct/Diff/All);
  entry requires ≥6 owned cards so a worst-case loss can never leave you deckless.
- Per-card drop rates are exact (computed from the pack draw structure) and shown in
  the Collection screen; a test cross-checks them against sampled packs.

## Multiplayer protocol notes

Both phones derive identical match state from a shared seed:

1. Each side commits `SHA-256(nonce)` before revealing the nonce (commit/reveal —
   neither side can influence the deal after seeing the other's).
2. Shared seed = `SHA-256(hostNonce + joinerNonce)`; a fixed PRNG (`SplitMix64`)
   then deals random hands identically on both devices, and decides colors.
3. Declared decks are validated peer-to-peer (size, known cards, single-instance).
4. The transport seam (`Wire`) has three implementations: TCP over LAN Wi-Fi,
   BLE GATT, and loopback for tests. Hosts advertise both radios; joiners try
   TCP first (3s bound) and fall back to Bluetooth when off-network.

Notable hardening baked into the session layer: outbound queues drain before socket
close, the handshake is synchronous (Reveal can never overtake Commit), EOF is
delivered through the inbound queue as a sentinel, and AI turns are
snapshot+generation-fenced with a liveness watchdog so a match can never stall
on the opponent's turn.

## Online play (any distance)

Two phones can also play through a relay: the host gets a three-word code,
the joiner types it, and both phones dial out, so it works behind any NAT.
Neither player's IP is ever exposed to the other, and a mid-match
disconnect awards the win to the remaining player.

The relay is private infrastructure and is not distributed in this repo.
Operators configure its address in the app under Settings, ONLINE PLAY.

## Card art

Frames, names, ranks and element icons are baked into per-card JPEGs
(`androidApp/src/main/assets/art/cards/{red,blue}/NNN.jpg`, 375×525 each) and
decoded on demand through a small LRU cache. Card art by Tobias Vyseri
(https://vyseri.com). This is a fan project; Triple Triad is © Square Enix.

## License

Code is licensed under the GNU AGPL v3 — see [LICENSE.md](LICENSE.md).
Card art by Tobias Vyseri; Triple Triad is © Square Enix.
