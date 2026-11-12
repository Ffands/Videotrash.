plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun envOrEmpty(key: String): String =
    System.getenv(key)?.replace("\"", "\\\"") ?: ""

android {
    namespace = "forge.media.pocket"
    compileSdk = 34

    defaultConfig {
        applicationId = "forge.media.pocket"
        minSdk = 29
        targetSdk = 34

        versionCode = 1
        versionName = "1.2.0-snapdragon"

        // Strictly ARM64-v8a for Qualcomm Snapdragon
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Option 6C Hybrid: Pre-fills from GitHub Actions Secrets if set, or editable on phone screen
        buildConfigField("String", "DEFAULT_TELEGRAM_BOT_TOKEN", "\"${envOrEmpty("TELEGRAM_BOT_TOKEN")}\"")
        buildConfigField("String", "DEFAULT_GEMINI_API_KEY", "\"${envOrEmpty("GEMINI_API_KEY")}\"")
        buildConfigField("String", "DEFAULT_GROQ_API_KEY", "\"${envOrEmpty("GROQ_API_KEY")}\"")
        buildConfigField("String", "DEFAULT_ALLOWED_CHAT_ID", "\"${envOrEmpty("ALLOWED_CHAT_ID")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        // Exact official match for Kotlin 1.9.24
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            // Option 4A: Required so SELinux allows executing bundled python/yt-dlp binaries from /lib/arm64
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val ktorVersion = "2.3.12"
    val coroutinesVersion = "1.8.1"
    val workVersion = "2.9.0"
    val media3Version = "1.3.1"

    // Core AndroidX & Lifecycle Service (Pinned to API 34 compatible releases)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:$workVersion")

    // Jetpack Compose (BOM 2024.06.00 — strictly targets compileSdk 34)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // AndroidX Media3 ExoPlayer (Hardware 60fps frame-exact NLE video preview & multi-segment clipping)
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Async Thumbnail Image Loading for Link Cards
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")

    // Option 1B: Self-contained FFmpegKit Full-GPL AAR downloaded into app/libs by CI
    implementation(files("libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar"))
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Option 4A: Native yt-dlp + Aria2c wrapper for Android (0.18.1 with updated Python & extractors)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")

    // Google ML Kit Face Detection (Bundled model)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Ktor Client
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
