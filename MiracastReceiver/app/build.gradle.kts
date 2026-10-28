plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 要打包的 ABI：-PbuildArm64 / -PbuildArm32 只出单一架构，默认两种都出（含 universal）
val targetAbis = when {
    project.hasProperty("buildArm64") -> listOf("arm64-v8a")
    project.hasProperty("buildArm32") -> listOf("armeabi-v7a")
    else -> listOf("arm64-v8a", "armeabi-v7a")
}
val buildUniversalApk = targetAbis.size > 1

android {
    namespace = "com.weekd.miracastreceiver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.weekd.miracastreceiver"
        minSdk = 21
        targetSdk = 36
        versionCode = 14
        versionName = "1.2.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // universal 包需要 ndk.abiFilters 排除 x86/x86_64；
        // 单 ABI 构建时 AGP 不允许它与 splits.abi 同时存在，所以只在出 universal 包时设置
        if (buildUniversalApk) {
            ndk {
                abiFilters += targetAbis
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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*targetAbis.toTypedArray())
            isUniversalApk = buildUniversalApk
        }
    }

    // 应用内可切换界面语言，AAB 不能按语言拆包，否则切到设备没下载的语言会缺资源
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Android TV Leanback
    implementation("androidx.leanback:leanback:1.2.0-alpha04")
    implementation("androidx.tvprovider:tvprovider:1.1.0-alpha01")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Video Player - ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    implementation("androidx.media3:media3-common:1.5.0")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // AirPlay 2 handshake dependencies
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.googlecode.plist:dd-plist:1.28")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
