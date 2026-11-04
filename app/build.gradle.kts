import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// Signing credentials live in keystore/KEYSTORE-INFO.txt (generated once;
// the file and the .jks stay out of git), or in the MITV_STORE_PASSWORD
// / MITV_KEY_PASSWORD environment variables (which win over the file).
// Parse them so the build is reproducible without committing gradle props.
fun keystoreInfo(): Properties {
    val p = Properties()
    val f = rootProject.file("keystore/KEYSTORE-INFO.txt")
    if (f.exists()) {
        f.forEachLine { line ->
            val t = line.trim()
            when {
                t.startsWith("Store password:") -> p.setProperty("store", t.substringAfter(":").trim())
                t.startsWith("Key password:") -> p.setProperty("key", t.substringAfter(":").trim())
            }
        }
    }
    System.getenv("MITV_STORE_PASSWORD")?.takeIf { it.isNotBlank() }?.let { p.setProperty("store", it) }
    System.getenv("MITV_KEY_PASSWORD")?.takeIf { it.isNotBlank() }?.let { p.setProperty("key", it) }
    return p
}
val ks = keystoreInfo()
fun hasText(v: String?) = !v.isNullOrBlank()
// The release key is present only when the keystore file AND both
// passwords exist. Without it the release build is produced UNSIGNED
// (with a warning) instead of failing cryptically; an unsigned APK
// cannot update the Stick install, which needs the same signing key.
val hasReleaseKey = rootProject.file("keystore/mitv-release.jks").exists() &&
    hasText(ks.getProperty("store")) && hasText(ks.getProperty("key"))
if (!hasReleaseKey) {
    logger.warn("MiTV: release keystore not found, building UNSIGNED release APK. " +
        "It cannot update the installed app on the Stick without the release key.")
}

android {
    namespace = "com.mitv.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mitv.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 19
        versionName = "1.0.18"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file("keystore/mitv-release.jks")
                storePassword = ks.getProperty("store")
                keyAlias = "mitv"
                keyPassword = ks.getProperty("key")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Default debug keystore (auto-generated): debug builds work
            // on a clean clone without the release key. Never the
            // release key — debug APKs must not carry the release identity.
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
