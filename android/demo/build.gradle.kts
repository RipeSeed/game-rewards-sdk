plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamerewards.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.gamerewards.demo" // must match the partner's Android package name in the server's admin
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
            // The shared sandbox (fake attestation, client "demo"). For a dev server on this machine instead, use
            // "http://localhost:8080" and `adb reverse tcp:8080 tcp:8080`: localhost matches the server's default
            // PUBLIC_URL, so API calls and portalUrl share one origin, and src/debug allows cleartext to it.
            buildConfigField("String", "BASE_URL", "\"https://game-rewards-sandbox.internal.ripeseed.io\"")
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
