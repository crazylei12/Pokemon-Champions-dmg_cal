# Canonical third-party license copies

Files in this directory are the repository-level copies used for source distribution and Android packaging. `npm.cmd run check:licenses` verifies their relationship to the pinned dependencies and generated assets.

- `smogon-damage-calc-MIT.txt` must exactly match `external/smogon-damage-calc/LICENSE`.
- `pkmn-ps-MIT.txt` corresponds to `@pkmn/dex` 0.10.5 from `pkmn/ps`.
- `pokeapi-BSD-3-Clause.txt` covers PokeAPI metadata used to generate the committed icon catalog.
- `pokeapi-sprites-CC0-1.0.txt` preserves both the sprites repository's CC0 text and its explicit warning that image contents remain copyright The Pokémon Company.
- `APACHE-2.0.txt` covers the Apache-2.0 Android runtime components listed in the root third-party notices.
- `ml-kit-TERMS.txt` records the governing terms URL; the actual ML Kit artifact's bundled third-party license files are extracted during the Android build.

The 42arch source README and MIT license remain under `src/data/localization/sources/42arch-pokemon-dataset-zh/`. Full raw snapshots are intentionally local-only because the app consumes only the generated localized name mappings.

When a pinned dependency changes, update the applicable license copy, `THIRD_PARTY_NOTICES.md`, generated metadata and the license check together.
