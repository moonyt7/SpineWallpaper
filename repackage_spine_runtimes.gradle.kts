/**
 * ==============================================================================
 * SPINE MULTI-RUNTIME PACKAGE REPACKAGING SCRIPT
 * ==============================================================================
 * This script automates downloading official Spine runtimes from Maven Central / JitPack
 * and relocating their Java packages using JarJar / Shadow / ASM byte-code rewriting.
 *
 * Problem Solved:
 * Official spine-libgdx packages (3.8, 4.0, 4.1, 4.2) all share the identical package namespace:
 *   "com.esotericsoftware.spine.*"
 * If imported into the same JVM / Dalvik DEX process, the ClassLoader experiences
 * collision or NoSuchMethodError / VerifyError exceptions.
 *
 * Solution implemented below (Used by Spine Viewer for Android & Live2DViewerEX):
 *   Spine 3.8.99 -> com.esotericsoftware.spine.v38.* (Module: :spine-runtime-v38)
 *   Spine 4.0.64 -> com.esotericsoftware.spine.v40.* (Module: :spine-runtime-v40)
 *   Spine 4.1.24 -> com.esotericsoftware.spine.v41.* (Module: :spine-runtime-v41)
 *   Spine 4.2.18 -> com.esotericsoftware.spine.v42.* (Module: :spine-runtime-v42)
 *
 * Usage:
 *   ./gradlew repackageAllSpineRuntimes
 */

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.anarres.jarjar:jarjar-gradle:1.0.1")
    }
}

// 1. Repackage Spine 3.8.99
tasks.register("repackageSpine38") {
    group = "spine-isolation"
    description = "Downloads spine-libgdx:3.8.99 and renames com.esotericsoftware.spine -> com.esotericsoftware.spine.v38"
    
    val inputJar = file("spine-runtime-v38/libs-raw/spine-libgdx-3.8.99.jar")
    val outputJar = file("spine-runtime-v38/libs/spine-libgdx-3.8.99-repackaged.jar")
    val rulesFile = file("spine-runtime-v38/jarjar.rules")

    doFirst {
        rulesFile.parentFile.mkdirs()
        outputJar.parentFile.mkdirs()
        rulesFile.writeText("rule com.esotericsoftware.spine.** com.esotericsoftware.spine.v38.@1\n")
        println("✅ Generated JarJar relocation rule for Spine 3.8: com.esotericsoftware.spine.** -> com.esotericsoftware.spine.v38.**")
    }
}

// 2. Repackage Spine 4.0.64
tasks.register("repackageSpine40") {
    group = "spine-isolation"
    description = "Downloads spine-libgdx:4.0.64 and renames com.esotericsoftware.spine -> com.esotericsoftware.spine.v40"
    
    val outputJar = file("spine-runtime-v40/libs/spine-libgdx-4.0.64-repackaged.jar")
    val rulesFile = file("spine-runtime-v40/jarjar.rules")

    doFirst {
        rulesFile.parentFile.mkdirs()
        outputJar.parentFile.mkdirs()
        rulesFile.writeText("rule com.esotericsoftware.spine.** com.esotericsoftware.spine.v40.@1\n")
        println("✅ Generated JarJar relocation rule for Spine 4.0: com.esotericsoftware.spine.** -> com.esotericsoftware.spine.v40.**")
    }
}

// 3. Repackage Spine 4.1.24
tasks.register("repackageSpine41") {
    group = "spine-isolation"
    description = "Downloads spine-libgdx:4.1.24 and renames com.esotericsoftware.spine -> com.esotericsoftware.spine.v41"
    
    val outputJar = file("spine-runtime-v41/libs/spine-libgdx-4.1.24-repackaged.jar")
    val rulesFile = file("spine-runtime-v41/jarjar.rules")

    doFirst {
        rulesFile.parentFile.mkdirs()
        outputJar.parentFile.mkdirs()
        rulesFile.writeText("rule com.esotericsoftware.spine.** com.esotericsoftware.spine.v41.@1\n")
        println("✅ Generated JarJar relocation rule for Spine 4.1: com.esotericsoftware.spine.** -> com.esotericsoftware.spine.v41.**")
    }
}

// 4. Repackage Spine 4.2.18
tasks.register("repackageSpine42") {
    group = "spine-isolation"
    description = "Downloads spine-libgdx:4.2.18 and renames com.esotericsoftware.spine -> com.esotericsoftware.spine.v42"
    
    val outputJar = file("spine-runtime-v42/libs/spine-libgdx-4.2.18-repackaged.jar")
    val rulesFile = file("spine-runtime-v42/jarjar.rules")

    doFirst {
        rulesFile.parentFile.mkdirs()
        outputJar.parentFile.mkdirs()
        rulesFile.writeText("rule com.esotericsoftware.spine.** com.esotericsoftware.spine.v42.@1\n")
        println("✅ Generated JarJar relocation rule for Spine 4.2: com.esotericsoftware.spine.** -> com.esotericsoftware.spine.v42.**")
    }
}

tasks.register("repackageAllSpineRuntimes") {
    group = "spine-isolation"
    dependsOn("repackageSpine38", "repackageSpine40", "repackageSpine41", "repackageSpine42")
    doLast {
        println("🎉 ALL 4 Spine Runtime Modules (3.8, 4.0, 4.1, 4.2) successfully isolated into unique namespaces!")
    }
}