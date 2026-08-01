# HarmonyOS emulator evidence

This directory contains UI hierarchy dumps plus locally ignored screenshots and filtered logs captured from the HarmonyOS API 24 emulator.

## Stage 2

- `pc-stage2-standard.*`: the standard product; `variant-standard` is present and `entry-replay` is absent.
- `pc-stage2-replay.*`: the replay product; `variant-replay` and `entry-replay` are present.
- Both logs contain `APP_NATIVE_BRIDGE_READY` for their matching product.

Regenerate and verify these files with `npm.cmd run harmonyos:emulator:verify` while target `127.0.0.1:5555` is connected.

## Stage 3

- `pc-stage3-verification.json`: hidden debug verification page showing `PASS 100 <elapsedMs>`.
- The ignored matching log contains `STAGE3_DAMAGE_100_PASS` and `STAGE3_CATALOG_PASS` from the same run.
- The script installs the standard Debug HAP, validates the ArkWeb engine and packaged runtime catalog, then returns the app to its normal home page.

Regenerate and verify Stage 3 evidence with `npm.cmd run harmonyos:phase3:emulator`.

## Stage 4

- `pc-stage4-standard-seed.json`: the standard product after atomic-write, rollback, corruption-protection, import, and backup-allowlist checks; UI status is `PASS seed 5`.
- `pc-stage4-replay-verify.json`: the replay product installed over standard without clearing data; UI status is `PASS replay persistence`.
- The ignored matching log contains `STAGE4_STORAGE_PASS` and `STAGE4_VARIANT_PASS` from the same run.
- The script also verifies the installed bundle registers `EntryBackupAbility` as a backup extension, then returns the emulator to the normal standard-product home page.

Regenerate and verify Stage 4 evidence with `npm.cmd run harmonyos:phase4:emulator`.

## Stage 5

- `pc-stage5-standard-home.json`: formal standard home with the offline engine ready and persisted teams/presets visible.
- `pc-stage5-standard-presets.json`: searchable user-preset manager with create/edit/delete actions.
- `pc-stage5-standard-calculation-result.json`: the formal free calculator after an actual ArkWeb calculation, including percentage, HP and KO output.
- `pc-stage5-standard-battle.json` and `pc-stage5-standard-settings.json`: battle-assistant guidance and settings/update/backup surfaces.
- `pc-stage5-replay-home.json`: replay product installed without clearing data and displaying its distinct product label.
- The ignored matching log contains native bridge, application data and damage-engine ready markers for both products.

Regenerate and verify Stage 5 evidence with `npm.cmd run harmonyos:phase5:emulator`.

## Stage 6

- `pc-stage6-standard-battle.json` and `pc-stage6-replay-battle.json`: formal battle-assistant page with the capture entry, manual review entry, and `2772×1240` fullscreen guidance.
- `pc-stage6-standard-correction.json` and `pc-stage6-replay-correction.json`: the complete six-slot own-team correction page, including the Ditto one-move rule.
- The verifier injects and clears a representative draft through a Debug-only page; it never mutates the app sandbox from HDC and deliberately does not click the system screen-capture consent entry.
- Actual AVScreenCapture video frames and Core Vision OCR remain a real-device gate because the current emulator does not provide either runtime capability.

Regenerate and verify Stage 6 evidence with `npm.cmd run harmonyos:phase6:emulator`.
