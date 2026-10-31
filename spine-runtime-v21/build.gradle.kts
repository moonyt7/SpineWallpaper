plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(project(":spine-core-bridge"))
    compileOnly("com.badlogicgames.gdx:gdx:1.14.2")
}

// ⚠️ 2.1 的 runtime **没有 maven 产物**（Maven Central 上 spine-libgdx 最老只到 3.5.51.1），
// 所以源码直接内嵌在 src/main/java/com/esotericsoftware/spine（30 个 .java，取自
// spine-runtimes 仓库 tag 2.1.25 的 spine-libgdx/spine-libgdx/src）。
// 源码在 gdx 1.14.2 下可直接编译通过（仅 deprecation / unchecked 警告）。

tasks.shadowJar {
    // 将内置的 Spine 2.1 runtime 重定位到独立包名，避免与其他版本运行时冲突
    relocate("com.esotericsoftware.spine", "com.spine.wallpaper.spine21")
    exclude("com/badlogic/**")
    exclude("com/spine/wallpaper/bridge/**")
    exclude("kotlin/**")
    exclude("kotlinx/**")
    exclude("org/jetbrains/**")
    exclude("org/intellij/**")
    exclude("META-INF/**")
}

configurations {
    create("shadowArtifact") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }
}

artifacts {
    add("shadowArtifact", tasks.shadowJar)
}
