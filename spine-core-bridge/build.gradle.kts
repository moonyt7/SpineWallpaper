plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // libGDX Graphics abstractions (PolygonSpriteBatch, TextureAtlas, OrthographicCamera) — provided by :app at runtime
    compileOnly("com.badlogicgames.gdx:gdx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.json:json:20231013")
}
