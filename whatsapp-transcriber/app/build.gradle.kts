plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vntranscriber"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.vntranscriber"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Every Galaxy phone from the last ~7 years is arm64. Dropping other ABIs
        // cuts native build time and APK size.
        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                // whisper.cpp built without optimizations is ~10x slower.
                // Force Release flags even for the debug APK.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideload-only app: sign release with the debug key unless you set up your own.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Model files are already compressed weights. Storing them uncompressed
    // lets us copy them out of the APK quickly if you bundle one in assets/.
    androidResources { noCompress += "bin" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
