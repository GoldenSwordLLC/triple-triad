# Triad — fan-made Triple Triad (FF8) for Android + iOS

Dual-native: Kotlin/Jetpack Compose (Android) + Swift/SwiftUI (iOS), two independent
rule engines kept in lockstep by a shared JSON parity-test suite. P2P multiplayer
(QR join over LAN TCP or Bluetooth LE) with no server, plus online play at any
distance through a private relay.

**Status: Android is playable end to end** — the Card Circuit campaign, pass & play,
two-phone Wi-Fi and Bluetooth matches, and online relay matches. The Swift core
passes its parity tests; the SwiftUI app layer awaits its first Xcode compile and
does not yet mirror the campaign.

## The Card Circuit (single player)

A 51-fight campaign across 16 chapters. Each chapter is a mini-arc: lesser
opponents (1–3, escalating with the chapter) building to a named boss. The run is
linear — one fight is open at a time, and every first-clear win pays specific cards,
so the whole 110-card catalog is collectible in single player alone.

- Own cast of characters inspired by FF8's card-community figures; bosses carry
  authored decks, dialogue, and portrait art; lessers use monogram plates.
- Rules escalate as you climb: elemental cells from chapter 5, Same from 7,
  Plus from 10, Same Wall from 14 — with every chapter's boss playing the full
  chapter ruleset.
- Replaying a defeated opponent drops one random card from their own deck.
- The ladder is test-enforced: every fight is solvable with the arsenal the
  campaign has paid out by that point, no lesser deck outguns its chapter boss,
  and starter ∪ all rewards covers the entire catalog.

## Build

```bash
./gradlew :core:test            # JVM engine + protocol tests
./gradlew :androidApp:assembleDebug   # debug APK
```

APK lands in `androidApp/build/outputs/apk/debug/`. Signed release APKs are built
locally and published on the [Releases](https://github.com/GoldenSwordLLC/triple-triad/releases)
page; CI only runs the test suites. The iOS core also builds and tests on Linux
(`cd ios/TriadCore && swift test`).

## Repo layout

| Path | Contents |
|---|---|
| `core/` | Pure-JVM engine: rules, AI, campaign ladder, save model, P2P session + protocol |
| `androidApp/` | Compose app: screens, BLE transport, QR scanner, camera, art cache |
| `ios/TriadCore/` | Swift engine mirror (parity-tested) |
| `ios/TriadApp/` | SwiftUI app layer (pending first compile) |
| `shared-data/` | JSON parity fixtures + card catalog shared by both engines |
| `tools/` | Catalog build/audit scripts |

## Game rules

Triple Triad's core: place a card, higher adjacent ranks capture. On top of that:

- **Elemental** — some cells carry an element; a matching card gets +1 to all
  ranks, a mismatched one −1. Green/red frames on placed cards show which.
- **Same / Plus / Combo** — capture by matching both touching ranks (Same), by
  equal sums on two pairs (Plus), and let captures cascade (Combo, the classic
  FF8 chain behavior).
- **Same Wall** — Same also applies to your own border wall.

In every multiplayer mode the host picks the active rules from checkboxes
(Same, Plus, Combo, Same Wall, Elemental); the joiner reviews them before the
match, and the game shows the live ruleset throughout.

Deliberate divergences from FF8:

- **Board-only scoring, no draws.** Hands don't count; with 9 board cards a tie is
  impossible, so every match ends decisively (5/4 is a win for whoever holds 5).
- **Single-instance decks.** A deck may contain each card at most once, regardless
  of owned copies.
- **Decks are 5 cards.** The deck you bring is the hand you play.
- **Multiplayer is zero-stakes.** No card trading or wagers — collection progress
  lives entirely in the campaign; matches are for the love of the game.

## Multiplayer protocol notes

Both phones derive identical match state from a shared seed:

1. Each side commits `SHA-256(nonce)` before revealing the nonce (commit/reveal —
   neither side can influence the deal after seeing the other's).
2. Shared seed = `SHA-256(hostNonce + joinerNonce)`; a fixed PRNG (`SplitMix64`)
   then deals random hands identically on both devices, and decides colors.
3. Declared decks are validated peer-to-peer (size, known cards, single-instance),
   and the host's chosen rules ride the same handshake to the joiner.
4. The transport seam (`Wire`) has four implementations: TCP over LAN Wi-Fi,
   BLE GATT, TLS to the online relay, and loopback for tests. Hosts advertise
   both radios; joiners try TCP first (3s bound) and fall back to Bluetooth
   when off-network.

Hosts keep accepting connections until a peer proves the session token in their
Hello (a port-scanner can't wedge hosting), and every transport caps line length
at 128 KB so a hostile peer drops the connection instead of exhausting memory.
Tokens and commit/reveal nonces come from a cryptographic RNG.

Notable hardening baked into the session layer: outbound queues drain before socket
close, the handshake is synchronous (Reveal can never overtake Commit), EOF is
delivered through the inbound queue as a sentinel, and AI turns are
snapshot+generation-fenced with a liveness watchdog so a match can never stall
on the opponent's turn.

## Online play (any distance)

Two phones can also play through a relay: the host gets a short room code,
the joiner types it, and both phones dial out, so it works behind any NAT.
Neither player's IP is ever exposed to the other, and a mid-match disconnect
awards the win to the remaining player. Room codes expire after 5 minutes.

The connection is TLS-only with the certificate chain pinned to Let's Encrypt's
root keys; the host signs its room with the device's ed25519 key (held in the
Android Keystore where hardware allows) and answers signed heartbeats while the
room waits. Online play is version-gated: the relay only accepts hosts and
joiners on the newest published release, so opponents always share a build —
old installs are told to update.

The relay is private infrastructure and is not distributed in this repo.

## Card art

Frames, names, ranks and element icons are baked into per-card JPEGs
(`androidApp/src/main/assets/art/cards/{red,blue}/NNN.jpg`, 375×525 each) and
decoded on demand through a small LRU cache. Card art by Tobias Vyseri
(https://vyseri.com). This is a fan project; Triple Triad is © Square Enix.

## License

Code is licensed under the GNU AGPL v3 — see [LICENSE.md](LICENSE.md).
Card art by Tobias Vyseri; Triple Triad is © Square Enix.
