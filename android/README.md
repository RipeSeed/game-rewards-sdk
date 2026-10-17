# Game Rewards Android SDK

One call opens the Game Rewards portal for your signed-in user. Each launch is attested with Google Play Integrity,
so points can only be earned from your genuine app installed from Google Play. No partner server is needed.

- Module `:gamerewards`: Kotlin library, minSdk 23. Its only dependency is `com.google.android.play:integrity:1.6.0`.
- Module `:demo`: one-screen sample app (user id, **Play & Earn**, status line).

## Add the dependency

There is no Maven artifact yet. Use either option:

**Source module** (keeps the Play Integrity dependency transitive). In your `settings.gradle.kts`:

```kotlin
include(":gamerewards")
project(":gamerewards").projectDir = file("../game-rewards/sdk/android/gamerewards")
```

Then in your app module: `implementation(project(":gamerewards"))`. Your project must already apply the Android
and Kotlin Gradle plugins. On AGP 9 or newer, which compiles Kotlin itself, delete the
`id("org.jetbrains.kotlin.android")` line from `gamerewards/build.gradle.kts`.

**AAR.** Run `./gradlew :gamerewards:assembleRelease`, copy `gamerewards/build/outputs/aar/gamerewards-release.aar`
to `app/libs/`, then:

```kotlin
dependencies {
    implementation(files("libs/gamerewards-release.aar"))
    implementation("com.google.android.play:integrity:1.6.0") // a file AAR doesn't bring its dependencies
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21") // only if your app has no Kotlin of its own
}
```

The library's manifest adds the `INTERNET` and `ACCESS_NETWORK_STATE` permissions (the second lets the portal see
when the phone is offline) and `GameRewardsActivity`. You don't need to declare any of them.

## Use it

```kotlin
// Once, e.g. in Application.onCreate(). Main thread.
GameRewards.init(
    this,
    GameRewardsConfig(
        clientId = "your-partner-id",           // given to you by the Game Rewards operator
        baseUrl = "https://rewards.example.com",
        cloudProjectNumber = 123456789012L,     // the Cloud project linked in Play Console (below)
    ),
)

// Your "Play & Earn" button.
playButton.setOnClickListener {
    GameRewards.open(this, userId = currentUser.id) { message -> showError(message) }
}
```

- `userId` is your own stable id for the user: 1 to 128 characters of `A-Z a-z 0-9 . _ : @ -`. Prefer an opaque id
  over an email address.
- `onError` runs on the main thread. If the call succeeds, the portal opens.
- `devFakeProof = true` is for local development only. A server in real attestation mode never accepts it, so leaving
  it on in a release build turns every launch into a failed attestation. It never grants anything.

What `open` does: once the integrity token provider is ready, it makes a random nonce and the current time, computes
`requestHash = sha256_hex("clientId|userId|nonce|ts")`, asks Play Integrity for a token bound to that hash, and posts
everything to `POST /v1/launch`. It then opens `GameRewardsActivity` on the returned `portalUrl`. The provider is
prepared once in `init` and reused. If Google reports it expired, `open` prepares a new one and retries once. If the
device can't produce a token at all (no Google Play, or Play is too old or unreachable), `open` launches with an empty
proof and your `onAttestFail` setting decides: play-only mode, or `attestation_failed (bad_proof)` in `onError`.

The portal runs in a plain WebView with these settings:
- JavaScript and DOM storage on; file and content access off.
- No JavaScript bridge.
- Only the portal's own origin loads inside. Other `http(s)` links open in the browser and every other scheme is
  blocked.
- Back goes back through the portal's pages, then closes it. A close control leaves in one tap: an up arrow if your
  theme has a platform action bar, otherwise a **Close** row above the portal.

## Play Console setup (once per app)

1. **Turn on Play Integrity.** In Play Console, open your app and go to **Test and release > App integrity**. Under
   **Play Integrity API**, choose **Link Cloud project**, then pick or create a Google Cloud project. In that
   project's Cloud Console, check that **Google Play Integrity API** shows as enabled under **APIs & Services**.
2. **Note the project number.** In Cloud Console, open **Dashboard > Project info** and copy the *project number*
   (digits only, not the project ID). It goes in `cloudProjectNumber` in your app and in the server config.
3. **Create the service account the server uses to decode tokens.** In the same Cloud project, go to
   **IAM & Admin > Service accounts > Create service account**. Grant it **Service Account User** and **Service
   Usage Consumer**. Then go to **Keys > Add key > Create new key > JSON**. Send the JSON file to the Game Rewards
   operator over a secure channel. The operator uploads it in the admin (partner Overview, Android key) and runs
   **Check with Google**, which shows Google's own error if the key or the API isn't right.
4. **Copy the app signing certificate.** Go to **Test and release > App integrity > App signing** and copy the
   **App signing key certificate** SHA-256. Use the app signing key, not the upload key: Play re-signs your app, and
   Play Integrity reports the certificate Play signed with.
5. The operator enters your app in the partner's settings in the admin (`/admin/`, partner Settings, Android app):
   the package name, the App signing certificate SHA-256 and the Cloud project number. It is live at once.

6. **Check the quota before launch.** Every launch uses one of the Cloud project's 10,000 default daily Play Integrity
   decodes. Past that, launches fail with `google_error` until the next day. Request an increase in Cloud Console
   early (it can take a week) and set a quota alert.

