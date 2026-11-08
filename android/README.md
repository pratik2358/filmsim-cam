# FilmCam for Android

Native Android version of FilmCam (Kotlin, Jetpack Compose, CameraX, OpenGL ES 3).
Same looks, picture settings and maths as the iPhone app and the website.

- Live camera with the look applied on the GPU (one baked 33³ 3D LUT per frame), strength, film grain, grid
- 12 camera looks + 15 film stocks (`app/src/main/assets/luts`, copied from `web/luts`), import `.cube` / Hald CLUT PNG
- Picture settings in two tabs. Tone: Highlight/Shadow, Clarity, Sharpness. Color: Color (saturation), Kelvin temperature,
  R/B white-balance shift, Color Chrome, FX Blue
  (`Adjustments.kt`, a port of `web/adjust.js`, unit-tested to match it exactly)
- Tap to focus/expose, drag up/down for exposure, hold for AE/AF lock, swipe sideways to change look
- 3:4 / 9:16 / Full frame (photos are cropped to what the viewfinder shows), zoom stops (.5×/1×/2×/5× when the phone has them), flash, front/back camera
- Portrait: blurs the background behind people, using ML Kit selfie segmentation on the phone (bundled, works offline). f/1.4–f/16.
  Works on the live camera and on photos opened from the gallery. Without a person in view it leaves the picture sharp.
- Video: switch to VIDEO above the shutter. Records what the viewfinder shows with the look, grain and Portrait blur
  (H.264 up to 1080 px wide + AAC sound) to `Movies/FilmCam`. The flash button becomes a light. Leaving the app stops and saves.
- Photos save to `Pictures/FilmCam` (shows up in Google Photos / the gallery). Nothing is uploaded.

Requires Android 10 or later on an ARM phone (practically every phone).

## Install on a phone

Copy `FilmCam.apk` to the phone (USB cable, Google Drive, email to yourself…), open it from the Files app,
allow that app to "install unknown apps" when Android asks, then tap **Install**. If Play Protect warns about an
unknown app, choose **More details → Install anyway**. Updates install the same way, over the old version.

## Build

Needs JDK 17+ and the Android SDK (platform 36, build-tools 36). With `sdk.dir` set in `local.properties`:

```
./gradlew testReleaseUnitTest assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the key in `keystore/` (not in git: `keystore/filmcam-release.jks` and
`keystore/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
**Keep a backup of that folder**: Android only installs an update over an existing app if it is signed with the
same key. Without it, `assembleRelease` produces an unsigned APK.
