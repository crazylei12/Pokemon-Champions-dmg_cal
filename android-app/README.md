# Android application

The Android app is a native Kotlin and Jetpack Compose project targeting Android 13+.

It provides a local manual damage calculator, private saved-team storage, a user-authorized MediaProjection session, a draggable overlay and optional screenshot recognition. Damage calculations run inside a local WebView against the generated JavaScript engine. The app does not request network permission or load remote pages.

## Build on Windows

From the repository root:

```powershell
npm.cmd run android:setup
npm.cmd run android:doctor
npm.cmd run android:test-engine
npm.cmd run android:assemble
```

The debug APK is written to:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

Android Studio is optional. If it is installed separately, open `android-app/`; `local.properties` remains local and is ignored by Git.

## Recognition assets

The public tree includes localization and ROI configuration, but not personal screenshots, downloaded image templates, generated `.pkl` caches or the Android `team-preview-templates-v2.bin` feature pack.

Developers can generate the feature pack only after supplying a local corpus they are permitted to use:

```powershell
python -m pip install -r requirements-recognition.txt
npm.cmd run recognition:android:templates
```

The manual calculator, saved-team editor, own-team OCR and damage overlay do not require the team-preview feature pack. Team-preview image matching reports a missing-resource error until the pack is generated.
