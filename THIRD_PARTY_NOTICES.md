# Third-party notices

This repository contains or builds upon third-party software and data. This file is an attribution summary, not a replacement for the license files shipped with each component.

## Project license scope

Original code authored for this project is released under the MIT License in the repository-root `LICENSE` file, with copyright held by `crazylei12`.

The project license applies only to material the project copyright holder has the right to license. It does not relicense third-party software, data, names, trademarks or imagery; those remain subject to their own licenses and rights.

## Smogon damage-calc

- Upstream: <https://github.com/smogon/damage-calc>
- Location: `external/smogon-damage-calc/` Git submodule
- Pinned revision: `2810dbf5edbcf3147e0266f66802a42dba86604b`
- License: MIT

The generated `android-app/app/src/main/assets/damage-engine.js` and `src/data/damage/champions-presets.json` are built from this pinned dependency and the project's adapter code. The upstream license is retained in the submodule and in `android-app/app/src/main/assets/licenses/smogon-damage-calc-LICENSE.txt`.

## 42arch Pokémon Chinese dataset

- Upstream project: 42arch Pokémon Chinese dataset
- Location: `src/data/localization/sources/42arch-pokemon-dataset-zh/`
- License: MIT

The original license and source README are kept beside the imported source data.

## Image-source metadata

`src/data/pokemon-icons/` contains source metadata, mappings, remote URLs and licensing notes used by local development tools. It does not contain the downloaded Pokémon image files.

The public repository intentionally excludes:

- downloaded images from 52Poké, Bulbagarden, PokeAPI or other sites;
- screenshots captured from Pokémon Champions;
- user-labeled screenshot crops;
- generated image-template caches and Android recognition feature packs.

Developers who obtain or generate those files are responsible for checking the applicable terms and having permission for their use and distribution. Do not assume that a repository-level license covers third-party Pokémon imagery.

## Trademarks and affiliation

Pokémon and related names, characters and imagery are trademarks or copyrighted material of their respective owners. This is an unofficial fan-made development project and is not endorsed by or affiliated with Nintendo, Creatures, GAME FREAK, The Pokémon Company or the Pokémon Champions team.
