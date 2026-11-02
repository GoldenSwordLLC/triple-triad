// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TriadCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    targets: [
        .target(name: "TriadCore", resources: [.copy("Art")]),
        .testTarget(name: "TriadCoreTests", dependencies: ["TriadCore"], path: "Tests/TriadCoreTests")
    ]
)
