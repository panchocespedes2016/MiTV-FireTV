import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// Signing credentials live in keystore/KEYSTORE-INFO.txt (generated once).
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
    return p
}
val ks = keystoreInfo()

android {
    namespace = "com.mitv.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mitv.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "1.0.8"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/mitv-release.jks")
            storePassword = ks.getProperty("store")
            keyAlias = "mitv"
            keyPassword = ks.getProperty("key")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
