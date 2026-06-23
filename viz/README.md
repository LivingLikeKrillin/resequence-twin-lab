# resequence-twin viz — OpenUSD PBS Exporter + CPU Schematic GIF Renderer

Two complementary outputs from the same PBS trajectory data:

| Output | Tool | Requires | What it shows |
|--------|------|----------|---------------|
| `pbs_twin.usda` | `viz/export.py` | USD viewer (usdview / Omniverse / Blender) | 3D digital-twin scene, photoreal-capable |
| `pbs_twin.gif`  | `viz/render.py` | Python + matplotlib (CPU only, no GPU) | **2D schematic** of resequencing dynamics |
| `pbs_compare.gif` | `viz/compare.py` | Python + matplotlib (CPU only, no GPU) | **static-vs-dynamic** schematic — instant visual diff of resequencing quality |

## See the differentiator at a glance — comparison GIF

The fastest way to demonstrate the value of multi-objective resequencing is the comparison GIF: one animated file, two synchronized panels.

**What the viewer sees:** static policy (round-robin + FIFO) on top, dynamic policy (multi-objective) on bottom, same seed/bodies, synchronized frame-by-frame. Live colour-change KPI counter per panel shows the gap closing/widening in real time.

```bash
# From running control service (http://localhost:8081 by default):
python -m viz.compare --seed 42 --bodies 100 --out pbs_compare.gif

# From saved JSON files (fully offline):
python -m viz.compare --static-file static.json --dynamic-file dynamic.json --out pbs_compare.gif

# Set control service URL:
RESEQUENCE_TWIN_CONTROL_URL=http://myserver:8081 python -m viz.compare --seed 42 --bodies 100 --out pbs_compare.gif
```

**SCHEMATIC — not a 3D render.** Colour-change counts are the real KPI computed from the released sequence.

## What each tool does

### 3D USD exporter (`viz/export.py`)

- Reads the trajectory JSON produced by `GET /api/trajectory` or a saved `.json` file.
- Authors a `.usda` scene: ground plane, lane guide boxes, one `UsdGeom.Cube` per body (scaled to a car-ish 4×1.5×2 m box), `displayColor` primvar for paint colour, USD time-sampled translate + visibility ops encoding arrival → occupancy → release animation.
- Lightweight geometry + `displayColor` only — no MDL/material networks — so it opens in **usdview** (OpenGL/Storm, no RTX required), Blender, and Omniverse.

### CPU schematic GIF renderer (`viz/render.py`) — view without a GPU/viewer

> **SCHEMATIC, not the 3D render.**
> `pip install usd-core` ships no imaging (UsdImagingGL / Hydra is absent), so the
> `.usda` cannot be rendered on a GPU-less PC.  This renderer is a 2D data-visualisation
> of the same trajectory — use it on any machine with Python + matplotlib.

- Renders an **animated GIF** (matplotlib Agg backend, fully headless).
- Per-frame layout (top-down schematic):
  - **Lane rows** (L1/L2/L3 …): each slot cell coloured by body paint colour; front-of-lane (release side) marked with an arrow; body released this step highlighted with a bold orange outline.
  - **Assembly-output rail**: accumulates released bodies left-to-right — the resequencing result visible in colour order.
  - **Two colour-strip bars** at the top: *paint-input order* vs *assembly-output order so far* — the viewer literally watches colour-batching emerge step by step.
  - Title: seed, policy, `step i / N`, and a "SCHEMATIC (not the 3D render)" tag.
- Optional PNG sequence (`frame_0000.png` …) for stills.

### Static-vs-dynamic comparison GIF (`viz/compare.py`) — instant visual diff

- Renders a **single animated GIF** with two stacked panels (static policy on top, dynamic policy on bottom) sharing the same time axis.
- Both panels are 2D schematics (same lane/output-rail layout as `viz/render.py`) synchronized frame-by-frame.
- Live colour-change KPI counter displayed per panel — shows the resequencing quality gap live as steps progress.
- Requires two trajectory JSON inputs (static and dynamic policies) from REST or files.
- Supports all the same sizing/speed options (`--fps`, `--dpi`).

## Install

```bash
cd viz
python -m pip install -e .[dev]
```

Dependencies added for the schematic renderer: `matplotlib>=3.8`, `pillow>=10.0`, `numpy>=1.26`.

## Run — 3D USD export

```bash
# From running control service (default http://localhost:8081):
python -m viz.export --seed 42 --bodies 100 --policy dynamic --out pbs_twin.usda

# From a saved JSON file (no network needed):
python -m viz.export --from-file trajectory.json --out pbs_twin.usda
```

## Run — single-policy schematic GIF (no GPU, no viewer install)

```bash
# From running control service:
python -m viz.render --seed 42 --bodies 100 --policy dynamic --out pbs_twin.gif

# From a saved JSON file (fully offline):
python -m viz.render --from-file trajectory.json --out pbs_twin.gif

# Control speed and resolution:
python -m viz.render --from-file trajectory.json --out pbs_twin.gif --fps 4 --dpi 96
```

Open `pbs_twin.gif` in any browser or image viewer — no USD tooling required.

## Run — comparison GIF (static vs dynamic)

```bash
# From running control service (http://localhost:8081 by default):
python -m viz.compare --seed 42 --bodies 100 --out pbs_compare.gif

# From saved JSON files (fully offline):
python -m viz.compare --static-file static.json --dynamic-file dynamic.json --out pbs_compare.gif

# Control speed and resolution:
python -m viz.compare --seed 42 --bodies 100 --out pbs_compare.gif --fps 4 --dpi 96

# Set control service URL:
RESEQUENCE_TWIN_CONTROL_URL=http://myserver:8081 python -m viz.compare --seed 42 --bodies 100 --out pbs_compare.gif
```

Open `pbs_compare.gif` in any browser or image viewer — side-by-side comparison of static and dynamic policies in one file.

## View the 3D USD export

### usdview (recommended for RTX 4060 Ti 8 GB — no Omniverse needed)

```bash
usdview pbs_twin.usda
```

`usdview` ships with `usd-core` and uses OpenGL/Storm — works fine on 8 GB VRAM. Press **Space** to play the animation.

### Blender

`File > Import > Universal Scene Description (.usd)` — built in since Blender 3.x.

### NVIDIA Omniverse

Open USD Composer / Kit and load `pbs_twin.usda`. Full RTX render available if VRAM permits (10 GB+ recommended for Omniverse RTX path).

## Run tests (offline, no GPU, no network)

```bash
cd viz
PYTHONIOENCODING=utf-8 python -m pytest -q
```

All tests are headless (matplotlib Agg, no display, no GPU).

## Honesty note

This is a **synthetic PoC**. The trajectory data is procedurally generated (seed-reproducible), not from a real factory floor. The USD export demonstrates the engineering pipeline (PBS simulation → 3D digital twin authoring). The schematic GIF is an honest 2D visualisation of the same data — it makes no claim to be a photoreal render.
