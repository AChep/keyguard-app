# Keyguard icons

16 custom monochrome SVGs are retained in this family. All other icon aliases and
call sites use their original artwork. Open [preview.html](preview.html) to inspect
the retained drawings on light and dark backgrounds at 12, 16, 20, and 24 px.
Click a drawing to open its SVG.

Every drawing uses a 24 × 24 viewBox, 2 px strokes, round caps and joins, and
`currentColor`. Shapes are plain paths with transparent backgrounds. The GPG
navigation pair shares its outer geometry; the selected certificate has a
transparent key cutout.

## Edit and regenerate

Edit the `keyguard-*.svg` sources, then run from the repository root:

```sh
python3 scripts/generate_keyguard_icons.py
python3 scripts/generate_keyguard_icons.py --check
```

The generator writes cached `ImageVector`s to `KeyguardVectors.kt` and rebuilds
the standalone preview. It validates the SVG grid, monochrome paths, and matching
custom aliases in `Icons.kt`. Legacy aliases continue to use their original
Material, Feather, or existing custom icons. There is no runtime SVG dependency.
