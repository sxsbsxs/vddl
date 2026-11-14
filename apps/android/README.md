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

## Platform integrations (0.3.2)
- **Bilibili:** provider-returned formats are shown, including separate DASH video/audio streams. Split streams download in a persistent foreground-capable WorkManager task and are muxed into one MP4 with Android MediaExtractor/MediaMuxer. Only provider-returned HTTPS CDN URLs on `*.bilivideo.com` / `*.bilivideo.cn` are accepted. Single progressive MP4 uses Android DownloadManager. Legacy multi-segment DURL and device-unsupported codecs fail explicitly rather than producing corrupt files. Optional local Cookie is only sent to `api.bilibili.com`.
- **YouTube:** public metadata preview through the oEmbed endpoint. The official YouTube Data API exposes video metadata, not a general video-file download endpoint. No stream URL is fabricated and platform access controls are not bypassed.
- **Niconico:** public metadata and available quality labels via the guest watch API. An optional local `user_session` Cookie is used only with the logged-in watch API on `www.nicovideo.jp`. Actual file delivery still requires negotiated playback-session creation and heartbeat renewal; it remains metadata-only in this version.

All requests are made directly from the Android device. Cookies are encrypted with AES-GCM using a key held by Android Keystore and are only attached to exact HTTPS hosts explicitly allow-listed by the matching adapter. Cookies are never sent to the VideoFetch Pro backend.

## Other client capabilities
- Chinese, English, and Japanese Android resources
- Paste a URL from the system clipboard or receive shared links
- Local quality preference; the actual formats are limited to what the provider returns for that video and account
- Device-local Android DownloadManager records

Split DASH downloads continue through WorkManager after process restarts. The merged MP4 is saved in the app-specific Downloads folder and opened using a scoped FileProvider URI.

The API base URL for debug builds defaults to `http://10.0.2.2:8000/`. Release builds use the HTTPS placeholder configured in Gradle; set a production endpoint before distributing a release APK.
