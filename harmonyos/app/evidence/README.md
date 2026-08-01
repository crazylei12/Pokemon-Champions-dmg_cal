# Stage 2 emulator evidence

This directory contains the standard and replay home-screen screenshots, UI hierarchy dumps, and filtered `PCApp` logs captured from the HarmonyOS API 24 emulator during stage 2 acceptance.

- `pc-stage2-standard.*`: the standard product; `variant-standard` is present and `entry-replay` is absent.
- `pc-stage2-replay.*`: the replay product; `variant-replay` and `entry-replay` are present.
- Both logs contain `APP_NATIVE_BRIDGE_READY` for their matching product.

Regenerate and verify these files with `npm.cmd run harmonyos:emulator:verify` while target `127.0.0.1:5555` is connected.
