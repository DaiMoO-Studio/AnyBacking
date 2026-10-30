# AnyBacking Android app / 伴唱工坊 App

Native Kotlin + Jetpack Compose, minSdk 33 (Android 13), arm64 only.
原生 Kotlin＋Jetpack Compose，minSdk 33（Android 13），只打包 arm64。

## Build / 建置（Docker only, no Android Studio）

Run from the repository root (Linux, macOS, or git-bash on Windows — git-bash needs the `MSYS_NO_PATHCONV=1` prefix):

```bash
docker run --rm -v "$PWD/android:/app" -v anybacking_gradle_cache:/root/.gradle -w /app ghcr.io/cirruslabs/android-sdk:35 \
  java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleRelease
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

- Use `GradleWrapperMain` rather than `./gradlew` (line endings / shebang on Windows checkouts).
  用 `GradleWrapperMain`，不要 `./gradlew`（Windows 的換行／shebang 問題）。
- `assembleDebug` builds a debug APK with developer tools (diagnostics screen, adb key import).
  `assembleDebug` 是 debug 版（有診斷畫面、可用 adb 帶入金鑰）。
- Version: `versionName` / `versionCode` (major×10000 + minor×100 + patch) in `app/build.gradle.kts`.

### Optional files / 選用設定（never commit them / 不要進版控）

| File | Purpose |
|---|---|
| `android/server.properties` | Default server for your own builds: `server=https://your.domain`, optional `pins=<SPKI sha256>,<backup>` to pin a self-signed certificate. Without it the app asks for the server address on first launch. 自己建置時的預設伺服器與憑證釘選；沒有就第一次開 App 時填。 |
| `android/release.properties` | Signing for `assembleRelease -Ppublic` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it release builds use the debug key. 正式簽章；沒有就用 debug 簽章。 |

`-Ppublic` ignores `server.properties`, so the APK starts with an empty server address. 公開版 APK 用 `-Ppublic` 建置。

## Device key / 裝置金鑰

Issue a key on the server (`docker compose exec api python /app/auth.py issue "My phone"`) and paste it in the app's settings
together with the server address. Debug builds can also take it from adb:
在伺服器核發後，在 App 設定頁連同伺服器位址一起貼上；debug 版也能用 adb 帶入：

```bash
adb shell am start -n com.anybacking.app/.MainActivity --es device_key "ab_…"
```

## Code map / 結構

| Where | What |
|---|---|
| `net/Api.kt` | Backend API, error mapping; one OkHttp client per server address |
| `net/ServerConfig.kt` | Server address rules (http only on home network / Tailscale) |
| `net/Pins.kt` | Optional SPKI pinning for the build-time default server |
| `data/DeviceKey.kt` | Device key, encrypted with Android Keystore AES-GCM |
| `player/VocalMixProcessor.kt` | 4-channel → stereo mix, live vocal gain |
| `player/SpectrumTap.kt` | FFT of what is playing (no microphone permission) |
| `player/KaraokePlayer.kt` | ExoPlayer setup, loop, link refresh; key/speed via the system AudioTrack |
| `player/PlaybackService.kt` | Background playback (Media3 session, notification) |
| `player/PlayerController.kt` | Player screen state, per-song settings, lyric fixes |
| `player/CorrectionDraft.kt` | Lyric fix draft (unit tested) |
| `work/DoneWatcher.kt` | Background check for finished songs, notification |
| `ui/` | Screens: setup, library, add song, player, fix mode, settings |

App icon: `tools/icon/mkicon.py` generates the adaptive icon (spectrum ring + microphone). 圖示由 `tools/icon/mkicon.py` 產生。

## Debugging / 除錯

- Debug diagnostics: `adb shell am start -n com.anybacking.app/.MainActivity --ez diag true`.
- Smoothness: `adb shell dumpsys gfxinfo com.anybacking.app reset` → use the app → `dumpsys gfxinfo` (Janky frames).
