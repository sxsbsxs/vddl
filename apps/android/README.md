# VideoFetch Pro for Android

This is the primary end-user client. It is a native Android application built with Kotlin and Jetpack Compose; it is not a WebView wrapper.

## Requirements
- Android Studio with JDK 17 support
- Android SDK Platform 36
- Gradle 9.6.0
- An Android device or emulator running Android 8.0 (API 26) or newer

## Open and build
Open the `apps/android` directory in Android Studio and sync the Gradle project. The repository pins the Gradle distribution in `gradle/wrapper/gradle-wrapper.properties`. If you use a terminal-only setup, install Gradle 9.6.0 and run:

```bash
gradle -p apps/android assembleDebug
gradle -p apps/android testDebugUnitTest
```

The debug API endpoint defaults to `http://10.0.2.2:8000/`, which reaches the host machine from the Android emulator. A physical device needs a reachable development host or an HTTPS endpoint configured in the app build.

The release build uses an HTTPS placeholder endpoint. Set the production API URL through the release build configuration before distributing an APK.

## Current client capabilities
- Chinese, English, and Japanese Android resources
- Paste a URL from the system clipboard
- Receive plain-text links shared from other Android apps
- Home, Tasks, Files, and Settings navigation
- HTTPS URL syntax checks on-device
- Retrofit API interface for task creation and task listing

## Local session and quality settings
- Preferred quality (best available, 2160p, 1440p, 1080p, 720p, or 480p) is stored locally.
- A user may paste a single Cookie request header for a provider and exact hostname.
- Cookie values are encrypted with AES-GCM using a key held by Android Keystore.
- A Cookie is returned only to a provider adapter that explicitly allow-lists the exact HTTPS hostname. Cookies are never sent to the VideoFetch Pro API.
- The Downloads screen reads device-local Android DownloadManager records. Downloads are stored in the app-specific external files directory.

## Deliberate integration boundary
The backend currently has only a temporary bootstrap API token, not user sign-in or per-user credentials. The Android APK must never contain that shared administrator token. Task submission remains a UI placeholder until server-side account authentication and individual API credentials are implemented. Provider registry is also intentionally empty until an authorized adapter has been reviewed. The local Cookie vault and downloader are infrastructure only; no platform adapter currently uses them, and no quality upgrade is performed until a supported provider resolver is implemented.
