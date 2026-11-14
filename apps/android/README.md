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

## Platform integrations (0.3.0)
- **Bilibili:** public video metadata and a single progressive MP4 URL when the provider API returns one. DASH-separated audio/video and multi-segment responses are not exposed as a fake single-file download. Optional local Cookie profile: provider ID `bilibili`, exact host `api.bilibili.com`.
- **YouTube:** public metadata preview through the oEmbed endpoint. The official YouTube Data API exposes video metadata, not a general video-file download endpoint. No stream URL is fabricated and platform access controls are not bypassed.
- **Niconico:** public metadata via thumbinfo/watch page. Current version does not negotiate a downloadable media session, so the app displays metadata and clearly states that no download format is available. Optional local Cookie profile host: `www.nicovideo.jp`.

All requests are made directly from the Android device. Cookies are encrypted with AES-GCM using a key held by Android Keystore and are only attached to exact HTTPS hosts explicitly allow-listed by the matching adapter. Cookies are never sent to the VideoFetch Pro backend.

## Other client capabilities
- Chinese, English, and Japanese Android resources
- Paste a URL from the system clipboard or receive shared links
- Local quality preference; the actual formats are limited to what the provider returns for that video and account
- Device-local Android DownloadManager records

The API base URL for debug builds defaults to `http://10.0.2.2:8000/`. Release builds use the HTTPS placeholder configured in Gradle; set a production endpoint before distributing a release APK.
