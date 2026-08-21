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

    buildTypes {
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
        file("src/main/jniLibs/armeabi-v7a").mkdirs()
        file("src/main/jniLibs/arm64-v8a").mkdirs()
        file("src/main/jniLibs/x86").mkdirs()
        file("src/main/jniLibs/x86_64").mkdirs()

        natives.files.forEach { jar ->
            val outputDir = when {
                jar.name.contains("arm64-v8a") -> file("src/main/jniLibs/arm64-v8a")
                jar.name.contains("armeabi-v7a") -> file("src/main/jniLibs/armeabi-v7a")
                jar.name.contains("x86_64") -> file("src/main/jniLibs/x86_64")
                jar.name.contains("x86") -> file("src/main/jniLibs/x86")
                else -> null
            }
            if (outputDir != null) {
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
    // Official Spine 4.1.0 Runtime (spine-libgdx on Maven Central)
    implementation("com.esotericsoftware.spine:spine-libgdx:4.1.0")

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

    // Coroutines & Document File / ZIP parsing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.json:json:20231013")

    // Icons
    implementation("androidx.compose.material:material-icons-extended:1.6.2")
}
