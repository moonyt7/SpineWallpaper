plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val natives by configurations.creating

android {
    namespace = "com.spine.wallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.spine.wallpaper"
        minSdk = 24 // Android 7.0
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    signingConfigs {
        // 将默认 debug 签名的密钥文件指向可写位置，绕开 ~/.android 被系统安全软件锁定的问题
        getByName("debug") {
            val customDebugKey = file("C:/Users/WWW/AppData/Local/Temp/debug.keystore")
            if (customDebugKey.exists()) {
                storeFile = customDebugKey
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // 16 KB Memory Page Size Support for Android 15+ (API Level 35)
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

// Automatically extract libGDX native .so binaries from platform jars into jniLibs
tasks.register("copyAndroidNatives") {
    doFirst {
        // 幂等：jniLibs 目录已有 .so 时跳过，避免 Windows 下覆盖已存在文件失败
        val abiDirs = linkedMapOf(
            "arm64-v8a" to file("src/main/jniLibs/arm64-v8a"),
            "armeabi-v7a" to file("src/main/jniLibs/armeabi-v7a"),
            "x86_64" to file("src/main/jniLibs/x86_64"),
            "x86" to file("src/main/jniLibs/x86")
        )
        abiDirs.values.forEach { it.mkdirs() }

        natives.files.forEach { jar ->
            val outputDir = abiDirs.entries.firstOrNull { jar.name.contains(it.key) }?.value ?: return@forEach
            val hasSo = outputDir.listFiles()?.any { it.name.endsWith(".so") } ?: false
            if (!hasSo) {
                copy {
                    from(zipTree(jar))
                    into(outputDir)
                    include("*.so")
                }
            }
        }
    }
}

tasks.configureEach {
    if (name.contains("merge") && name.contains("JniLibFolders")) {
        dependsOn("copyAndroidNatives")
    }
}

dependencies {
    // Multi-Runtime Isolated Modules (No package collisions!)
    // 各 runtime 模块以 shadowArtifact 形式提供重定位后的官方 spine-libgdx runtime
    implementation(project(":spine-core-bridge"))
    implementation(project(":spine-runtime-v36", configuration = "shadowArtifact"))
    implementation(project(":spine-runtime-v37", configuration = "shadowArtifact"))
    implementation(project(":spine-runtime-v38", configuration = "shadowArtifact"))
    implementation(project(":spine-runtime-v40", configuration = "shadowArtifact"))
    implementation(project(":spine-runtime-v41", configuration = "shadowArtifact"))
    implementation(project(":spine-runtime-v42", configuration = "shadowArtifact"))

    // libGDX 1.13.1+ for 16 KB page size alignment support on Android 15+
    implementation("com.badlogicgames.gdx:gdx:1.13.1")
    implementation("com.badlogicgames.gdx:gdx-backend-android:1.13.1")

    // libGDX native platform binaries with 16 KB LOAD segment alignment
    implementation("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-armeabi-v7a")
    implementation("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-arm64-v8a")
    implementation("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-x86")
    implementation("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-x86_64")
    natives("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-x86")
    natives("com.badlogicgames.gdx:gdx-platform:1.13.1:natives-x86_64")

    // Jetpack Compose & UI
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")

    // Coroutines & Document File / ZIP parsing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.json:json:20231013")

    // Icons
    implementation("androidx.compose.material:material-icons-extended:1.6.2")
}
