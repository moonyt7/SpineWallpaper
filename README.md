# SpineWallpaper - Android Spine 2D Live Wallpaper Application
本项目完全由AI生成
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
<img width="1280" height="2772" alt="8c8d52541dbc400b5eba8b097b72732" src="https://github.com/user-attachments/assets/b0d05222-d755-40fa-a5d7-bff0a6d4b28c" />
<img width="1280" height="2772" alt="ae91baa0c8c9b5b199c5ab2a1a59404" src="https://github.com/user-attachments/assets/7ef1d8de-d919-4b49-96b7-be28062bd560" />
