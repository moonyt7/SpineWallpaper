// Top-level build file configuring multi-runtime isolated Spine modules
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

// 统一所有模块的 Kotlin/JVM 编译目标为 17（与各模块 Java 编译目标一致）。
// 修复高版本 JDK（24/25）下 "Inconsistent JVM Target Compatibility" 构建失败。
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "17"
    }

    // 可选：将 build 目录重定向到临时目录（-Pspine1.altBuildDir=...），
    // 用于绕过系统安全软件对桌面/用户目录文件删除的拦截。
    val altBuildDir = providers.gradleProperty("spine1.altBuildDir").orNull
    if (altBuildDir != null) {
        layout.buildDirectory.set(file("$altBuildDir/${project.name}"))
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}