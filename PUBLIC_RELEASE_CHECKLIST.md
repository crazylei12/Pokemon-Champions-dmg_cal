# Public release checklist

This repository is the curated public tree. It was created without the private repository's Git history.

## Every stable Android release

- [ ] Review all commits and tracked-file changes since the previous release tag.
- [ ] Increase both the semantic version and `androidVersionCode`; keep the tag, APK name and release title aligned.
- [ ] Update `CHANGELOG.md`, `README.md`, the Android README, release notes, release guide and any changed third-party notices.
- [ ] Run `npm.cmd test`, release lint, third-party license checks and `npm.cmd audit --omit=dev --audit-level=high`.
- [ ] Build the requested release ABI set from a clean output directory. For a public phone-only release, use `npm.cmd run android:assemble-release-arm64` and confirm no emulator or universal APK was generated.
- [ ] Verify the APK version, single production signer, exact ABI, recognition feature/ROI hashes, packaged license assets and update-only network permission.
- [ ] If one Release contains optional APK variants, give every asset an unambiguous name, verify each artifact independently, record the exact source commit/tag, and prove each installed build prefers its matching variant while still offering the other.
- [ ] If variants are intended to replace one another while preserving data, verify the same application ID, version code and production signer; state clearly that they cannot be installed side by side.
- [ ] Record the APK byte size and SHA-256 in the release notes and release guide, then confirm the uploaded GitHub asset digest matches.
- [ ] Push the release commit and matching tag, publish a non-draft/non-prerelease GitHub Release, mark it latest and re-check the public download.

## Included

- Android application source and Gradle wrapper.
- TypeScript damage contracts and engine adapter.
- Generated localized name mappings with the source README and license; unused raw scraped descriptions are excluded.
- Generated Smogon-based damage presets and license notice.
- ROI configuration, recognition code and reproducible generation tools.
- The finalized Android v2 recognition feature pack and verification metadata required for offline runtime use; source images and intermediate datasets remain excluded.
- Core architecture and data-contract documents.
- A synthetic test fixture that contains no captured user team.
- Four curated README demonstration images with no user-identifying data; any third-party game content is shown only to document interoperability and is excluded from the project license.

## Intentionally excluded

- `.git` history from the private maintenance repository.
- Personal teams, test exports and app-private backups.
- Private or raw game screenshots, OCR samples, evaluation datasets and contact sheets.
- Downloaded Pokémon image assets and local labeled crops.
- Complete upstream localization snapshots containing unused scraped descriptions and metadata.
- Recognition `.pkl` caches, intermediate generated templates and all Android template `.bin` files except the single finalized v2 runtime feature pack.
- APK/AAB files, signing keys, Android SDK state, IDE state and dependencies.
- Device-specific debug records and internal agent planning files.

## Before making the GitHub repository public

- [x] Choose a license for the original project code. Do not reuse a third-party license file as the project license by accident.
- [x] Review `THIRD_PARTY_NOTICES.md` and keep all bundled third-party license files.
- [x] Confirm `git submodule status` points at the reviewed Smogon revision.
- [x] Run `npm.cmd run check:licenses` and confirm the license copies, generated-data attribution and APK packaging configuration agree.
- [x] Run `npm test` from a clean dependency install.
- [x] Run the Android build; its final release check must confirm `assets/licenses/`, arm64-only native code and only the declared update-check network permission.
- [x] Run Gitleaks against the staged public tree and inspect every tracked binary and unusually large file.
- [x] Confirm no unreviewed screenshots, teams, caches, APKs, signing files, `.env` files or device identifiers are tracked; review each intentionally published documentation image for privacy and rights boundaries.
- [x] Review every recognition feature-pack source corpus separately and record the result in `THIRD_PARTY_NOTICES.md`: source images and labeled screenshots are excluded, the finalized runtime feature pack is not relicensed under MIT, and underlying third-party rights remain with their owners.
- [x] For the first stable Android release, review the application ID, stable signing fingerprint, privacy disclosure, permission explanations and release versioning.

Recommended final inspection:

```powershell
git status --short --branch
git ls-files
git count-objects -vH
git log --oneline --decorate --all
```
