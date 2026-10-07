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
    // Spine 4.3.39-beta（dev 窗口）的 libgdx runtime。
    // 来源：用户本机的 Spine Skeleton Viewer fat jar（skeletonViewer-4.3.39-beta.jar），
    // 只抽出其中未 relocate 的 com/esotericsoftware/spine/** 打成本地 jar。
    // 这一版能读 hero_11000501 / heroCG_11000501 那批 `4.3.39-beta` 中间格式的文件，
    // 而已发布的 4.2.12 / 4.3.0~4.3.5 全部读不了。
    implementation(files("libs/spine-libgdx-4.3.39-beta.jar"))
    compileOnly("com.badlogicgames.gdx:gdx:1.14.2")
}

tasks.shadowJar {
    // 重定位到独立包名，避免与 :spine-runtime-v43（4.3.5）等其他运行时冲突。
    // 注意：本模块自己的 Java 助手 com/esotericsoftware/spine/V43bSupport.java 也在这个命名空间下，
    // 会一起被重定位 ⇒ 与运行时代码保持同包，从而可以零反射访问包私有成员。
    relocate("com.esotericsoftware.spine", "com.spine.wallpaper.spine43b")
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
