# viz/ USD Exporter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `viz/` — a pip-installable Python module that exports a PBS trajectory dict into an OpenUSD `.usda` scene (animated bodies per lane/slot, colour-coded, time-sampled), viewable offline in usdview/Blender/Omniverse; TDD, all tests offline/headless.

**Architecture:** Three flat Python modules (`usd_exporter.py`, `export.py`, `__init__.py`) + `pyproject.toml` + `tests/`, mirroring `agent/` conventions (setuptools `py-modules`, no sub-packages). `build_stage_in_memory(trajectory) -> Usd.Stage` is the pure authoring core (testable without disk I/O); `build_stage(trajectory, out_path)` wraps it and saves; the CLI in `export.py` fetches or loads trajectory JSON, then calls `build_stage`.

**Tech Stack:** Python 3.11+, `usd-core>=23.11` (pxr), `httpx>=0.27`, pytest>=8.2, setuptools>=68.

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `viz/pyproject.toml` | Package `resequence-twin-viz`; deps; pytest config |
| Create | `viz/__init__.py` | Empty package marker |
| Create | `viz/usd_exporter.py` | `COLOR_MAP`, `sanitize_id`, `slot_position`, `build_stage_in_memory`, `build_stage` |
| Create | `viz/export.py` | `__main__` CLI: `--seed`, `--bodies`, `--policy`, `--out`, `--from-file` |
| Create | `viz/README.md` | Usage: usdview (8GB-safe), Blender, Omniverse; honesty note |
| Create | `viz/tests/__init__.py` | Empty — makes tests a package |
| Create | `viz/tests/conftest.py` | `FIXTURE_TRAJECTORY` dict (3 lanes, 5 bodies, 8 frames) |
| Create | `viz/tests/test_helpers.py` | Tests: `sanitize_id`, `COLOR_MAP` all 5 colours distinct |
| Create | `viz/tests/test_usd_exporter.py` | Tests: stage structure, body prims, displayColor, time samples, arrival/release animation |

---

## Chunk 1: Package scaffold + helpers

### Task 1: Create `viz/pyproject.toml`

**Files:**
- Create: `viz/pyproject.toml`

- [ ] **Step 1: Create the file**

```toml
[build-system]
requires = ["setuptools>=68", "wheel"]
build-backend = "setuptools.build_meta"

[project]
name = "resequence-twin-viz"
version = "0.0.1"
description = "resequence-twin OpenUSD exporter (PBS trajectory → .usda 3D twin)"
requires-python = ">=3.11"
dependencies = [
    "usd-core>=23.11",
    "httpx>=0.27",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.2",
]

[tool.setuptools]
py-modules = ["usd_exporter", "export"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

Save to `viz/pyproject.toml`.

- [ ] **Step 2: Create empty `viz/__init__.py`**

Create `viz/__init__.py` with contents:
```python
"""resequence-twin-viz: OpenUSD PBS trajectory exporter."""
```

- [ ] **Step 3: Create `viz/tests/__init__.py`**

Create `viz/tests/__init__.py` — empty file.

- [ ] **Step 4: Install the package in editable mode (from `viz/` dir)**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] resequence-twin/viz"
python -m pip install -e .[dev]
```

Expected: successful install, `resequence-twin-viz 0.0.1` shown.

---

### Task 2: Write failing helper tests + implement helpers

**Files:**
- Create: `viz/tests/conftest.py`
- Create: `viz/tests/test_helpers.py`
- Create: `viz/usd_exporter.py` (stub with helpers only)

- [ ] **Step 1: Create `viz/tests/conftest.py` with fixture trajectory**

This fixture has 3 lanes (capacity 3), 5 bodies, 8 frames:
- Frame 0: BODY-00000 arrives → goes into L1[0]
- Frame 1: BODY-00001 arrives → L1[1]
- Frame 2: BODY-00002 arrives → L2[0]
- Frame 3: BODY-00003 arrives → L1[2]; BODY-00000 released (leaves L1)
- Frame 4: BODY-00004 arrives → L2[1]
- Frame 5: BODY-00001 released (leaves L1)
- Frame 6: BODY-00002 released (leaves L2)
- Frame 7: BODY-00004 released (leaves L2)

