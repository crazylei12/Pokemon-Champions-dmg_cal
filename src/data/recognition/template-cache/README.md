# Local recognition template cache

`tools/recognition/pokemon-vision-pipeline.py` can generate precomputed `.pkl` feature caches in this directory from a developer-supplied local corpus.

The cache files are intentionally ignored and are not part of the public repository because the private development caches were derived from screenshots and third-party image templates whose redistribution was not reviewed.

Refresh local caches after changing source images, labels, ROI configuration, augmentation settings or `TEMPLATE_CACHE_VERSION`:

```powershell
python tools/recognition/pokemon-vision-pipeline.py evaluate --refresh-template-cache
```

Keep metrics, overlays, contact sheets and other diagnostics under `.tmp/`.