## Try it on an emulator against the dev server

The debug demo calls `http://localhost:8080` and sends `fake:` proofs, so it only works against a server started with
`ATTEST_MODE=fake`. `adb reverse` forwards the device's `localhost:8080` to the host. That is also the server's
default `PUBLIC_URL`, so the API calls and the returned `portalUrl` share one origin, which the portal WebView
requires. It works the same on an emulator and on a phone plugged in over USB. Cleartext HTTP is allowed only in debug
and only to `localhost` (`demo/src/debug/res/xml/network_security_config.xml`). The release build allows no cleartext.

```sh
# 1. Dev server on :8080 with fake attestation (repo root)
cd server && npm run seed && npm run dev

# 2. Forward the device's localhost:8080 to the host. Repeat after every emulator boot or USB reconnect.
adb reverse tcp:8080 tcp:8080

# 3. Build and install the demo (needs JDK 17; see "Building" below)
cd sdk/android && ./gradlew :demo:installDebug
```

Open **Game Rewards Demo**, keep `u-123` or type another id, and tap **Play & Earn**. The status line shows any
error:

| Status | Fix |
|---|---|
| `Failed to connect to localhost/127.0.0.1:8080` | Start the dev server, then run `adb reverse tcp:8080 tcp:8080` (step 2). |
| `Cleartext HTTP traffic … not permitted` | Use the debug build, or a host from the network security config. |
| `unknown_client: …` | The server's config has no partner `demo`. A new database starts from `config/partners.example.json` (or `CONFIG_PATH`), which has it; after that the database keeps its own config, which the startup line reports. Add the partner in the admin, or start with a new `DB_PATH`. |

## Test real Play Integrity on an internal testing track

Play Integrity only reports `PLAY_RECOGNIZED` for an app installed from Google Play, so use an internal testing
track and a real phone. Emulators and rooted phones fail `MEETS_DEVICE_INTEGRITY`.

1. Use a package name you own in Play Console. Set `applicationId` in `demo/build.gradle.kts` and the partner's
   package name in the admin to it.
2. In `demo/build.gradle.kts` `defaultConfig`, set `BASE_URL` to an HTTPS server running with `ATTEST_MODE=real` and
   `CLOUD_PROJECT_NUMBER` to your project number (for example `123456789012L`).
3. Build and sign the bundle with an upload key. Keep the keystore out of the repo.

   ```sh
   ./gradlew :demo:bundleRelease
   keytool -genkeypair -v -keystore ~/upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000   # once
   jarsigner -keystore ~/upload.jks demo/build/outputs/bundle/release/demo-release.aab upload
   ```

4. In Play Console, go to **Test and release > Testing > Internal testing > Create new release**. Accept Play App
   Signing, upload `demo-release.aab`, then save and roll out. Play may ask you to complete a few App content forms
   first. On the **Testers** tab, add an email list that includes your Google account, and copy the opt-in link.
   Bump `versionCode` for every new upload.
5. Finish the Play Console setup above: link the Cloud project, add the service account, and copy the App signing
   SHA-256 into the partner's settings in the admin. Upload the key there and run **Check with Google**. No restart.
6. On the phone, signed in as a tester, open the opt-in link, accept, and install the app from the Play Store. A
   build installed with `adb` or Android Studio is not Play-recognized. Tap **Play & Earn**. The session should be
   verified, with balance, wallet and rewards visible.

If the launch isn't verified, the server's `reason` tells you why. The portal shows play-only mode, or the status
line shows `attestation_failed (<reason>)` when the partner uses `onAttestFail: "reject"`.

| Reason | Usual cause |
|---|---|
| `not_play_recognized` | Installed outside Play (adb or Android Studio). Uninstall, then reinstall from the opt-in link. |
| `bad_cert` | `certSha256` holds the upload key; use the **App signing** key. |
| `wrong_package` | The partner's package name in the admin doesn't match `applicationId`. |
| `device_integrity` | Emulator, rooted phone or unlocked bootloader. |
| `google_error` | Service account isn't in the linked project, lacks the two roles, the API is off, the JSON is wrong, or the daily quota is used up. |
| `stale` | The phone's clock is more than 2 minutes off. |
| `bad_proof` | The SDK got no token: the device has no usable Google Play, or the Integrity API isn't enabled for the linked Cloud project (`-1`). |
| `attest_limited` | Too many failed Android checks for this app from this IP within 24 h: 20 junk or replayed tokens (`bad_proof`, `hash_mismatch`, `wrong_package`, `stale`) or 200 other failures such as emulators on shared office Wi-Fi, so the server stopped asking Google. `google_error` doesn't count. It clears 24 h after the first failure, or on a server restart. |
| `testing_response` | Play Console test responses are on for this tester (Play Integrity API settings under **App integrity**). Turn them off to get real verdicts. |

The client can also fail before reaching the server. For example, `Play Integrity: -16 … cloud project number is
invalid` means `CLOUD_PROJECT_NUMBER` is wrong or still `0`.

## Building

```sh
cd sdk/android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # gitignored
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # or /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :gamerewards:testDebugUnitTest :gamerewards:assembleRelease :demo:assembleDebug
```

Toolchain: Gradle 8.14.3, AGP 8.7.3, Kotlin 2.1.21, compileSdk and targetSdk 36. The unit test checks `requestHash`
against the SPEC test vector.