```python
"""Shared fixtures for viz tests."""
import pytest


FIXTURE_TRAJECTORY = {
    "synthetic": True,
    "disclaimer": "test fixture",
    "seed": 0,
    "policy": "dynamic",
    "lanes": [
        {"id": "L1", "capacity": 3},
        {"id": "L2", "capacity": 3},
        {"id": "L3", "capacity": 3},
    ],
    "bodies": {
        "BODY-00000": {"color": "RED",    "model": "Focus",   "options": [], "dueDateSeq": 1},
        "BODY-00001": {"color": "BLUE",   "model": "Focus",   "options": [], "dueDateSeq": 2},
        "BODY-00002": {"color": "WHITE",  "model": "Focus",   "options": [], "dueDateSeq": 3},
        "BODY-00003": {"color": "BLACK",  "model": "Focus",   "options": [], "dueDateSeq": 4},
        "BODY-00004": {"color": "SILVER", "model": "Focus",   "options": [], "dueDateSeq": 5},
    },
    "frames": [
        # step 0 — BODY-00000 arrives into L1
        {
            "step": 0,
            "lanes": {"L1": ["BODY-00000"], "L2": [], "L3": []},
            "released": None,
            "arrived": ["BODY-00000"],
        },
        # step 1 — BODY-00001 arrives into L1
        {
            "step": 1,
            "lanes": {"L1": ["BODY-00000", "BODY-00001"], "L2": [], "L3": []},
            "released": None,
            "arrived": ["BODY-00001"],
        },
        # step 2 — BODY-00002 arrives into L2
        {
            "step": 2,
            "lanes": {"L1": ["BODY-00000", "BODY-00001"], "L2": ["BODY-00002"], "L3": []},
            "released": None,
            "arrived": ["BODY-00002"],
        },
        # step 3 — BODY-00003 arrives, BODY-00000 released
        {
            "step": 3,
            "lanes": {"L1": ["BODY-00001", "BODY-00003"], "L2": ["BODY-00002"], "L3": []},
            "released": "BODY-00000",
            "arrived": ["BODY-00003"],
        },
        # step 4 — BODY-00004 arrives into L2
        {
            "step": 4,
            "lanes": {"L1": ["BODY-00001", "BODY-00003"], "L2": ["BODY-00002", "BODY-00004"], "L3": []},
            "released": None,
            "arrived": ["BODY-00004"],
        },
        # step 5 — BODY-00001 released
        {
            "step": 5,
            "lanes": {"L1": ["BODY-00003"], "L2": ["BODY-00002", "BODY-00004"], "L3": []},
            "released": "BODY-00001",
            "arrived": [],
        },
        # step 6 — BODY-00002 released
        {
            "step": 6,
            "lanes": {"L1": ["BODY-00003"], "L2": ["BODY-00004"], "L3": []},
            "released": "BODY-00002",
            "arrived": [],
        },
        # step 7 — BODY-00004 released
        {
            "step": 7,
            "lanes": {"L1": ["BODY-00003"], "L2": [], "L3": []},
            "released": "BODY-00004",
            "arrived": [],
        },
    ],
}


@pytest.fixture()
def trajectory():
    """Return the shared fixture trajectory dict."""
    return FIXTURE_TRAJECTORY
```

- [ ] **Step 2: Create `viz/tests/test_helpers.py` (failing — module not yet created)**

```python
"""Tests for sanitize_id helper and COLOR_MAP constant."""
import pytest
from usd_exporter import sanitize_id, COLOR_MAP


class TestSanitizeId:
    """sanitize_id converts body ids to valid USD prim-name strings."""

    def test_hyphen_replaced_by_underscore(self):
        assert sanitize_id("BODY-00000") == "Body_00000"

    def test_already_clean_id(self):
        # an id with no hyphens should just be returned lower-with-capital unchanged
        assert sanitize_id("BODY_00001") == "Body_00001"

    def test_prefix_capitalised(self):
        result = sanitize_id("BODY-12345")
        assert result.startswith("Body_")

    def test_numeric_suffix_preserved(self):
        assert sanitize_id("BODY-99999") == "Body_99999"

    def test_multiple_hyphens(self):
        # e.g. a weird id like "ABC-DEF-001"
        result = sanitize_id("ABC-DEF-001")
        # hyphens become underscores; result must not contain hyphens
        assert "-" not in result


class TestColorMap:
    """COLOR_MAP maps all 5 body colours to distinct Gf.Vec3f tuples."""

    def test_all_five_colours_present(self):
        expected = {"RED", "BLUE", "WHITE", "BLACK", "SILVER"}
        assert set(COLOR_MAP.keys()) == expected

    def test_all_colours_are_distinct(self):
        values = list(COLOR_MAP.values())
        # compare as tuples for hashability
        tuples = [tuple(v) for v in values]
        assert len(set(tuples)) == 5, "Each colour must map to a distinct RGB"

    def test_red_is_reddish(self):
        r, g, b = tuple(COLOR_MAP["RED"])
        assert r > 0.5, "RED.r should be dominant"
        assert g < r and b < r

    def test_white_is_near_one(self):
        r, g, b = tuple(COLOR_MAP["WHITE"])
        assert r > 0.8 and g > 0.8 and b > 0.8

    def test_black_is_near_zero(self):
        r, g, b = tuple(COLOR_MAP["BLACK"])
        assert r < 0.2 and g < 0.2 and b < 0.2

    def test_blue_is_bluish(self):
        r, g, b = tuple(COLOR_MAP["BLUE"])
        assert b > 0.5 and b > r and b > g

    def test_all_values_in_0_1_range(self):
        for colour, vec in COLOR_MAP.items():
            for component in tuple(vec):
                assert 0.0 <= component <= 1.0, f"{colour} component out of [0,1]"
```

