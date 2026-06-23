"""
usd_exporter.py — OpenUSD scene authoring for PBS trajectory data.

Exports a PBS trajectory dict (from GET /api/trajectory) to a .usda file
with one prim per body, animated via USD time samples (arrival/occupancy/release).

Scene layout (Y-up, right-hand):
  - Ground plane at Y=0, XZ-spread
  - Lanes along +Z axis, slots along +X within each lane
  - Assembly output rail at X=60 (beyond last lane slot)
  - Camera positioned above and behind to see whole PBS

Geometry: UsdGeom.Cube scaled to car-ish box (4x1.5x2 metres).
Colour: displayColor primvar only — no MDL/material networks (usdview/Blender-safe).
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

#: Car box scale for UsdGeom.Cube (unit cube scaled to 4x1.5x2 metres)
CAR_SCALE: tuple[float, float, float] = (2.0, 0.75, 1.0)

#: USD time codes per second
TIME_CODES_PER_SECOND: float = 6.0

#: Body colour -> linear-sRGB RGB triple
COLOR_MAP: dict[str, Gf.Vec3f] = {
    "RED":    Gf.Vec3f(0.85, 0.10, 0.10),
    "BLUE":   Gf.Vec3f(0.10, 0.25, 0.85),
    "WHITE":  Gf.Vec3f(0.92, 0.92, 0.92),
    "BLACK":  Gf.Vec3f(0.08, 0.08, 0.08),
    "SILVER": Gf.Vec3f(0.60, 0.62, 0.65),
}

#: Offstage position for bodies not yet arrived
_OFFSTAGE: Gf.Vec3d = Gf.Vec3d(0.0, -100.0, 0.0)

#: Assembly rail X position (released bodies park here)
_ASSEMBLY_RAIL_X: float = 60.0

# Visibility token strings (use literals for cross-version compatibility)
_VIS_INVISIBLE = "invisible"
_VIS_INHERITED = "inherited"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def sanitize_id(body_id: str) -> str:
    """Convert a body id to a valid USD prim name.

    Replaces hyphens with underscores and capitalises the first letter.

    Examples::

        sanitize_id("BODY-00000") -> "Body_00000"
        sanitize_id("BODY_00001") -> "Body_00001"
    """
    name = body_id.replace("-", "_")
    if not name:
        return name
    # Title-case the prefix (segment before first underscore), preserve the rest.
    underscore_pos = name.find("_")
    if underscore_pos == -1:
        return name[0].upper() + name[1:].lower()
    prefix = name[:underscore_pos]
    suffix = name[underscore_pos:]
    return prefix[0].upper() + prefix[1:].lower() + suffix


def slot_position(lane_index: int, slot_index: int) -> Gf.Vec3d:
    """Return the world-space XYZ for a lane/slot cell.

    Lanes are spaced along +Z; slots along +X within each lane.
    """
    x = slot_index * LANE_SLOT_SPACING
    z = lane_index * LANE_ROW_SPACING
    return Gf.Vec3d(x, 0.0, z)


def assembly_position(release_order_index: int, num_lanes: int) -> Gf.Vec3d:
    """Return the world-space XYZ on the assembly-output rail.

    Released bodies park at X=60, spaced along Z by release order.
    """
    z = release_order_index * LANE_ROW_SPACING
    return Gf.Vec3d(_ASSEMBLY_RAIL_X, 0.0, z)


# ---------------------------------------------------------------------------
# Core authoring
# ---------------------------------------------------------------------------

def build_stage_in_memory(trajectory: dict[str, Any]) -> Usd.Stage:
    """Author a USD stage from a PBS trajectory dict, in memory (no disk I/O).

    Args:
        trajectory: PBS trajectory dict (from GET /api/trajectory).

    Returns:
        An open Usd.Stage with the full scene authored.
    """
    stage = Usd.Stage.CreateInMemory()
    _populate_stage(stage, trajectory)
    return stage


def build_stage(trajectory: dict[str, Any], out_path: str) -> None:
    """Author a USD stage and save to out_path as ASCII USDA.

    Uses an in-memory stage + explicit Export so there is a single write
    to *out_path* (avoids the double-write that CreateNew + Export produces).

    Args:
        trajectory: PBS trajectory dict.
        out_path: Destination .usda file path.
    """
    stage = Usd.Stage.CreateInMemory()
    _populate_stage(stage, trajectory)
    stage.GetRootLayer().Export(out_path)


# ---------------------------------------------------------------------------
# Internal scene builder
# ---------------------------------------------------------------------------

def _populate_stage(stage: Usd.Stage, trajectory: dict[str, Any]) -> None:
    """Write all prims, attributes, and time samples into stage."""
    lanes_spec = trajectory["lanes"]
    bodies_spec = trajectory["bodies"]
    frames = trajectory["frames"]

    num_lanes = len(lanes_spec)
    max_capacity = max(lane["capacity"] for lane in lanes_spec)
    last_step = frames[-1]["step"] if frames else 0

    # Stage metadata
    stage.SetStartTimeCode(0.0)
    stage.SetEndTimeCode(float(last_step))
    stage.SetTimeCodesPerSecond(TIME_CODES_PER_SECOND)
    UsdGeom.SetStageUpAxis(stage, UsdGeom.Tokens.y)

    # /World root
    world = UsdGeom.Xform.Define(stage, "/World")
    stage.SetDefaultPrim(world.GetPrim())

    # Ground plane (thin mesh box)
    _build_ground(stage, max_capacity, num_lanes)

    # Lane base guide boxes
    _build_lanes(stage, lanes_spec)

    # Light
    light = UsdLux.DistantLight.Define(stage, "/World/SunLight")
    light.GetIntensityAttr().Set(3000.0)
    UsdGeom.XformCommonAPI(light.GetPrim()).SetRotate(Gf.Vec3f(-60.0, 0.0, 0.0))

    # Camera
    camera = UsdGeom.Camera.Define(stage, "/World/Camera")
    cam_x = (max_capacity * LANE_SLOT_SPACING) / 2.0
    cam_y = num_lanes * LANE_ROW_SPACING * 2.5
    cam_z = num_lanes * LANE_ROW_SPACING * 2.0
    cam_xform = UsdGeom.XformCommonAPI(camera.GetPrim())
    cam_xform.SetTranslate(Gf.Vec3d(cam_x, cam_y, cam_z))
    cam_xform.SetRotate(Gf.Vec3f(-40.0, 0.0, 0.0))
    camera.GetFocalLengthAttr().Set(35.0)

    # Pre-compute arrival/release info from frames
    arrival_step: dict[str, int] = {}
    release_step: dict[str, int] = {}
    release_order: list[str] = []

    for frame in frames:
        step = frame["step"]
        for body_id in (frame.get("arrived") or []):
            if body_id not in arrival_step:
                arrival_step[body_id] = step
        released = frame.get("released")
        if released:
            release_step[released] = step
            if released not in release_order:
                release_order.append(released)

    # Body prims
    UsdGeom.Xform.Define(stage, "/World/Bodies")

    for body_id, body_data in bodies_spec.items():
        _build_body(
            stage=stage,
            body_id=body_id,
            body_data=body_data,
            frames=frames,
            lanes_spec=lanes_spec,
            arrival_step=arrival_step,
            release_order=release_order,
            num_lanes=num_lanes,
        )


def _build_ground(stage: Usd.Stage, max_capacity: int, num_lanes: int) -> None:
    ground = UsdGeom.Mesh.Define(stage, "/World/Ground")
    width = (max_capacity + 2) * LANE_SLOT_SPACING
    depth = (num_lanes + 1) * LANE_ROW_SPACING
    hw, hd, hy = width / 2.0, depth / 2.0, 0.1
    ground.GetPointsAttr().Set([
        Gf.Vec3f(-hw, -hy, -hd), Gf.Vec3f(hw, -hy, -hd),
        Gf.Vec3f(hw, -hy,  hd), Gf.Vec3f(-hw, -hy,  hd),
        Gf.Vec3f(-hw,  hy, -hd), Gf.Vec3f(hw,  hy, -hd),
        Gf.Vec3f(hw,  hy,  hd), Gf.Vec3f(-hw,  hy,  hd),
    ])
    ground.GetFaceVertexCountsAttr().Set([4, 4, 4, 4, 4, 4])
    ground.GetFaceVertexIndicesAttr().Set([
        0, 1, 2, 3, 4, 7, 6, 5,
        0, 4, 5, 1, 1, 5, 6, 2,
        2, 6, 7, 3, 3, 7, 4, 0,
    ])
    ground.GetDisplayColorAttr().Set([Gf.Vec3f(0.3, 0.3, 0.3)])


def _build_lanes(stage: Usd.Stage, lanes_spec: list[dict]) -> None:
    UsdGeom.Xform.Define(stage, "/World/Lanes")
    for l_idx, lane in enumerate(lanes_spec):
        lane_id = lane["id"].replace("-", "_")
        lane_mesh = UsdGeom.Mesh.Define(stage, f"/World/Lanes/{lane_id}")
        cap = lane["capacity"]
        lw = cap * LANE_SLOT_SPACING
        lz = LANE_ROW_SPACING * 0.45
        lhw, lhz, lhy = lw / 2.0, lz / 2.0, 0.05
        ox = lw / 2.0 - LANE_SLOT_SPACING / 2.0
        oz = float(l_idx) * LANE_ROW_SPACING
        lane_mesh.GetPointsAttr().Set([
            Gf.Vec3f(ox - lhw, -lhy, oz - lhz), Gf.Vec3f(ox + lhw, -lhy, oz - lhz),
            Gf.Vec3f(ox + lhw, -lhy, oz + lhz), Gf.Vec3f(ox - lhw, -lhy, oz + lhz),
            Gf.Vec3f(ox - lhw,  lhy, oz - lhz), Gf.Vec3f(ox + lhw,  lhy, oz - lhz),
            Gf.Vec3f(ox + lhw,  lhy, oz + lhz), Gf.Vec3f(ox - lhw,  lhy, oz + lhz),
        ])
        lane_mesh.GetFaceVertexCountsAttr().Set([4, 4, 4, 4, 4, 4])
        lane_mesh.GetFaceVertexIndicesAttr().Set([
            0, 1, 2, 3, 4, 7, 6, 5,
            0, 4, 5, 1, 1, 5, 6, 2,
            2, 6, 7, 3, 3, 7, 4, 0,
        ])
        lane_mesh.GetDisplayColorAttr().Set([Gf.Vec3f(0.45, 0.45, 0.50)])


def _build_body(
    stage: Usd.Stage,
    body_id: str,
    body_data: dict,
    frames: list[dict],
    lanes_spec: list[dict],
    arrival_step: dict[str, int],
    release_order: list[str],
    num_lanes: int,
) -> None:
    prim_name = sanitize_id(body_id)
    prim_path = f"/World/Bodies/{prim_name}"
    cube = UsdGeom.Cube.Define(stage, prim_path)

    # Scale (constant, not time-sampled) — raw op so AddTranslateOp coexists cleanly
    cube.AddScaleOp().Set(Gf.Vec3f(*CAR_SCALE))

    # displayColor
    colour_name = body_data.get("color", "WHITE")
    rgb = COLOR_MAP.get(colour_name, COLOR_MAP["WHITE"])
    cube.GetDisplayColorAttr().Set([rgb])

    vis_attr = cube.GetVisibilityAttr()
    translate_op = cube.AddTranslateOp()

    arr_step = arrival_step.get(body_id)
    rel_idx = release_order.index(body_id) if body_id in release_order else None

    for frame in frames:
        step = frame["step"]
        tc = Usd.TimeCode(float(step))

        if arr_step is None or step < arr_step:
            vis_attr.Set(_VIS_INVISIBLE, tc)
            translate_op.Set(_OFFSTAGE, tc)
            continue

        in_lane = False
        for l_idx, lane in enumerate(lanes_spec):
            lane_id = lane["id"]
            occupants = frame["lanes"].get(lane_id, [])
            if body_id in occupants:
                slot_idx = occupants.index(body_id)
                pos = slot_position(l_idx, slot_idx)
                vis_attr.Set(_VIS_INHERITED, tc)
                translate_op.Set(pos, tc)
                in_lane = True
                break

        if not in_lane:
            if rel_idx is not None:
                pos = assembly_position(rel_idx, num_lanes)
            else:
                pos = _OFFSTAGE
            vis_attr.Set(_VIS_INHERITED, tc)
            translate_op.Set(pos, tc)
