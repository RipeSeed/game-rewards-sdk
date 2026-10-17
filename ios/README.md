# GameRewards iOS SDK

One button in your app opens the Game Rewards portal. Apple App Attest proves each launch comes from your genuine app; our server runs the games, holds the points and hands out your promo codes.

iOS 15+, Swift package, no dependencies (DeviceCheck, CryptoKit, WebKit, UIKit).

## Add it

Xcode: **File > Add Package Dependencies...**, then pick this repo (or **Add Local...** and choose `sdk/ios`). Add the `GameRewards` library to your app target.

Or in a `Package.swift`:

```swift
.package(path: "../game-rewards/sdk/ios") // or .package(url: "<repo url>", branch: "main")
// target dependency: .product(name: "GameRewards", package: "GameRewards")
```

## Use it

```swift
import GameRewards

// Once, at app start (AppDelegate or your SwiftUI App's init)
GameRewards.configure(.init(clientId: "demo", baseURL: URL(string: "https://rewards.example.com")!))

// From your Play & Earn button
GameRewards.open(from: viewController, userId: currentUser.id) { error in
    if let error { print("Game Rewards:", error.localizedDescription) }
}
```

- `userId` is your own user id: 1-128 chars of `A-Z a-z 0-9 . _ : @ -`.
- `completion` runs on the main thread: `nil` once the portal is on screen, otherwise the error. Server errors arrive as `GameRewardsError` with `code` (`attestation_failed`, `rate_limited`, ...), `message` and, for attestation failures, `reason`.
- The portal opens full screen in a `WKWebView` with a Close button. Only the portal's own origin loads inside; other links open in Safari.
- SwiftUI has no view controller to pass: use the key window's topmost one, as `Demo/DemoApp.swift` does.

What happens on `open`: the first time per user on this install (and per client and server host), the SDK makes an App Attest key (Apple: never share one key between users), asks the server for a challenge, has Apple attest the key and registers it (`POST /v1/attest/register`). Every launch then signs `SHA-256("clientId|userId|nonce|ts")` with that key and sends it to `POST /v1/launch`. If the server no longer knows the key (`unknown_key`) or the device lost it, the SDK registers a fresh key and retries once. On devices without App Attest, the launch goes out without a valid proof, and your partner setting `onAttestFail` decides: a play-only session (can play, never earns) or an error.

## Add the App Attest capability

In your target: **Signing & Capabilities > + Capability > App Attest**. That adds this entitlement:

```xml
<key>com.apple.developer.devicecheck.appattest-environment</key>
<string>development</string>   <!-- production for App Store / TestFlight builds -->
```

Send us your **Team ID**, **bundle ID** and the environment. They go in your partner config on the server (`"ios": { "teamId", "bundleId", "environment" }`). Use `development` for builds you run from Xcode. TestFlight and App Store builds always attest in `production`, whatever the entitlement says. The server accepts one environment per partner, so switch it when you move from Xcode builds to TestFlight.

## Try it in the simulator

The simulator has no App Attest, so the demo uses `devFakeProof: true` in Debug, which sends a `fake:<requestHash>` proof that only a dev server in fake attestation mode accepts.

1. Start the dev server (fake attestation, port 8080): `cd server && npm run seed && npm run dev`
2. `open sdk/ios/Demo/Demo.xcodeproj`, pick any iPhone simulator, Run.
3. Keep or change the user id, tap **Play & Earn**.

The Debug build talks to `http://localhost:8080` (the Mac, from the simulator). The demo's `Info.plist` sets `NSAllowsLocalNetworking` so plain HTTP to localhost is allowed. Command line build: `xcodebuild -project sdk/ios/Demo/Demo.xcodeproj -scheme Demo -destination 'generic/platform=iOS Simulator' build`.

Never ship `devFakeProof: true`, and never run the server in fake mode in production (it refuses to start that way with `NODE_ENV=production`).

## Real App Attest needs a physical iPhone

App Attest only works on a real device. To try it end to end:

1. Set your team under **Signing & Capabilities** of the Demo target, and change its bundle id if `com.gamerewards.demo` is taken.
2. Run the server with `ATTEST_MODE=real`, reachable from the phone over HTTPS, and set the partner's iOS app in the admin (partner Settings) to that team id, bundle id and the Development environment.
3. Point the demo's `baseURL` at that server and run on the iPhone. With App Attest available the SDK uses it even when `devFakeProof` is on.

## Tests

```sh
cd sdk/ios && swift test
```

Checks the request hash against the SPEC test vector, the nonce format, the per-user key store name and the portal origin check. They run on the Mac, no simulator needed: `Package.swift` also lists macOS just for this, and the UI half compiles for iOS only.