- [ ] **Step 3: Run tests — expect ImportError (module doesn't exist yet)**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] resequence-twin/viz"
PYTHONIOENCODING=utf-8 python -m pytest tests/test_helpers.py -v
```

Expected: `ModuleNotFoundError: No module named 'usd_exporter'`

- [ ] **Step 4: Create `viz/usd_exporter.py` stub with helpers + COLOR_MAP**

```python
"""
usd_exporter.py — OpenUSD scene authoring for PBS trajectory data.

Exports a PBS trajectory dict (from GET /api/trajectory) to a .usda file
with one prim per body, animated via USD time samples (arrival/occupancy/release).

Scene layout (Y-up, right-hand):
  - Ground plane at Y=0, XZ-spread
  - Lanes along +Z axis, slots along +X within each lane
  - Assembly output rail at X = LANE_SLOT_SPACING * max_capacity (beyond last slot)
  - Camera positioned above and behind to see whole PBS

Geometry: UsdGeom.Cube scaled to car-ish box (4×1.5×2 metres).
Colour: displayColor primvar — no MDL/material networks (usdview/Blender-safe).
"""
from __future__ import annotations

from typing import Any

from pxr import Gf, Sdf, Usd, UsdGeom, UsdLux

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

#: Spacing between slot positions within a lane (metres along X)
LANE_SLOT_SPACING: float = 5.0

#: Spacing between lanes (metres along Z)
LANE_ROW_SPACING: float = 4.0

#: Car box half-extents for UsdGeom.Cube (which is -1..+1 by default, so
#: scale = actual half-size).  4m long × 2m wide × 1.5m tall.
CAR_SCALE: tuple[float, float, float] = (2.0, 0.75, 1.0)

#: X position of the assembly-output rail (released bodies park here)
#: Computed dynamically from max capacity; this is a sentinel default.
_ASSEMBLY_RAIL_X_OFFSET: float = 2.0  # multiplied by max_capacity * LANE_SLOT_SPACING

#: USD time codes per second — slow enough to watch in usdview
TIME_CODES_PER_SECOND: float = 6.0

#: Body colour → linear-sRGB RGB triple (Gf.Vec3f-compatible)
COLOR_MAP: dict[str, Gf.Vec3f] = {
    "RED":    Gf.Vec3f(0.85, 0.10, 0.10),
    "BLUE":   Gf.Vec3f(0.10, 0.25, 0.85),
    "WHITE":  Gf.Vec3f(0.92, 0.92, 0.92),
    "BLACK":  Gf.Vec3f(0.08, 0.08, 0.08),
    "SILVER": Gf.Vec3f(0.60, 0.62, 0.65),
}

#: Offstage position for bodies not yet arrived or already released-to-park
_OFFSTAGE: Gf.Vec3d = Gf.Vec3d(0.0, -100.0, 0.0)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def sanitize_id(body_id: str) -> str:
    """Convert a body id to a valid USD prim name.

    USD prim names must not contain hyphens.  We replace hyphens with
    underscores and capitalise the first letter so the result reads as
    ``Body_00000`` rather than ``BODY_00000``.

    Examples::

        sanitize_id("BODY-00000") -> "Body_00000"
        sanitize_id("BODY_00001") -> "Body_00001"
    """
    name = body_id.replace("-", "_")
    # Capitalise first char, keep rest as-is
    return name[0].upper() + name[1:] if name else name


def slot_position(
    lane_index: int,
    slot_index: int,
) -> Gf.Vec3d:
    """Return the world-space position for a lane/slot cell.

    Lanes are spaced along +Z; slots are spaced along +X within each lane.

    Args:
        lane_index: 0-based lane index.
        slot_index: 0-based slot index within the lane (0 = front/release end).

    Returns:
        Gf.Vec3d world position (X, Y=0, Z).
    """
    x = slot_index * LANE_SLOT_SPACING
    z = lane_index * LANE_ROW_SPACING
    return Gf.Vec3d(x, 0.0, z)


def assembly_position(release_order_index: int, num_lanes: int) -> Gf.Vec3d:
    """Return the world-space position on the assembly-output rail.

    Released bodies park at X beyond the last lane slot, spaced along Z.

    Args:
        release_order_index: 0-based index in the release sequence.
        num_lanes: total number of lanes (to compute Z offset).

    Returns:
        Gf.Vec3d world position on the assembly rail.
    """
    # Place rail to the right of any lane content: use a large fixed X offset
    rail_x = 60.0
    z = release_order_index * LANE_ROW_SPACING
    return Gf.Vec3d(rail_x, 0.0, z)


# ---------------------------------------------------------------------------
# Core authoring
# ---------------------------------------------------------------------------

def build_stage_in_memory(trajectory: dict[str, Any]) -> Usd.Stage:
    """Author a USD stage from a PBS trajectory dict and return it in memory.

    This function does NOT write to disk — ideal for offline testing.

    Args:
        trajectory: PBS trajectory dict (from GET /api/trajectory).

    Returns:
        An open Usd.Stage with the full scene authored.
    """
    stage = Usd.Stage.CreateInMemory()
    _populate_stage(stage, trajectory)
    return stage


def build_stage(trajectory: dict[str, Any], out_path: str) -> None:
    """Author a USD stage from a PBS trajectory dict and save to *out_path*.

    The file is written as ASCII USDA (human-readable, diff-able).

    Args:
        trajectory: PBS trajectory dict (from GET /api/trajectory).
        out_path: Destination file path (should end with ``.usda``).
    """
    stage = Usd.Stage.CreateNew(out_path)
    _populate_stage(stage, trajectory)
    stage.GetRootLayer().Export(out_path)


# ---------------------------------------------------------------------------
# Internal scene-building logic
# ---------------------------------------------------------------------------

def _populate_stage(stage: Usd.Stage, trajectory: dict[str, Any]) -> None:
    """Write all prims, attributes, and time samples into *stage*."""
    lanes_spec = trajectory["lanes"]
    bodies_spec = trajectory["bodies"]
    frames = trajectory["frames"]

    num_lanes = len(lanes_spec)
    max_capacity = max(lane["capacity"] for lane in lanes_spec)
    last_step = frames[-1]["step"] if frames else 0

    # -----------------------------------------------------------------------
    # Stage metadata
    # -----------------------------------------------------------------------
    stage.SetStartTimeCode(0.0)
    stage.SetEndTimeCode(float(last_step))
    stage.SetTimeCodesPerSecond(TIME_CODES_PER_SECOND)
    UsdGeom.SetStageUpAxis(stage, UsdGeom.Tokens.y)

    # -----------------------------------------------------------------------
    # /World root Xform
    # -----------------------------------------------------------------------
    world = UsdGeom.Xform.Define(stage, "/World")
    stage.SetDefaultPrim(world.GetPrim())

    # -----------------------------------------------------------------------
    # Ground plane (thin flat box)
    # -----------------------------------------------------------------------
    ground = UsdGeom.Mesh.Define(stage, "/World/Ground")
    width = (max_capacity + 2) * LANE_SLOT_SPACING
    depth = (num_lanes + 1) * LANE_ROW_SPACING
    hw, hd = width / 2.0, depth / 2.0
    hy = 0.1  # very thin
    ground.GetPointsAttr().Set([
        Gf.Vec3f(-hw, -hy, -hd), Gf.Vec3f( hw, -hy, -hd),
        Gf.Vec3f( hw, -hy,  hd), Gf.Vec3f(-hw, -hy,  hd),
        Gf.Vec3f(-hw,  hy, -hd), Gf.Vec3f( hw,  hy, -hd),
        Gf.Vec3f( hw,  hy,  hd), Gf.Vec3f(-hw,  hy,  hd),
    ])
    ground.GetFaceVertexCountsAttr().Set([4, 4, 4, 4, 4, 4])
    ground.GetFaceVertexIndicesAttr().Set([
        0, 1, 2, 3,  # bottom
        4, 7, 6, 5,  # top
        0, 4, 5, 1,  # front
        1, 5, 6, 2,  # right
        2, 6, 7, 3,  # back
        3, 7, 4, 0,  # left
    ])
    dc = ground.GetDisplayColorAttr()
    dc.Set([Gf.Vec3f(0.3, 0.3, 0.3)])

    # -----------------------------------------------------------------------
    # Lane base prims (thin flat box per lane — faint visual guides)
    # -----------------------------------------------------------------------
    lanes_xform = UsdGeom.Xform.Define(stage, "/World/Lanes")
    for l_idx, lane in enumerate(lanes_spec):
        lane_id = lane["id"].replace("-", "_")
        lane_prim_path = f"/World/Lanes/{lane_id}"
        lane_mesh = UsdGeom.Mesh.Define(stage, lane_prim_path)
        cap = lane["capacity"]
        lw = cap * LANE_SLOT_SPACING
        lz = LANE_ROW_SPACING * 0.45
        lhw, lhz = lw / 2.0, lz / 2.0
        lhy = 0.05
        offset_x = lw / 2.0 - LANE_SLOT_SPACING / 2.0
        offset_z = float(l_idx) * LANE_ROW_SPACING
        lane_mesh.GetPointsAttr().Set([
            Gf.Vec3f(offset_x - lhw, -lhy, offset_z - lhz),
            Gf.Vec3f(offset_x + lhw, -lhy, offset_z - lhz),
            Gf.Vec3f(offset_x + lhw, -lhy, offset_z + lhz),
            Gf.Vec3f(offset_x - lhw, -lhy, offset_z + lhz),
            Gf.Vec3f(offset_x - lhw,  lhy, offset_z - lhz),
            Gf.Vec3f(offset_x + lhw,  lhy, offset_z - lhz),
            Gf.Vec3f(offset_x + lhw,  lhy, offset_z + lhz),
            Gf.Vec3f(offset_x - lhw,  lhy, offset_z + lhz),
        ])
        lane_mesh.GetFaceVertexCountsAttr().Set([4, 4, 4, 4, 4, 4])
        lane_mesh.GetFaceVertexIndicesAttr().Set([
            0, 1, 2, 3,
            4, 7, 6, 5,
            0, 4, 5, 1,
            1, 5, 6, 2,
            2, 6, 7, 3,
            3, 7, 4, 0,
        ])
        lane_mesh.GetDisplayColorAttr().Set([Gf.Vec3f(0.45, 0.45, 0.50)])

    # -----------------------------------------------------------------------
    # Light — DistantLight from above
    # -----------------------------------------------------------------------
    light = UsdLux.DistantLight.Define(stage, "/World/SunLight")
    light.GetIntensityAttr().Set(3000.0)
    xform_api = UsdGeom.XformCommonAPI(light.GetPrim())
    xform_api.SetRotate(Gf.Vec3f(-60.0, 0.0, 0.0))

    # -----------------------------------------------------------------------
    # Camera — positioned above-and-behind to see whole PBS
    # -----------------------------------------------------------------------
    camera = UsdGeom.Camera.Define(stage, "/World/Camera")
    cam_x = (max_capacity * LANE_SLOT_SPACING) / 2.0
    cam_y = num_lanes * LANE_ROW_SPACING * 2.5
    cam_z = num_lanes * LANE_ROW_SPACING * 2.0
    cam_xform = UsdGeom.XformCommonAPI(camera.GetPrim())
    cam_xform.SetTranslate(Gf.Vec3d(cam_x, cam_y, cam_z))
    cam_xform.SetRotate(Gf.Vec3f(-40.0, 0.0, 0.0))
    camera.GetFocalLengthAttr().Set(35.0)

    # -----------------------------------------------------------------------
    # Compute per-body arrival step and release step + order
    # -----------------------------------------------------------------------
    arrival_step: dict[str, int] = {}   # body_id -> first step it appears in any lane
    release_step: dict[str, int] = {}   # body_id -> step at which it was released
    release_order: list[str] = []       # ordered list of released body ids

    for frame in frames:
        step = frame["step"]
        # Track arrivals from frame["arrived"]
        for body_id in (frame.get("arrived") or []):
            if body_id not in arrival_step:
                arrival_step[body_id] = step
        # Track releases
        released = frame.get("released")
        if released:
            release_step[released] = step
            if released not in release_order:
                release_order.append(released)

    # -----------------------------------------------------------------------
    # Body prims — one UsdGeom.Cube per body, under /World/Bodies/
    # -----------------------------------------------------------------------
    bodies_xform = UsdGeom.Xform.Define(stage, "/World/Bodies")

    for body_id, body_data in bodies_spec.items():
        prim_name = sanitize_id(body_id)
        prim_path = f"/World/Bodies/{prim_name}"
        cube = UsdGeom.Cube.Define(stage, prim_path)

        # Scale to car-ish box: UsdGeom.Cube is a unit [-1,+1]^3 cube,
        # so scale=(2,0.75,1) gives a 4×1.5×2 metre car.
        xform = UsdGeom.XformCommonAPI(cube.GetPrim())
        xform.SetScale(Gf.Vec3f(*CAR_SCALE))

        # displayColor primvar
        colour_name = body_data.get("color", "WHITE")
        rgb = COLOR_MAP.get(colour_name, COLOR_MAP["WHITE"])
        display_color = cube.GetDisplayColorAttr()
        display_color.Set([rgb])

        # Visibility + translate time samples
        vis_attr = cube.GetVisibilityAttr()
        translate_op = cube.AddTranslateOp()

        arr_step = arrival_step.get(body_id)
        rel_step = release_step.get(body_id)
        rel_idx = release_order.index(body_id) if body_id in release_order else None

        for frame in frames:
            step = frame["step"]
            tc = Usd.TimeCode(float(step))

            if arr_step is None or step < arr_step:
                # Not yet arrived → invisible, parked offstage
                vis_attr.Set(UsdGeom.Tokens.invisible, tc)
                translate_op.Set(_OFFSTAGE, tc)
                continue

            # Check if body is in a lane this frame
            in_lane = False
            for l_idx, lane in enumerate(lanes_spec):
                lane_id = lane["id"]
                occupants = frame["lanes"].get(lane_id, [])
                if body_id in occupants:
                    slot_idx = occupants.index(body_id)
                    pos = slot_position(l_idx, slot_idx)
                    vis_attr.Set(UsdGeom.Tokens.inherited, tc)
                    translate_op.Set(pos, tc)
                    in_lane = True
                    break

            if not in_lane:
                # Body has been released — park on assembly rail
                if rel_idx is not None:
                    pos = assembly_position(rel_idx, num_lanes)
                else:
                    pos = _OFFSTAGE
                vis_attr.Set(UsdGeom.Tokens.inherited, tc)
                translate_op.Set(pos, tc)
```

- [ ] **Step 5: Run helper tests — expect PASS**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] resequence-twin/viz"
PYTHONIOENCODING=utf-8 python -m pytest tests/test_helpers.py -v
```

Expected: 12 tests PASS.

- [ ] **Step 6: Commit scaffold + helpers**

```bash
git add viz/pyproject.toml viz/__init__.py viz/usd_exporter.py viz/tests/__init__.py viz/tests/conftest.py viz/tests/test_helpers.py
git commit -m "feat(viz-1): scaffold viz/ package + usd_exporter helpers (sanitize_id, COLOR_MAP)"
```

---

## Chunk 2: Stage structure and body prim tests

### Task 3: Write failing USD stage tests

**Files:**
- Create: `viz/tests/test_usd_exporter.py`

- [ ] **Step 1: Write `viz/tests/test_usd_exporter.py`**

```python
"""
Offline tests for build_stage_in_memory.

All tests are headless (no GPU, no network) — pxr authoring/reading is CPU-only.
The fixture trajectory is defined in conftest.py.
"""
import os
import tempfile

import pytest
from pxr import Gf, Sdf, Usd, UsdGeom

from usd_exporter import (
    COLOR_MAP,
    build_stage,
    build_stage_in_memory,
    sanitize_id,
    slot_position,
    assembly_position,
)
from tests.conftest import FIXTURE_TRAJECTORY


# ---------------------------------------------------------------------------
# Stage top-level structure
# ---------------------------------------------------------------------------

class TestStageStructure:
    """The stage has the expected root hierarchy."""

    @pytest.fixture(autouse=True)
    def stage(self, trajectory):
        self._stage = build_stage_in_memory(trajectory)

    def test_stage_opens(self):
        assert self._stage is not None

    def test_world_prim_exists(self):
        world = self._stage.GetPrimAtPath("/World")
        assert world.IsValid(), "/World prim must exist"

    def test_camera_exists(self):
        cam = self._stage.GetPrimAtPath("/World/Camera")
        assert cam.IsValid(), "/World/Camera prim must exist"

    def test_light_exists(self):
        # Accept either DistantLight or DomeLight under /World
        prim = self._stage.GetPrimAtPath("/World/SunLight")
        assert prim.IsValid(), "/World/SunLight prim must exist"

    def test_ground_exists(self):
        ground = self._stage.GetPrimAtPath("/World/Ground")
        assert ground.IsValid(), "/World/Ground prim must exist"

    def test_start_time_code_is_zero(self):
        assert self._stage.GetStartTimeCode() == 0.0

    def test_end_time_code_matches_last_frame(self):
        last_step = FIXTURE_TRAJECTORY["frames"][-1]["step"]
        assert self._stage.GetEndTimeCode() == float(last_step)

    def test_default_prim_is_world(self):
        default = self._stage.GetDefaultPrim()
        assert default.GetPath() == Sdf.Path("/World")


# ---------------------------------------------------------------------------
# Body prims
# ---------------------------------------------------------------------------

class TestBodyPrims:
    """One prim per body, with sanitised names, under /World/Bodies/."""

    @pytest.fixture(autouse=True)
    def stage(self, trajectory):
        self._stage = build_stage_in_memory(trajectory)
        self._trajectory = trajectory

    def test_bodies_xform_exists(self):
        bodies = self._stage.GetPrimAtPath("/World/Bodies")
        assert bodies.IsValid()

    def test_one_prim_per_body(self):
        bodies_prim = self._stage.GetPrimAtPath("/World/Bodies")
        children = list(bodies_prim.GetChildren())
        assert len(children) == len(self._trajectory["bodies"])

    def test_prim_names_have_no_hyphens(self):
        bodies_prim = self._stage.GetPrimAtPath("/World/Bodies")
        for child in bodies_prim.GetChildren():
            name = child.GetName()
            assert "-" not in name, f"Prim name '{name}' must not contain hyphens"

    def test_body_00000_prim_exists_at_expected_path(self):
        prim_name = sanitize_id("BODY-00000")
        prim = self._stage.GetPrimAtPath(f"/World/Bodies/{prim_name}")
        assert prim.IsValid()

    def test_all_body_prims_discoverable(self):
        for body_id in self._trajectory["bodies"]:
            prim_name = sanitize_id(body_id)
            prim = self._stage.GetPrimAtPath(f"/World/Bodies/{prim_name}")
            assert prim.IsValid(), f"Prim for {body_id} ({prim_name}) not found"


# ---------------------------------------------------------------------------
# displayColor primvar
# ---------------------------------------------------------------------------

class TestDisplayColor:
    """Each body prim has a displayColor matching its COLOR_MAP entry."""

    @pytest.fixture(autouse=True)
    def stage(self, trajectory):
        self._stage = build_stage_in_memory(trajectory)
        self._trajectory = trajectory

    def test_red_body_has_red_display_color(self):
        # BODY-00000 is RED
        prim = self._stage.GetPrimAtPath(f"/World/Bodies/{sanitize_id('BODY-00000')}")
        cube = UsdGeom.Cube(prim)
        colour_val = cube.GetDisplayColorAttr().Get()
        assert colour_val is not None
        r, g, b = tuple(colour_val[0])
        expected = COLOR_MAP["RED"]
        assert abs(r - expected[0]) < 1e-5
        assert abs(g - expected[1]) < 1e-5
        assert abs(b - expected[2]) < 1e-5

    def test_blue_body_display_color(self):
        prim = self._stage.GetPrimAtPath(f"/World/Bodies/{sanitize_id('BODY-00001')}")
        cube = UsdGeom.Cube(prim)
        colour_val = cube.GetDisplayColorAttr().Get()
        r, g, b = tuple(colour_val[0])
        expected = COLOR_MAP["BLUE"]
        assert abs(b - expected[2]) < 1e-5

    def test_all_bodies_have_display_color(self):
        for body_id, body_data in self._trajectory["bodies"].items():
            prim = self._stage.GetPrimAtPath(f"/World/Bodies/{sanitize_id(body_id)}")
            cube = UsdGeom.Cube(prim)
            val = cube.GetDisplayColorAttr().Get()
            assert val is not None and len(val) > 0, f"{body_id} missing displayColor"


# ---------------------------------------------------------------------------
# Time samples (animation)
# ---------------------------------------------------------------------------

class TestTimeSamples:
    """Bodies have time-sampled translate ops and visibility attrs."""

    @pytest.fixture(autouse=True)
    def stage(self, trajectory):
        self._stage = build_stage_in_memory(trajectory)
        self._trajectory = trajectory

    def _get_translate_attr(self, body_id: str):
        prim = self._stage.GetPrimAtPath(f"/World/Bodies/{sanitize_id(body_id)}")
        # translateOp stores values on the xformOp:translate attribute
        attr = prim.GetAttribute("xformOp:translate")
        return attr

    def _get_vis_attr(self, body_id: str):
        prim = self._stage.GetPrimAtPath(f"/World/Bodies/{sanitize_id(body_id)}")
        cube = UsdGeom.Cube(prim)
        return cube.GetVisibilityAttr()

    def test_translate_attr_has_time_samples(self):
        # BODY-00000 is present from step 0 onward → must have time samples
        attr = self._get_translate_attr("BODY-00000")
        assert attr.IsValid()
        times = attr.GetTimeSamples()
        assert len(times) > 0, "Translate attr must have time samples"

    def test_time_samples_span_all_frames(self):
        # For BODY-00000, samples should exist at every step (0..7)
        attr = self._get_translate_attr("BODY-00000")
        times = attr.GetTimeSamples()
        expected_steps = set(range(len(self._trajectory["frames"])))
        actual_steps = {int(t) for t in times}
        assert expected_steps == actual_steps

    def test_body_invisible_before_arrival(self):
        # BODY-00003 arrives at step 3 (fixture frame 3 has it in arrived)
        # At step 0, 1, 2 it should be invisible
        vis = self._get_vis_attr("BODY-00003")
        for step in (0, 1, 2):
            val = vis.Get(Usd.TimeCode(float(step)))
            assert val == UsdGeom.Tokens.invisible, \
                f"BODY-00003 should be invisible at step {step}, got {val}"

    def test_body_visible_after_arrival(self):
        # BODY-00003 arrives at step 3
        vis = self._get_vis_attr("BODY-00003")
        for step in (3, 4, 5, 6, 7):
            val = vis.Get(Usd.TimeCode(float(step)))
            assert val == UsdGeom.Tokens.inherited, \
                f"BODY-00003 should be visible at step {step}, got {val}"

    def test_body_at_lane_position_when_in_lane(self):
        # BODY-00000 is in L1[0] at step 0 → lane_index=0, slot_index=0
        attr = self._get_translate_attr("BODY-00000")
        pos = attr.Get(Usd.TimeCode(0.0))
        expected = slot_position(0, 0)  # lane 0, slot 0
        assert pos is not None
        assert abs(pos[0] - expected[0]) < 1e-4, f"X mismatch: {pos[0]} != {expected[0]}"
        assert abs(pos[2] - expected[2]) < 1e-4, f"Z mismatch: {pos[2]} != {expected[2]}"

    def test_released_body_moves_to_assembly_rail(self):
        # BODY-00000 is released at step 3 (first released body → release_order_index=0)
        attr = self._get_translate_attr("BODY-00000")
        # At step 3 and later, body should be on assembly rail (X=60)
        pos_at_3 = attr.Get(Usd.TimeCode(3.0))
        assert pos_at_3 is not None
        assert abs(pos_at_3[0] - 60.0) < 1e-4, \
            f"Released body X should be 60.0 (assembly rail), got {pos_at_3[0]}"

    def test_body_not_yet_arrived_is_offstage(self):
        # BODY-00003 not arrived at step 0 → Y=-100 (offstage)
        attr = self._get_translate_attr("BODY-00003")
        pos = attr.Get(Usd.TimeCode(0.0))
        assert pos is not None
        assert pos[1] == -100.0, f"Pre-arrival body should be at Y=-100, got {pos[1]}"


# ---------------------------------------------------------------------------
# build_stage — writes a .usda file and re-opens it
# ---------------------------------------------------------------------------

class TestBuildStageFile:
    """build_stage writes a .usda that can be re-opened with Usd.Stage.Open."""

    def test_writes_usda_and_reopens(self, trajectory):
        with tempfile.NamedTemporaryFile(suffix=".usda", delete=False) as f:
            out_path = f.name
        try:
            build_stage(trajectory, out_path)
            assert os.path.exists(out_path), ".usda file was not created"
            assert os.path.getsize(out_path) > 0, ".usda file is empty"
            reopened = Usd.Stage.Open(out_path)
            assert reopened is not None
            world = reopened.GetPrimAtPath("/World")
            assert world.IsValid(), "/World must exist in re-opened stage"
        finally:
            if os.path.exists(out_path):
                os.unlink(out_path)
```

- [ ] **Step 2: Run tests — expect FAIL (stage tests pass, some assertions may fail)**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] resequence-twin/viz"
PYTHONIOENCODING=utf-8 python -m pytest tests/test_usd_exporter.py -v
```

Expected: Tests run; any failures reveal gaps in `usd_exporter.py`. Fix iteratively.

- [ ] **Step 3: Fix any failures in `usd_exporter.py` until all tests pass**

Key areas to watch:
- `xformOp:translate` attribute name — `UsdGeom.Cube.AddTranslateOp()` creates `xformOp:translate`; verify via `prim.GetAttribute("xformOp:translate")`.
- `cube.GetVisibilityAttr()` is an inherited attr from `UsdGeom.Imageable`; time-setting uses `.Set(value, Usd.TimeCode(step))`.
- If `UsdGeom.Tokens.invisible` / `.inherited` don't exist, use string literals `"invisible"` / `"inherited"`.
- `Gf.Vec3d` vs `Gf.Vec3f` for translate — USD translate ops accept `Gf.Vec3d`.

- [ ] **Step 4: Run full test suite — all green**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] resequence-twin/viz"
PYTHONIOENCODING=utf-8 python -m pytest -v
```

