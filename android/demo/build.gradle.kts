plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamerewards.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.gamerewards.demo" // must match android.packageName in the server's partners.json
        minSdk = 29 // for Theme.DeviceDefault.DayNight, so the portal follows dark mode
        targetSdk = 36
        versionCode = 1 // bump for every Play upload
        versionName = "1.0"
        // Release: your HTTPS server and the Cloud project number linked in Play Console (see README).
        buildConfigField("String", "BASE_URL", "\"https://rewards.example.com\"")
        buildConfigField("long", "CLOUD_PROJECT_NUMBER", "0L")
        buildConfigField("boolean", "DEV_FAKE_PROOF", "false")
    }
    buildTypes {
        debug {
            // The host's dev server (ATTEST_MODE=fake) via `adb reverse tcp:8080 tcp:8080`. localhost matches the
            // server's default PUBLIC_URL, so API calls and portalUrl share one origin. src/debug allows cleartext to it.
            buildConfigField("String", "BASE_URL", "\"http://localhost:8080\"")
            buildConfigField("boolean", "DEV_FAKE_PROOF", "true")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":gamerewards"))
}
