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
    implementation("com.esotericsoftware.spine:spine-libgdx:3.7.83.1")
    compileOnly("com.badlogicgames.gdx:gdx:1.13.1")
}

tasks.shadowJar {
    // 将官方 Spine 3.7 runtime 重定位到独立包名，避免与其他版本运行时冲突
    // 注意：3.7 导出的 JSON 仍是老 skins 字典格式，3.8 runtime 无法读取，必须由本模块处理
    relocate("com.esotericsoftware.spine", "com.spine.wallpaper.spine37")
    // libGDX、bridge 接口、Kotlin 等由 app 模块统一提供，不打入 shadow JAR
    exclude("com/badlogic/**")
    exclude("com/spine/wallpaper/bridge/**")
    exclude("kotlin/**")
    exclude("kotlinx/**")
    exclude("org/jetbrains/**")
    exclude("org/intellij/**")
    exclude("META-INF/**")
}

// 自定义配置：让 app 模块消费 shadow JAR（而非普通 JAR）
configurations {
    create("shadowArtifact") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }
}

artifacts {
    add("shadowArtifact", tasks.shadowJar)
}