Expected: ALL tests PASS (helpers + stage structure + body prims + display color + time samples + file I/O).

- [ ] **Step 5: Commit**

```bash
git add viz/tests/test_usd_exporter.py viz/usd_exporter.py
git commit -m "feat(viz-2a): USD stage tests + usd_exporter core (lane/body/animation)"
```

---

## Chunk 3: CLI (`export.py`) + README

### Task 4: Create `viz/export.py` CLI

**Files:**
- Create: `viz/export.py`

- [ ] **Step 1: Create `viz/export.py`**

```python
"""
export.py — CLI entry point for the resequence-twin viz USD exporter.

Usage::

    # Fetch from running control service:
    python -m viz.export --seed 42 --bodies 100 --policy dynamic --out pbs_twin.usda

    # Load from a saved JSON file:
    python -m viz.export --from-file trajectory.json --out pbs_twin.usda

Environment:
    RESEQUENCE_TWIN_CONTROL_URL — override default http://localhost:8081
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import httpx

from usd_exporter import build_stage


def _fetch_trajectory(
    seed: int,
    bodies: int,
    policy: str,
    base_url: str,
) -> dict:
    """Fetch a PBS trajectory from the control service."""
    url = f"{base_url}/api/trajectory"
    params = {"seed": seed, "bodies": bodies, "policy": policy}
    resp = httpx.get(url, params=params, timeout=30.0)
    resp.raise_for_status()
    return resp.json()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Export PBS trajectory to OpenUSD (.usda) 3D twin scene"
    )
    parser.add_argument("--seed",    type=int, default=42,      help="RNG seed (default 42)")
    parser.add_argument("--bodies",  type=int, default=100,     help="Number of bodies (default 100)")
    parser.add_argument("--policy",  type=str, default="dynamic", help="Sequencing policy (default dynamic)")
    parser.add_argument("--out",     type=str, default="pbs_twin.usda", help="Output .usda path")
    parser.add_argument("--from-file", type=str, default=None, dest="from_file",
                        help="Load trajectory from a JSON file instead of fetching from control service")
    args = parser.parse_args(argv)

    # Load trajectory
    if args.from_file:
        with open(args.from_file, encoding="utf-8") as f:
            trajectory = json.load(f)
        print(f"Loaded trajectory from {args.from_file}")
    else:
        base_url = os.environ.get("RESEQUENCE_TWIN_CONTROL_URL", "http://localhost:8081")
        print(f"Fetching trajectory from {base_url} (seed={args.seed}, bodies={args.bodies}, policy={args.policy})...")
        trajectory = _fetch_trajectory(args.seed, args.bodies, args.policy, base_url)

    n_bodies = len(trajectory.get("bodies", {}))
    n_frames = len(trajectory.get("frames", []))
    n_lanes  = len(trajectory.get("lanes",  []))

    print(f"Building USD stage: {n_bodies} bodies, {n_frames} frames, {n_lanes} lanes...")
    build_stage(trajectory, args.out)

    print(
        f"{n_bodies} bodies, {n_frames} frames, {n_lanes} lanes → {args.out}; "
        f"open in usdview/Blender/Omniverse"
    )
    print(f"  usdview: usdview {args.out}")
    print(f"  Blender: File > Import > Universal Scene Description (.usd)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Quick smoke test — export fixture to /tmp**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] resequence-twin/viz"
PYTHONIOENCODING=utf-8 python -c "
import json, tempfile, os
from tests.conftest import FIXTURE_TRAJECTORY
from usd_exporter import build_stage
with tempfile.NamedTemporaryFile(suffix='.usda', delete=False) as f:
    p = f.name
build_stage(FIXTURE_TRAJECTORY, p)
print('Written:', p, os.path.getsize(p), 'bytes')
from pxr import Usd
s = Usd.Stage.Open(p)
print('Re-opened OK, default prim:', s.GetDefaultPrim().GetPath())
os.unlink(p)
print('Smoke test PASSED')
"
```

