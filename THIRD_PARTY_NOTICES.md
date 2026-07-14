# Third-party notices

This repository contains, builds upon, or generates data from third-party software and data. This document is an attribution summary. The complete tracked license copies are in `third_party/licenses/`, and source-local licenses remain beside imported source material.

When an Android APK is built, the project copies these notices and license texts into the APK under `assets/licenses/`. The ML Kit artifact's own third-party license files are also extracted into `assets/licenses/ml-kit/` during the build.

## Project license scope

Original code authored for this project is released under the MIT License in the repository-root `LICENSE` file, with copyright held by `crazylei12`.

The project MIT License applies only to material the project copyright holder has the right to license. It does not relicense third-party software, data, names, trademarks, images or other assets; those remain subject to their own licenses and rights.

## Smogon damage-calc

- Upstream: <https://github.com/smogon/damage-calc>
- Location: `external/smogon-damage-calc/` Git submodule
- Pinned revision: `2810dbf5edbcf3147e0266f66802a42dba86604b`
- License: MIT
- License copy: `third_party/licenses/smogon-damage-calc-MIT.txt`

The generated `android-app/app/src/main/assets/damage-engine.js` bundle and the base set data used for `src/data/damage/champions-presets.json` come from this pinned dependency together with project adapter code. The upstream copyright and permission notice are retained in the submodule and copied independently into the public source tree and built APK.

## pkmn/ps (`@pkmn/dex`)

- Upstream: <https://github.com/pkmn/ps>
- Package: `@pkmn/dex` version `0.10.5`
- Referenced package revision: `fe861ca07f336756b4e7ebf1e23dbb1cb0195c69`
- License: MIT
- License copy: `third_party/licenses/pkmn-ps-MIT.txt`

`tools/android/export-champions-presets.mjs` uses `@pkmn/dex` to obtain learnsets. Those results are included in the generated `src/data/damage/champions-presets.json` file, so its license is retained even though the package itself is not copied into the APK as a standalone module.

## 42arch Pokémon Chinese dataset

- Upstream: <https://github.com/42arch/pokemon-dataset-zh>
- Location: `src/data/localization/sources/42arch-pokemon-dataset-zh/`
- License: MIT
- Source-local license: `src/data/localization/sources/42arch-pokemon-dataset-zh/LICENSE`

The generated `src/data/localization/zh-Hans.json` retains only the localized name mappings used by the app. The upstream README says the dataset was scraped from 52Poké, so the public tree does not redistribute the four complete raw JSON snapshots, which also contained unused descriptions and other fields. The original source README and license remain tracked for provenance, and the license is copied into built APKs as `assets/licenses/42arch-pokemon-dataset-zh-MIT.txt`. The upstream MIT notice does not by itself clear any independent rights in source-site text, names or trademarks.

## PokeAPI metadata and sprite-source index

- PokeAPI upstream: <https://github.com/PokeAPI/pokeapi>
- PokeAPI license: BSD 3-Clause
- License copy: `third_party/licenses/pokeapi-BSD-3-Clause.txt`
- Sprite index upstream: <https://github.com/PokeAPI/sprites>
- Repository dedication: CC0 1.0, subject to the upstream rights warning
- License and warning copy: `third_party/licenses/pokeapi-sprites-CC0-1.0.txt`

`src/data/pokemon-icons/` contains source metadata, mappings, remote URLs and a generated catalog used by local development tools. The public repository does not contain the downloaded Pokémon image files.

The PokeAPI sprites license expressly says that the image contents are copyright The Pokémon Company. CC0 for the repository does not clear third-party image, character or trademark rights. Therefore this project does not treat those images as MIT-licensed and excludes downloaded images and derived recognition packs from the public source distribution.

## Android runtime dependencies

The Android app declares these principal runtime dependencies:

- AndroidX and Jetpack Compose components: Apache License 2.0.
- Kotlin standard/runtime components: Apache License 2.0.
- OpenCV for Android `4.13.0`: Apache License 2.0.
- Google ML Kit Chinese text recognition `16.0.1`: governed by the ML Kit Terms of Service and applicable artifact notices.

The shared Apache License 2.0 text is kept at `third_party/licenses/APACHE-2.0.txt`. The ML Kit Terms of Service pointer is kept at `third_party/licenses/ml-kit-TERMS.txt`, and the build extracts `third_party_licenses.txt` plus `third_party_licenses.json` from the exact ML Kit AAR into the APK. Android builds can also resolve transitive dependencies; review the resolved dependency report again whenever dependency versions change.

## Development and build tooling

Node.js packages used to build or check the project retain their own package metadata and licenses in a local dependency installation. Direct build-only packages currently include `sharp` (Apache License 2.0), `esbuild` (MIT), TypeScript (Apache License 2.0), and `@types/node` (MIT). They are not committed as `node_modules` and are not shipped as standalone APK libraries; generated outputs remain subject to any applicable upstream notices described above.

The Gradle wrapper JAR is distributed by the Gradle project under Apache License 2.0 and contains its own `META-INF/LICENSE` notice. Test-only and optional local Python dependencies are development tools and are not included in release APKs.

## Excluded image and private inputs

The public repository intentionally excludes:

- downloaded images from 52Poké, Bulbagarden, PokeAPI or other sites;
- screenshots captured from Pokémon Champions;
- user-labeled screenshot crops;
- generated image-template caches and Android recognition feature packs derived from those images.

Developers who obtain, generate or redistribute such files are responsible for checking the applicable terms and obtaining any necessary permission. A repository-level software license must not be assumed to cover third-party Pokémon imagery.

## Trademarks and affiliation

Pokémon and related names, characters and imagery are trademarks or copyrighted material of their respective owners. This is an unofficial fan-made development project and is not endorsed by or affiliated with Nintendo, Creatures, GAME FREAK, The Pokémon Company or the Pokémon Champions team.
