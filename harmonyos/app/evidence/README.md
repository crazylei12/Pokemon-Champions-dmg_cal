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