Expected: `Smoke test PASSED`.

- [ ] **Step 3: Commit CLI**

```bash
git add viz/export.py
git commit -m "feat(viz-2b): CLI export.py (--from-file / fetch + build_stage)"
```

---

### Task 5: Write `viz/README.md`

**Files:**
- Create: `viz/README.md`

- [ ] **Step 1: Create `viz/README.md`**

```markdown
# resequence-twin viz — OpenUSD PBS Exporter

Exports a PBS (Paint Body Store) sequencing trajectory to an OpenUSD `.usda` 3D digital-twin scene.  Animated car bodies move through lane slots frame-by-frame, colour-coded by paint colour.

## What it does

- Reads the trajectory JSON produced by `GET /api/trajectory` (control service) or a saved `.json` file.
- Authors a `.usda` scene: ground plane, lane guide boxes, one `UsdGeom.Cube` per body (scaled to a car-ish 4×1.5×2 m box), `displayColor` primvar for paint colour, USD time-sampled translate + visibility ops encoding arrival → occupancy → release animation.
- Lightweight geometry + `displayColor` only — no MDL/material networks — so it opens in **usdview** (OpenGL/Storm, no RTX required) and Blender, as well as Omniverse.

## Install

```bash
cd viz
python -m pip install -e .[dev]
```

## Run

```bash
# From running control service (default http://localhost:8081):
python -m viz.export --seed 42 --bodies 100 --policy dynamic --out pbs_twin.usda

