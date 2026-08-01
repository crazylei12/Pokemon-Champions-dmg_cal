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

## Stage 7

- `pc-stage7-standard-battle.json` and `pc-stage7-replay-battle.json`: the formal team-preview capture/review entry in both products.
- `pc-stage7-standard-review.json` and `pc-stage7-replay-review.json`: twelve-slot review with explicit confirmation and manual replacement controls.
- The verifier executes the packaged x86_64 Native/OpenCV sample and reports `NativeSmoke=PASS` but deliberately does not accept screen-capture consent.
- Real album-window frames remain a device gate because the emulator does not output AVScreenCapture video buffers.

Regenerate and verify Stage 7 evidence with `npm.cmd run harmonyos:phase7:emulator`.

## Stage 8

- `pc-stage8-standard-panel.json` and `pc-stage8-replay-panel.json`: complete bidirectional damage panel with battle-state sections.
- `pc-stage8-standard-hud.json` and `pc-stage8-replay-hud.json`: local-engine HUD result with double/single transitions and hide/restore checks.
- Both variants report `Panel=PASS`, `HudDamage=PASS`, `SingleDouble=PASS`, `HideRestore=PASS`, and `PrivacyPromptClicked=False`.
- Real `TYPE_FLOAT` touch behavior, rotation recovery, and capture-layer isolation remain device gates.

Regenerate and verify Stage 8 evidence with `npm.cmd run harmonyos:phase8:emulator`.

## Stage 9

- `pc-stage9-replay-launch.json`: replay product mode selector with combined, recognition-only and record-only routes.
- `pc-stage9-replay-record-only.json`: the lightweight record-only control surface, without entering the full assistant.
- `pc-stage9-standard-home.json`: standard product still opens the normal home and exposes no replay route.
- The Debug profile verifier prepares the codec pipeline but never starts AVScreenCapture or accepts a privacy prompt.
- The current emulator reports that the H.264 encoder is unavailable, so codec runtime is recorded as `BLOCKED_BY_EMULATOR` rather than PASS.

Regenerate and verify Stage 9 evidence with `npm.cmd run harmonyos:phase9:emulator`.

## Stage 10

- `config/harmonyos-phase10-acceptance.json` covers all 66 frozen feature IDs exactly once: 38 `PASS`, 28 actionable `BLOCKED`, and zero untested entries.
- `docs/harmonyos_phase10_final_acceptance_zh.md` records the 59/59 test result, Stage 3–9 emulator results, final clean Release package hashes, and the real-device continuation checklist.
- `tools/harmonyos/verify-stage10-final.ps1` reproduces phase tests, emulator checks, clean Debug/Release builds, and package validation without automating privacy decisions.

Run the complete available Stage 10 gate with `npm.cmd run harmonyos:phase10:emulator`.
