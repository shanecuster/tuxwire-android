// Build file for the `app` module: what the app is called, which Android
// versions it supports, and which libraries it uses.

plugins {
    alias(libs.plugins.android.application)
    // Turns on the Compose compiler (Kotlin support is built into the Android
    // Gradle Plugin itself, so there is no separate Kotlin plugin line).
    alias(libs.plugins.kotlin.compose)
}

// The exact Android NDK (the C/Rust cross-compiler kit) the Rust library is
// built with. Pinning it, instead of "whatever is newest", is what makes two
// builds of the same source come out the same. It is used twice below: AGP's
// `ndkVersion`, and an environment variable that tells scripts/build-rust.sh
// which NDK folder to use. To change it, install that NDK version in Android
// Studio (SDK Manager > SDK Tools > NDK (Side by side)) and edit this line.
val pinnedNdkVersion = "30.0.16248370"

// Which CPU types the Rust library is built for. Release builds (any Gradle
// task with "release" in its name, e.g. assembleRelease) get all three: 64-bit
// phones, old 32-bit phones, and the x86_64 emulator. Everyday debug builds
// leave this unset, so the script's quicker default (arm64-v8a and x86_64)
// applies. `-PtuxwireAbis="..."` on the Gradle command line overrides both.
val requestedAbis: String? = providers.gradleProperty("tuxwireAbis").orNull
    ?: if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
        "arm64-v8a armeabi-v7a x86_64"
    } else {
        null
    }

android {
    // Where the code lives and the prefix of the generated `R` class (R.font...).
    namespace = "com.shanetalkstech.tuxwire"
    // The NDK version AGP expects (see `pinnedNdkVersion` above).
    ndkVersion = pinnedNdkVersion
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // The app's permanent identity on the device and in app stores.
        applicationId = "com.shanetalkstech.tuxwire"
        // Oldest Android supported: 8.0. Keep in step with TUXWIRE_MIN_API in
        // scripts/build-rust.sh.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Debug builds install as a *separate app* (com.shanetalkstech.tuxwire.debug)
            // so they never overwrite or share data with a real release install.
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose: the BOM first (it pins the versions), then the pieces we use.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)

    // Needed by the Kotlin code that UniFFI generates from the Rust library.
    implementation(libs.kotlinx.coroutines.android)
    // JNA must be the Android flavour (`@aar`), which bundles the native pieces
    // for each CPU type. A plain jar would not work on a phone.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}

// Runs scripts/build-rust.sh: compiles the Rust library for Android and writes
// the Kotlin bridge file. `workingDir = rootDir` starts it from the project root.
val buildRust = tasks.register<Exec>("buildRust") {
    group = "build"
    description = "Compiles tuxwire-ffi with cargo-ndk and generates the UniFFI Kotlin bindings."
    workingDir = rootDir
    commandLine("bash", "scripts/build-rust.sh")
    // Tell the script which NDK to use (it refuses to fall back to another).
    environment("TUXWIRE_NDK_VERSION", pinnedNdkVersion)
    // Only set when a release build, or -PtuxwireAbis, asked for specific CPU
    // types; otherwise the script's own default applies.
    requestedAbis?.let { environment("TUXWIRE_ABIS", it) }
}

// Make every Android build run the Rust build first, so the .so files and the
// generated Kotlin always exist before Kotlin is compiled.
tasks.named("preBuild") {
    dependsOn(buildRust)
}
