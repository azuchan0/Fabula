# Fabula

Fabula is an experimental Android camera app built with **Kotlin**, **Jetpack Compose**, **Material 3**, and the **Camera2 API**. It is being developed with the Pixel 9a as the primary target; available camera features depend on the device and its Camera2 implementation.

**Repository:** [github.com/azuchan0/Fabula](https://github.com/azuchan0/Fabula)

RAW burst capture and DNG export are available when the device supports the required Camera2 stream combination. This feature has not yet been validated across devices.

## Current features

- Camera2 viewfinder with a sensor-matched aspect ratio and JPEG capture.
- Camera capability inspection for logical and physical cameras, with a text report export.
- Exposure compensation and manual ISO/shutter controls when supported.
- White-balance presets, optional color-temperature controls, and focus controls when supported.
- Tap-to-focus/metering when the camera advertises compatible regions.
- RAW_SENSOR burst capture and DNG export to `Pictures/Fabula/RAW_Burst_<id>` when a compatible stream combination is available.

Camera2 capabilities vary by device. Unsupported controls and stream combinations are detected and reported rather than assumed.

## Project structure

- `:app` — Compose UI, app lifecycle, MediaStore writers, and the Android Studio C++/JNI sample code.
- `:camera` — Camera2 preview, capture, capability discovery, and camera control state.

## Build

Open the project in Android Studio with the Android SDK and NDK/CMake components installed, or use the Gradle wrapper from the repository root.

```bash
# macOS / Linux
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

```powershell
# Windows
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

The project currently uses `minSdk 34`, `compileSdk 37`, and `targetSdk 36`. `local.properties` is machine-specific and should not be committed.

## License

Fabula is licensed under the [Apache License 2.0](LICENSE).
