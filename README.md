<img width="1280" height="2772" alt="09395f37fee583ca791be6f36584f967" src="https://github.com/user-attachments/assets/28efdad5-0d2d-40d9-8f08-6c1c0668ef51" />

<img width="1280" height="2772" alt="13a4f7846c1b49437db70d6780bc4674" src="https://github.com/user-attachments/assets/4a044e55-0eae-4a14-924d-80eb34494818" />
# SpineWallpaper - Android Spine 2D Live Wallpaper Application

SpineWallpaper is a full-featured Android Live Wallpaper application built with **Kotlin**, **Jetpack Compose**, and the official **spine-android** runtime library with multi-runtime version isolation.

---

## 🌟 Key Features

1. **Multi-Runtime Spine 2D Animation Engine**:
   - Isolated runtimes for Spine **v3.8, v4.0, v4.1, v4.2**
   - Automatically detects skeleton version from `.skel` binary header or `.json`
   - Dispatches rendering to the corresponding relocated runtime module via `ISpineModelAdapter` bridge

2. **File & ZIP Parsing**:
   - Accepts compressed ZIP archives containing `.skel`, `.atlas`, `.png`, and `.config.json`
   - Auto texture path matching and pre-multiplied alpha (PMA) detection
   - Reads uncompressed local folders selected via Document File Picker

3. **Interactive Wallpaper Engine**:
   - Double-finger pinch-to-zoom model sizing
   - Drag positioning & animation track switching
   - Automatic power saving: pauses rendering when screen is off

---

## 🚀 How to Build in Android Studio

1. Open **Android Studio (2023.3+)**.
2. Select **File -> Open** and choose this extracted project directory.
3. Gradle will sync modules: `:app`, `:spine-core-bridge`, `:spine-runtime-v38`, `:spine-runtime-v40`, `:spine-runtime-v41`, `:spine-runtime-v42`.
4. Connect an Android device running **Android 7.0 (API 24)** or higher.
5. Click **Run 'app'** (`Shift + F10`).
