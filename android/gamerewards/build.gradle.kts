plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.gamerewards"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

// Pin Kotlin to the same target as Java; otherwise it follows the Gradle JDK (Android Studio's is 21) and the build fails.
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.google.android.play:integrity:1.6.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

// com.gamerewards:gamerewards on GitHub Packages. The public SDK repo's release workflow publishes it on a v* tag,
// passing -PsdkVersion and the GitHubPackagesUsername/Password properties. The POM brings Play Integrity along.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.gamerewards"
            artifactId = "gamerewards"
            version = providers.gradleProperty("sdkVersion").getOrElse("0.0.0-dev")
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven("https://maven.pkg.github.com/RipeSeed/game-rewards-sdk") {
            name = "GitHubPackages"
            credentials(PasswordCredentials::class)
        }
    }
}
