#if canImport(CoreGraphics)
import CoreGraphics
import Foundation
import ImageIO
#else
import Foundation
public typealias CGFloat = Double
#endif

/// Per-card art: individual 375x525 JPEGs at Art/cards/{red,blue}/NNN.jpg (001 = Geezard
/// .. 110), loaded on demand through an NSCache. Frames, names, ranks and element icons
/// are baked into the art. Replaces the sprite sheets (a sheet of these cells would
/// decode to ~86MB; per-card loading keeps memory proportional to what's on screen).
public enum CardArt {
    public static let cardW: CGFloat = 375
    public static let cardH: CGFloat = 525
    public static let aspect: CGFloat = cardW / cardH   // 0.714

    #if canImport(CoreGraphics)
    private static let cache = NSCache<NSString, CGImage>()
    #endif

    /// No clamping: out-of-range numbers must FAIL to load, not silently render a
    /// neighbor (tests assert the excluded 111/112 art is truly absent).
    private static func resourceName(_ cardNo: Int, _ owner: Owner) -> String {
        let color = owner == .blue ? "blue" : "red"
        return "cards/\(color)/\(String(format: "%03d", cardNo))"
    }

    #if canImport(CoreGraphics)
    public static func peek(_ cardNo: Int, _ owner: Owner) -> CGImage? {
        cache.object(forKey: resourceName(cardNo, owner) as NSString)
    }

    public static func load(_ cardNo: Int, _ owner: Owner, bundle: Bundle? = nil) -> CGImage? {
        let name = resourceName(cardNo, owner)
        if let hit = cache.object(forKey: name as NSString) { return hit }
        let bundle = bundle ?? .module
        guard let url = bundle.url(forResource: name, withExtension: "jpg", subdirectory: "Art"),
              let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        cache.setObject(image, forKey: name as NSString)
        return image
    }

    public static func playmat(bundle: Bundle? = nil) -> CGImage? {
        let bundle = bundle ?? .module
        guard let url = bundle.url(forResource: "playmat", withExtension: "jpg", subdirectory: "Art"),
              let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        return image
    }
    #else
    public static func peek(_ cardNo: Int, _ owner: Owner) -> Data? { nil }
    public static func load(_ cardNo: Int, _ owner: Owner, bundle: Bundle? = nil) -> Data? {
        guard let url = (bundle ?? .module).url(forResource: resourceName(cardNo, owner),
                                                withExtension: "jpg", subdirectory: "Art")
        else { return nil }
        return try? Data(contentsOf: url)
    }
    public static func playmat(bundle: Bundle? = nil) -> Data? {
        guard let url = (bundle ?? .module).url(forResource: "playmat",
                                                withExtension: "jpg", subdirectory: "Art")
        else { return nil }
        return try? Data(contentsOf: url)
    }
    #endif
}

/// The card catalog (stats + ids), loaded from the bundled cards.json.
public struct CardCatalog {
    public private(set) var cards: [CardDef] = []
    public private(set) var byId: [String: CardDef] = [:]

    private struct CatalogFile: Codable { let cards: [CardDef] }

    public init(bundle: Bundle? = nil) {
        let bundle = bundle ?? .module
        if let url = bundle.url(forResource: "cards", withExtension: "json", subdirectory: "Art"),
           let data = try? Data(contentsOf: url),
           let file = try? JSONDecoder().decode(CatalogFile.self, from: data) {
            cards = file.cards
            byId = Dictionary(cards.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        }
    }
}
