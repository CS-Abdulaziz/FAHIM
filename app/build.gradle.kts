plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.testandroidenv"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.testandroidenv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // Override with -PwhisperSourceDir=/path/to/whisper.cpp if needed.
                val whisperSourceDir = providers.gradleProperty("whisperSourceDir")
                    .orElse("C:/src/whisper.cpp")
                    .get()
                    .replace('\\', '/')
                arguments += listOf(
                    "-DWHISPER_SOURCE_DIR=$whisperSourceDir",
                    // Native Debug builds are too slow for practical transcription.
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    buildTypes {
        release {
            // Temporary local-device testing only. Replace for production distribution.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // The native asset loader streams the model directly from the APK.
        noCompress += "bin"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.json)
}