# From a saved JSON file (no network needed):
python -m viz.export --from-file trajectory.json --out pbs_twin.usda
```

## View the export

### usdview (recommended for RTX 4060 Ti 8 GB — no Omniverse needed)

```bash
usdview pbs_twin.usda
```

`usdview` ships with `usd-core` and uses OpenGL/Storm — works fine on 8 GB VRAM.  Press **Space** to play the animation.

### Blender

`File > Import > Universal Scene Description (.usd)` — built in since Blender 3.x.

### NVIDIA Omniverse

Open USD Composer / Kit and load `pbs_twin.usda`.  Full RTX render available if VRAM permits (10 GB+ recommended for Omniverse RTX path).

## Run tests (offline, no GPU, no network)

```bash
cd viz
PYTHONIOENCODING=utf-8 python -m pytest -q
```

## Honesty note

This is a **synthetic PoC**.  The trajectory data is procedurally generated (seed-reproducible), not from a real factory floor.  The USD export is the real artifact — it demonstrates the engineering pipeline (PBS simulation → 3D digital twin authoring) for demonstration purposes.  Full RTX render is run locally; the scene is intentionally viewable without Omniverse to stay within 8 GB VRAM constraints.
```

- [ ] **Step 2: Final full test run — confirm all green**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] resequence-twin/viz"
PYTHONIOENCODING=utf-8 python -m pytest -q
```

Expected: All tests pass (0 failures).

- [ ] **Step 3: Final commit**

```bash
git add viz/README.md
git commit -m "feat(viz-2): OpenUSD exporter (PBS trajectory → .usda 3D twin) — complete"
```

Optionally squash or use the single final commit message for the full feature:
```
feat(viz-2): OpenUSD exporter (PBS trajectory → .usda 3D twin)

- viz/pyproject.toml: resequence-twin-viz, usd-core>=23.11, httpx>=0.27
- viz/usd_exporter.py: build_stage_in_memory / build_stage, COLOR_MAP, sanitize_id, slot_position, assembly_position; USD time-sampled translate + visibility per body
- viz/export.py: CLI --seed/--bodies/--policy/--out/--from-file
- viz/README.md: usdview (8 GB-safe) / Blender / Omniverse usage + honesty note
- viz/tests/: 20+ offline headless pytest tests covering helpers, stage structure, displayColor, time samples, arrival/release animation, file I/O round-trip
```

---

## Final verification checklist

- [ ] `python -m pip install -e .[dev]` succeeds from `viz/`
- [ ] `PYTHONIOENCODING=utf-8 python -m pytest -q` → all green, no network/GPU required
- [ ] Smoke: `build_stage(FIXTURE_TRAJECTORY, tmp.usda)` → file opens with `Usd.Stage.Open`
- [ ] `python -m viz.export --from-file <fixture_json> --out out.usda` prints summary line
- [ ] Committed on `build/scaffold` with prefix `feat(viz-2):`
