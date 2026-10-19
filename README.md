# Game Rewards SDK

One **Play & Earn** button in your Android or iOS app opens a portal where your users play short quizzes, earn points
and redeem your promo codes. Google Play Integrity or Apple App Attest proves each launch comes from your genuine app.

**Guide:** https://game-rewards-sandbox.internal.ripeseed.io/docs/

## Install

**Android** (API 23+). GitHub Packages needs a [classic token](https://github.com/settings/tokens/new?scopes=read:packages&description=Game%20Rewards%20SDK)
with `read:packages`, even for public packages. Put it in `~/.gradle/gradle.properties`:

```properties
gameRewardsUsername=your-github-username
gameRewardsPassword=ghp_your_token
```

```kotlin
// settings.gradle.kts, in dependencyResolutionManagement { repositories { … } }
maven("https://maven.pkg.github.com/RipeSeed/game-rewards-sdk") {
    name = "gameRewards"
    credentials(PasswordCredentials::class)
}

// app/build.gradle.kts
dependencies { implementation("com.gamerewards:gamerewards:0.1.1") }
```

Or download `gamerewards-release.aar` from [Releases](https://github.com/RipeSeed/game-rewards-sdk/releases) and add
`com.google.android.play:integrity:1.6.0` (and `kotlin-stdlib` in a Java-only app) yourself.

**iOS** (15+). In Xcode, **File > Add Package Dependencies…**, enter `https://github.com/RipeSeed/game-rewards-sdk`
and add **GameRewards** to your target.

## Use

These values point at the shared sandbox, which accepts test proofs: no setup on our side.

```kotlin
// Android: Application.onCreate(), then your button
GameRewards.init(this, GameRewardsConfig(clientId = "demo", baseUrl = "https://game-rewards-sandbox.internal.ripeseed.io",
    cloudProjectNumber = 0L, devFakeProof = true))
GameRewards.open(activity, userId = currentUser.id) { error -> showError(error) }
```

```swift
// iOS: at app start, then your button (in the simulator)
GameRewards.configure(.init(clientId: "demo", baseURL: URL(string: "https://game-rewards-sandbox.internal.ripeseed.io")!,
                            devFakeProof: true))
GameRewards.open(from: viewController, userId: currentUser.id) { error in if let error { print(error) } }
```

Keep `devFakeProof` in debug builds only. The guide has release-safe versions of both, and the go-live steps.

## Demos

- Android: `cd android && ./gradlew :demo:installDebug` (emulator or phone, debug build uses the sandbox).
- iOS: open `ios/Demo/Demo.xcodeproj` and run **Demo** on a simulator.

## Develop

```sh
# Android: library tests, release AAR, demo (JDK 17+; Android Studio's bundled JDK works)
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :gamerewards:testDebugUnitTest :gamerewards:assembleRelease :demo:assembleDebug

# iOS: hashing, nonce, key-store and origin tests, on the Mac without a simulator
swift test
```

Both test suites check the request hash against the same test vector as the server. To run the demos against a
Game Rewards server on your machine instead of the sandbox, set the demo's debug base URL to `http://localhost:8080`
(the debug builds already allow cleartext to `localhost`), and on Android run `adb reverse tcp:8080 tcp:8080`.

Real attestation needs the demo's `applicationId` or bundle ID changed to one you own, registered with a live server,
and, on Android, a release signed with your upload key and installed from a Play internal testing track:

```sh
keytool -genkeypair -keystore ~/upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000   # once
cd android && ./gradlew :demo:bundleRelease
jarsigner -keystore ~/upload.jks demo/build/outputs/bundle/release/demo-release.aab upload
```

A `v*` tag publishes `com.gamerewards:gamerewards` to GitHub Packages and attaches the AAR to a GitHub release
(`.github/workflows/release.yml`). Swift Package Manager reads the same tag.
