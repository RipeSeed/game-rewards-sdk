// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "GameRewards",
    // ponytail: macOS is listed only so `swift test` runs the hashing tests on the Mac without a simulator.
    // The UI half is behind `#if canImport(UIKit)`; drop macOS if we ever test on a simulator instead.
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [.library(name: "GameRewards", targets: ["GameRewards"])],
    targets: [
        .target(name: "GameRewards"),
        .testTarget(name: "GameRewardsTests", dependencies: ["GameRewards"]),
    ]
)
