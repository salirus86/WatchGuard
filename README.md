# WatchGuard — Android camera object detector

Starter Android app for detecting people and common vehicles with the rear camera.

## Features
- Live camera preview.
- On-device object detection using MediaPipe EfficientDet-Lite0 (COCO labels).
- Highlights people and vehicles: car, truck, bus, motorcycle, bicycle and train.
- Audible alert, with an on-screen switch.
- Saves a camera photo to `Pictures/WatchGuard` when a supported object is detected (at most once every 5 seconds), with a switch to disable saving.
- Detection runs on the phone; no video is uploaded to a server.

## Build APK with GitHub Actions
1. Create a new empty GitHub repository.
2. Upload the contents of this folder to the repository root (including the hidden `.github` folder).
3. Open **Actions** and enable workflows if GitHub asks.
4. Run **Build WatchGuard APK** with **Run workflow**, or push a commit to `main`.
5. Wait for the green check mark.
6. Open the completed workflow run, find **Artifacts**, and download `WatchGuard-debug-apk`.
7. Unzip the artifact and install `app-debug.apk` on the phone. Allow installation from the browser/file manager if Android asks.

## Build locally
Requires Android Studio / Android SDK and JDK 17. Download the model file into
`app/src/main/assets/efficientdet_lite0.tflite`, then run:

```bash
gradle assembleDebug
```

## Important notes
- This is a starter project, not a tested release build. The first GitHub build is the compile check; fix any dependency/API errors reported by Actions before installing.
- The model detects COCO object categories, not identity. It does not identify who a person is.
- Accuracy depends on lighting, distance, camera quality and model limitations. It is not a certified security or safety system.
- Detection and alerts work while the app is open. Android may stop camera use when the app is backgrounded or the screen is locked.
- The app only requests camera permission. Photos are saved through Android MediaStore.
- For a release APK suitable for wider distribution, configure signing and test on the actual Honor X8c.
