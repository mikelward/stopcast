# Play Store graphics

`icon-512.png` — the 512×512 Play Store launcher icon. It composites the adaptive
icon (the `ic_launcher_background` color plus `ic_launcher_foreground`) cropped to
the 72dp safe zone so the mark fills the frame.

Regenerate after any change to the icon drawables:

```sh
python3 scripts/render-store-icon.py   # from the repo root; needs Pillow
```

The render is deterministic, so the committed PNG should match a fresh run. There
is no CI check enforcing that yet — see `TODO.md` (Phase 5). If we add one, folding
the assertion into an existing screenshot class is near-free; a dedicated Roborazzi
step costs ~8–9s/run (measured on the sibling repos).
