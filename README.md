# SpineWallpaper - Android Spine 2D Live Wallpaper Application

SpineWallpaper is a full-featured Android Live Wallpaper application built with **Kotlin**, **Jetpack Compose**, and the official **spine-android** runtime library.

---

## 🌟 Key Features

1. **Spine 2D Animation Playback**:
   - Parses `.skel` binary skeleton data via `SkeletonBinary`
   - Loads `.atlas` texture atlas via `TextureAtlas`
   - Manages playback & track switching via `AnimationState`
   - Supports Live2DViewerEX compatible `.config.json` files

2. **File & ZIP Parsing**:
   - Accepts compressed ZIP archives containing `.skel`, `.atlas`, `.png`, and `.config.json`
   - Matches files by filename prefixes (e.g. `character.skel` -> `character.atlas` -> `character.png`)
   - Reads uncompressed local folders selected via Document File Picker (`OpenDocumentTree`)

3. **Interactive Wallpaper Engine**:
   - Double-finger pinch-to-zoom model sizing
   - Single finger drag positioning
   - Single tap screen touch to trigger tap motions / track changes
   - Automatic power saving: rendering pauses when screen is off (`onVisibilityChanged(false)`)

4. **Jetpack Compose UI**:
   - Single-click **"Set Wallpaper"** shortcut launching Android `WallpaperManager`
   - Model preview canvas and scale slider

---

## 🚀 How to Build in Android Studio

1. Open **Android Studio Jellyfish / Iguana / Giraffe (2023.3+)**.
2. Select **File -> Open** and choose this extracted project directory.
3. Gradle will automatically sync using `mavenCentral()` and `https://oss.sonatype.org/content/repositories/snapshots/`.
4. Connect an Android device or emulator running **Android 7.0 (API 24)** or higher.
5. Click **Run 'app'** (`Shift + F10`).

---

## 📁 File Structure Overview

```
SpineWallpaper/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/spine/wallpaper/
│           │   ├── MainActivity.kt            (Jetpack Compose Main UI)
│           │   ├── loader/
│           │   │   └── SpineModelLoader.kt    (Coroutines ZIP & Folder Parser)
│           │   ├── model/
│           │   │   └── Live2DConfig.kt        (Live2DViewerEX .config.json Parser)
│           │   ├── service/
│           │   │   ├── SpineWallpaperService.kt (WallpaperService Engine)
│           │   │   └── SpineGlRenderer.kt       (OpenGL ES Spine Renderer)
│           │   └── reference/
│           │       ├── SimpleAnimation.kt     (Sample reference)
│           │       └── PlayPause.kt           (Sample reference)
│           └── res/
│               └── xml/
│                   └── spine_wallpaper_info.xml
└── build.gradle.kts
```
