# Public release checklist

This repository is the curated public tree. It was created without the private repository's Git history.

## Included

- Android application source and Gradle wrapper.
- TypeScript damage contracts and engine adapter.
- Generated localized name mappings with the source README and license; unused raw scraped descriptions are excluded.
- Generated Smogon-based damage presets and license notice.
- ROI configuration, recognition code and reproducible generation tools.
- Core architecture and data-contract documents.
- A synthetic test fixture that contains no captured user team.

## Intentionally excluded

- `.git` history from the private maintenance repository.
- Personal teams, test exports and app-private backups.
- Game screenshots, OCR samples, evaluation datasets and contact sheets.
- Downloaded Pokémon image assets and local labeled crops.
- Complete upstream localization snapshots containing unused scraped descriptions and metadata.
- Recognition `.pkl` caches and Android template `.bin` files derived from those images.
- APK/AAB files, signing keys, Android SDK state, IDE state and dependencies.
- Device-specific debug records and internal agent planning files.

## Before making the GitHub repository public

- [x] Choose a license for the original project code. Do not reuse a third-party license file as the project license by accident.
- [x] Review `THIRD_PARTY_NOTICES.md` and keep all bundled third-party license files.
- [x] Confirm `git submodule status` points at the reviewed Smogon revision.
- [x] Run `npm.cmd run check:licenses` and confirm the license copies, generated-data attribution and APK packaging configuration agree.
- [x] Run `npm test` from a clean dependency install.
- [x] Run `npm.cmd run android:assemble`; its final release check must confirm `assets/licenses/` and the absence of undeclared network permissions.
- [x] Run Gitleaks against the staged public tree and inspect every tracked binary and unusually large file.
- [x] Confirm no screenshots, teams, caches, APKs, signing files, `.env` files or device identifiers are tracked.
- [ ] If publishing a recognition feature pack or APK that contains one, perform a separate rights review for every source corpus.
- [ ] If distributing the Android app, review the application ID, signing, privacy disclosure, permission explanations and release versioning.

Recommended final inspection:

```powershell
git status --short --branch
git ls-files
git count-objects -vH
git log --oneline --decorate --all
```
