# Android application

The Android app is a native Kotlin and Jetpack Compose project targeting Android 13+.

It provides a local manual damage calculator, private saved-team storage, a user-authorized MediaProjection session, a draggable overlay or opt-in battle HUD, and optional screenshot recognition. Damage calculations run inside a local WebView against the generated JavaScript engine. Network access supports user-triggered GitHub update checks and public team-code queries to the official service. Game 1.2.0 team-code login currently fails with error 1001; use screenshot recognition or manual entry until compatibility is restored. Screenshots and calculations remain local.

## Build on Windows

From the repository root:

```powershell
npm.cmd run android:setup
npm.cmd run android:doctor
npm.cmd run android:test-engine
npm.cmd run android:assemble
```

The default debug build produces two separate single-ABI APKs:

```text
android-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
android-app/app/build/outputs/apk/debug/app-x86_64-debug.apk
```

For a signed phone-only production build, set the next version and run:

```powershell
npm.cmd run version:set -- 1.1.10 15
npm.cmd test
npm.cmd run android:assemble-release-arm64
```

The phone release APK is written to `android-app/app/build/outputs/apk/release/app-arm64-v8a-release.apk`. The `android:assemble-release-arm64` command compiles only `arm64-v8a`; it does not create an emulator or universal APK. The default `android:assemble-release` command remains available when maintainers intentionally need both the ARM64 phone artifact and the local `x86_64` emulator artifact. Release builds require the stable signing key outside the repository.

The app version and Android version code come from the root `package.json`. Version 1.1.10 (15) publishes only the standard ARM64 APK; replay and HarmonyOS are outside this release. The application ID and production signer are unchanged. Installing this package over an older replay build switches to the standard feature set. `config/android-release-variant.txt` identifies the build. Settings can manually check the stable or preview channel from `crazylei12/Pokemon-Champions-dmg_cal`. See `docs/release_1.1.10_zh.md` and `docs/android_update_release_guide_zh.md` for release details. The version-setting command above documents this release's bump; on an already-versioned checkout, skip it or choose a strictly higher version code for a future release.

Android Studio is optional. If it is installed separately, open `android-app/`; `local.properties` remains local and is ignored by Git.

## Recognition assets

The public tree includes localization, ROI configuration and the finalized Android `team-preview-templates-v2.bin` runtime feature pack. Personal screenshots, downloaded source images, labeled crops, intermediate templates and generated `.pkl` caches remain excluded.

Maintainers can reproduce or replace the feature pack only after supplying a local corpus they are permitted to use:

```powershell
python -m pip install -r requirements-recognition.txt
npm.cmd run recognition:android:templates
```

A clean checkout builds with the tracked finalized pack and supports offline team-preview matching. The build and APK release checks reject a missing pack or mismatched feature/ROI hashes.
