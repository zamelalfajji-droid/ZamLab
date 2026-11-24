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

## What's New in ZamLab v1.2.2

* **Clipping Warning Overlays:** Live highlight and shadow clipping indicators so you can protect bright skies and deep shadows while editing.
* **Rebuilt Real-Time OpenGL Pipeline & Live Histogram:** Smooth, lag-free slider scrubbing with physically accurate linear exposure, white balance, soft highlight roll-off, multi-pixel spatial detail (Texture, Clarity, Dehaze, Sharpen), and real-time RGB histogram updates.
* **Redesigned `All` Parameters Tab & Save Recipe:** Adaptive split-screen workspace with a draggable handle, quick category jump pills, an **Edited Only** filter, and **Save Recipe** to save your custom edits as reusable presets in the LUTs tab.
* **Universal Double-Tap-to-Reset:** Double-tap any slider, numeric readout, color wheel, or tool icon across the entire app to reset that individual setting with light haptic feedback.
* **Explicit Apply Crop & Reset Workflow:** Dedicated **Apply Crop** and **Reset Crop** buttons for an instant cropped preview while keeping full non-destructive re-cropping anytime.
* **Android Share Sheet & "Open With" Support:** Share or open photos directly into ZamLab from your phone's Gallery, Google Photos, or file manager.
* **Multi-Format Import & 4-Format Export Studio:** Import DNG RAW, HEIC, TIFF, WebP, PNG, and JPG photos, and export full-resolution **JPG**, **Lossless PNG**, **WebP**, or **Print-Ready PDF** with a one-tap post-export Share toast.
* **360dp Responsive Polish & Stability:** Optimized layout spacing for compact screens and improved memory/URI safety for crash-free editing and exporting.

v 1.3.0


Here is everything included in this update:

1. Rebranded to Z-Effect
Updated the app name to Z-Effect across strings.xml, AndroidManifest.xml, Gradle/project metadata, and native C++ engine labels while keeping your internal Kotlin package (com.zamalfajji.zamlab) intact so builds and workflows stay compatible.

Updated exported file prefixes to Z-Effect_* and output folders to Pictures/Z-Effect and Downloads/Z-Effect.

2. Offline AI Object Removal (Spot Heal / migan_pipeline_v2.onnx)
Integrated ONNX Runtime (com.microsoft.onnxruntime:onnxruntime-android 1.32.0) with lazy extraction of migan_pipeline_v2.onnx into cacheDir/onnx/ and XNNPACK/CPU execution, keeping zero internet permissions.

Fixed black-smudge and high-contrast border artifacts by inspecting inputInfo/outputInfo tensor formats dynamically, expanding the MI-GAN context crop by 64px with 4px mask dilation, and restricting the preview fallback solver to interior gradient pixels with 8px boundary feathering.

3. Native OpenGL ES 3.0 Masking Engine (MASKS Tab)
Built modular GLSL ES 3.00 (#version 300 es) masking shaders (MaskShaderCommon.kt, MaskSubMaskShaders.kt, GuidedFilterShaders.kt, MaskLocalAdjustShader.kt, MaskRubylithOverlayShader.kt).

Added Guided Image Filter edge-snap feathering, Luminance Range (Rec. 709) and Color Range (CIELAB + eyedropper) parametric masks, resolution-independent UV vector brushes, Linear/Radial gradients with draggable handles, Boolean sub-mask operations (Add, Subtract, Intersect), and a real-time Rubylith red overlay (O shortcut) connected to 8 local adjustments.

4. Offline 2× & 4× AI Super-Resolution (UPSCALE Tab)
Added the UPSCALE tab right next to ALL (LocalUpscaleEngine.kt + UpscalePanel.kt) with 2× and 4× scaling, pixel/megapixel target readout, progress bar, and single-step Undo/Redo.

Uses overlapping 256×256 tiles with 16px feathering via realesrgan_x2.onnx (falling back to multi-pass Lanczos3 + unsharp detail sharpening when no ONNX model is bundled).

5. Non-Destructive RAW/Photo Noise Reduction (DENOISE Tab)
Added the DENOISE tab next to UPSCALE (LocalDenoiseEngine.kt + DenoisePanel.kt) with one-tap presets (Off, Subtle, Natural, High ISO / Night, AI Clean), 5 sliders (Luminance, Detail, Contrast, Color, Smoothness), press-and-hold Before/After split comparison, and optional tiled nafnet_denoise.onnx / scunet_denoise.onnx support.

6. Composition Grid Overlays (Grid Button & Crop Panel)
Added a Grid toggle button in the floating utility pill below the photo and inside the Crop panel (CompositionGrid.kt + CompositionGridOverlay.kt) with crisp dual-stroke lines that never bake into exports.

Supports Rule of Thirds (3×3), Square/Alignment (8×8), Golden Ratio (Phi), Fibonacci Spiral (with 4-corner Flip), Dynamic Symmetry, and Center Crosshair & Safe Zones (90% Action / 80% Title).

7. Unified OLED Studio Precision Ruler-Dial UI
Upgraded the palette to true OLED black (#000000), elevated charcoal (#121214) pills with 1dp (#242428) borders, and warm amber-gold (#F5A623) active highlights.

Replaced plain slider bars across Adjust, All, Denoise, Masks, LUTs, Crop, and Color with tactile tick-mark ruler dials, tabular monospace readouts, haptic tick detents, and double-tap zero reset, plus a unified floating utility pill (Touch Adjust, Histogram, Info HUD, Mask Overlay, Grid).
