# FilmCam (FujiFilmCam)

Also available as a native **Android app** (`android/`, see [android/README.md](android/README.md)) and a **website** (`web/`).

iOS camera app that applies Fuji-style film looks **live in the viewfinder** and bakes them into the full-resolution photo.

- Real-time GPU preview (Core Image on Metal, `CIColorCubeWithColorSpace`, 33³ LUTs)
- 12 camera looks (Provia, Velvia, Astia, Classic Chrome, Classic Neg., Nostalgic Neg., Reala Ace, Pro Neg. Std/Hi, Eterna, Bleach Bypass, Acros).
  All but Provia are 3D LUTs fitted to Fujifilm's sample photos (`tools/fit-camera-looks.py`, `FujiFilmCam/LUTs/cam-*.lut`):
  on scenes held out of the fit they average ΔE(OKLab×100) 0.77 from Fujifilm's output, vs 2.56 for the earlier hand-tuned recipes. Provia is a mild recipe (`Engine/LookRecipe.swift`).
- 15 film stocks (Provia 100F, Velvia 50/100, Astia 100F, Sensia, Pro 160C/400H/800Z, Superia 200/400/Reala/X-Tra 800, FP-100C, Acros 100, Neopan 1600) from the RawTherapee collection (CC BY-SA 4.0, see `FujiFilmCam/LUTs/NOTICE.txt`)
- Import your own `.cube` or Hald CLUT PNG (••• › Import LUT)
- Strength slider, film grain, grid, flash, 0.5×/1×/2× lenses, swipe to change look
- Tap to focus/expose, drag up/down for exposure, hold for AE/AF lock
- Portrait mode (depth blur) on iPhones with dual back cameras or TrueDepth: live preview, f/1.4–f/16, Apple's portrait renderer for the saved photo
- Apply looks to existing photos from your library

## Run
Open `FujiFilmCam.xcodeproj` in Xcode, choose your team under Signing & Capabilities, and run on an iPhone (iOS 17+).
The Simulator has no real camera; for testing there, pass the launch argument `-demoImage /path/to/photo.jpg` (Debug builds only).

Not affiliated with Fujifilm. The looks are approximations.

## Web version (`web/`)

A static site with the same looks, for any modern browser (Safari, Chrome, Firefox, Edge) on phone or desktop.
Live camera with the look applied on the GPU (WebGL 2), strength, grain, grid, torch (Android Chrome),
front/back camera, lens buttons (.5 / 1 / tele where the browser exposes them), upload a photo, import `.cube` / Hald PNG LUTs. Nothing is uploaded anywhere.

- Try it locally: `cd web && python3 -m http.server 8000`, then open http://localhost:8000
- Host on GitHub Pages: push this repo, then in the repo's **Settings → Pages** set **Source** to **GitHub Actions**.
  `.github/workflows/pages.yml` publishes `web/` on every push to `main`.
- After changing `FujiFilmCam/Engine/LookRecipe.swift`, regenerate the web LUTs so both versions match:
  ```
  swiftc -O -o /tmp/export-luts tools/export-web-luts/main.swift FujiFilmCam/Engine/LUT.swift FujiFilmCam/Engine/LookRecipe.swift
  /tmp/export-luts FujiFilmCam/LUTs web/luts
  ```
