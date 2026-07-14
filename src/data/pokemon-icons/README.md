# Pokemon Icon Sources

This directory keeps the Pokemon team-preview icon source configuration and generated catalogs.

Selected source stack: 52poke Pokemon Champions sprites for regular team-preview icons,
Bulbagarden Pokemon Champions shiny menu sprites for shiny variants, then `PokeAPI/sprites`.
PokeAPI shiny paths remain as fallbacks when a Champions-style shiny menu sprite is unavailable.

Why this source:

- 52poke exposes `Champions_...._Sprite.png` files for Pokemon Champions Mega forms, including newly added Mega forms.
- Bulbagarden Archives exposes `Menu CP .... shiny.png` Pokemon Champions shiny menu sprites, which match the team-preview thumbnail style much better than Home artwork.
- PokeAPI has a maintainable numeric mapping through `PokeAPI/pokeapi` CSV metadata.
- `generation-viii/icons` provides 68x56 box sprites, which are closest to Pokemon Champions team-preview thumbnails for non-Mega or already covered forms.
- The same upstream repository also provides Scarlet/Violet sprites and Home artwork as fallbacks for newer forms.
- PokeAPI `other/home/shiny` and `sprites/pokemon/shiny` provide shiny visual variants. These are useful fallbacks, but they are not as close to the team-preview thumbnail style as Champions shiny menu sprites or confirmed in-game crops.

Files:

- `source.manifest.json`: source priority and licensing notes.
- `showdown-pokeapi-overrides.json`: mapping fixes from local `showdownId` names to PokeAPI identifiers.
- `catalog.pokeapi-composite.json`: generated species-to-image catalog.
- `coverage.pokeapi-composite.json`: generated coverage report.

Regenerate:

```sh
node tools/pokemon-icons/sync-pokemon-icons.mjs
```

Download local images for development:

```sh
node tools/pokemon-icons/sync-pokemon-icons.mjs --download
```

Downloaded PNG files are ignored by git under `src/data/pokemon-icons/assets/`. Review asset licensing and distribution policy before packaging these images in a public app.
