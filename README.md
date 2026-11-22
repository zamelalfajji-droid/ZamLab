# ZamLab 📸✨

**ZamLab** is a fast, 100% offline, studio-grade mobile photo editor and color-grading suite for Android built with Jetpack Compose and OpenGL shaders.

[![Download APK](https://shields.io)](https://github.com)

---

## ✨ Key Features

- **🎨 Pro Color & Tone Grading:** Real-time OpenGL adjustments for Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Temperature, Tint, Vibrance, Saturation, Detail, Optics, and Lens Blur.
- **📈 Interactive RGB & Luma Tone Curves:** Precision curve editor with independent **RGB**, **Red**, **Green**, and **Blue** channels.
- **🎞️ Cinema LUTs & Custom Presets:** Apply built-in cinema looks, import custom `.cube` / `.xmp` LUT files, and save your own custom preset recipes.
- **🖌️ Selective Local Masks:** Shape light and color locally using adjustable **Radial** and **Linear Gradient** masks.
- **🩹 100% Offline Heal & Retouch:** Remove blemishes, dust spots, and unwanted objects on-device with **Spot Heal**, **Clone Stamp**, and **Patch/Smooth** brushes—no internet or API keys required.
- **📐 Crop, Rotate & Perspective Geometry:** Interactive composition guides (Rule of Thirds, Golden Ratio, Spiral, Grid), aspect ratio locks, auto-straighten, and vertical/horizontal keystone correction.
- **⚡ Smooth 120Hz Engine & Pro Export:** Dual-resolution proxy preview for lag-free editing on flagship displays (including Galaxy S26 Ultra), live RGB histogram, split before/after comparison, and custom JPEG/PNG/WebP export with sRGB / Display P3 support.

---

## 📲 Installation (Android)

1. Open the [**Releases**](https://github.com) page on your Android phone.
2. Tap **`ZamLab-v1.0.apk`** under **Assets** to download it.
3. Open the downloaded file and tap **Install** (enable *"Install from unknown sources"* if prompted).

---

## 🛠️ Tech Stack

- **Language:** Kotlin 100%
- **UI Framework:** Jetpack Compose (Material 3)
- **Graphics Engine:** Custom OpenGL ES Shaders & Dual-Resolution Proxy Pipeline
- **CI/CD:** Automated APK builds via GitHub Actions
still need a lot of  work to do this just the start

(v1.10)

## What's New in ZamLab v1.1.0

### Native C++ Image Processing Engine
* **Integrated Native C++ Core (`libzamlab_native.so`):** Added a high-performance C++/NDK backend via JNI (`NativeBridge`) to execute heavy pixel operations directly in native memory.
* **Fast C++ Spot Heal Brush:** Rebuilt the offline Spot Heal diffusion inpainting engine in C++ for faster, smoother object and blemish removal on high-resolution photos.
* **True 16-Bit Uncompressed TIFF Export:** Added a **`TIFF (16-Bit)`** export option powered by a native C++ TIFF encoder that streams full 16-bit-per-channel color files directly to storage without hitting Android JVM heap limits.

### Offline AI Create & Multi-Model Manager
* **Dedicated `AI` Studio Tab:** Added an **AI** tab right next to **LUTs** in the bottom capsule dock (`Adjust | All | LUTs | AI | Crop | Heal | Masks`) powered on-device by MediaPipe Stable Diffusion.
* **Multi-Model Registry & Manager:** Download the built-in Stable Diffusion v1.5 bundle (~1.91 GB), add custom `.zip` models from **Hugging Face** or **GitHub** (with automatic `/blob/` to `/resolve/` or `/raw/` link conversion), import local `.zip` bundles from phone storage, switch active models, or delete models to free space.
* **Direct Canvas Handoff:** Generate 1:1, 4:5, or 16:9 images 100% offline with adjustable inference steps (10–25) and load results straight onto ZamLab's OpenGL canvas for instant grading.

### Studio Workspace, RAW Import & System Integration
* **Spacious `All` Tab & Adaptive Split Workspace:** Added a sticky filter bar (`All`, `Edited`, `Light`, `Color`, `Curve`, `Effects`), draggable panel height handle, and **Save Recipe** preset creator.
* **Universal Double-Tap-to-Reset:** Double-tap any slider, ruler, numeric readout, tool tile, HSL channel, Tone Curve point, or Color Grading wheel to reset that individual parameter with light haptic feedback.
* **Non-Destructive Crop Workflow:** Added dedicated **Apply Crop** and **Reset Crop** buttons with instant preview and non-destructive re-cropping.
* **Multi-Format & RAW Photo Import + 5-Format Export:** Import camera RAW (`.dng` plus embedded-preview support for `.cr2`, `.nef`, `.arw`, `.orf`, `.rw2`, `.raf`), HEIC, TIFF, WebP, PNG, and JPG, and export to **JPG**, **PNG**, **WebP**, **PDF**, and **TIFF (16-Bit)** with instant post-export **Share**.
* **Android System Share & "Open With" / "Edit":** Registered ZamLab in Android's system Share sheet and Gallery **"Edit in" / "Open with"** menus with safe URI stream caching.

### Bug Fixes & Stability Improvements
* **OutOfMemoryError & Small-Screen Fixes:** Added safe 2048px live preview downsampling, responsive horizontal scrolling for 360dp-wide screens, zoom-aware Touch Adjust gesture arbitration, and CMake/NDK build optimizations.


(v1.2.0)

## What's New in ZamLab v1.2.0

### Native C++ Cinema Optics & Grading Engine
* **Live Native C++ RGB Histogram:** Added real-time RGB channel histogram analysis computed directly in native C++ memory (`libzamlab_native.so`).
* **Native C++ 3D `.cube` LUT Grading:** Accelerated 3D LUT trilinear color interpolation in C++ with automatic Kotlin fallback support.
* **Native C++ Film Halation & Bloom:** Added cinema highlight threshold and separable Gaussian blur kernels in C++ for realistic film halation and soft highlight bloom under **Effects**.
* **Native C++ Optical Lens Effects:** Added **Chromatic Aberration** (radial RGB channel UV shift) and **Anamorphic Streak** (horizontal cyan/blue highlight flare) powered by native C++ pixel kernels.

### Calibrated Studio Charcoal & Cinema Amber Redesign
* **Color-Accurate Darkroom UI:** Upgraded the workspace to a neutral **Calibrated Charcoal (`#121316`)** background with elevated graphite panels and **Cinema Amber (`#F5A623`)** active accents for accurate color judgment.
* **Custom Monochrome Vector Icons:** Replaced generic icons across the top toolbar, adjustment tiles, and floating capsule dock with crisp custom-drawn stroke vector icons.

