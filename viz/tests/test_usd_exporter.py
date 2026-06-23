"""
Offline tests for build_stage_in_memory.

All tests are headless (no GPU, no network) — pxr authoring/reading is CPU-only.
The fixture trajectory is defined in conftest.py.
"""
import os
import tempfile

import pytest
from pxr import Gf, Sdf, Usd, UsdGeom

from viz.usd_exporter import (
    COLOR_MAP,
    build_stage,
    build_stage_in_memory,
    sanitize_id,
    slot_position,
    assembly_position,
)


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
        prim = self._stage.GetPrimAtPath("/World/SunLight")
        assert prim.IsValid(), "/World/SunLight prim must exist"

    def test_ground_exists(self):
        ground = self._stage.GetPrimAtPath("/World/Ground")
        assert ground.IsValid(), "/World/Ground prim must exist"

    def test_start_time_code_is_zero(self):
        assert self._stage.GetStartTimeCode() == 0.0

    def test_end_time_code_matches_last_frame(self):
        # fixture has 8 frames, last step = 7
        assert self._stage.GetEndTimeCode() == 7.0

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
        attr = prim.GetAttribute("xformOp:translate")
        return attr

    def _get_vis_attr(self, body_id: str):
        prim = self._stage.GetPrimAtPath(f"/World/Bodies/{sanitize_id(body_id)}")
        cube = UsdGeom.Cube(prim)
        return cube.GetVisibilityAttr()

    def test_translate_attr_has_time_samples(self):
        attr = self._get_translate_attr("BODY-00000")
        assert attr.IsValid()
        times = attr.GetTimeSamples()
        assert len(times) > 0, "Translate attr must have time samples"

    def test_time_samples_span_all_frames(self):
        attr = self._get_translate_attr("BODY-00000")
        times = attr.GetTimeSamples()
        expected_steps = set(range(8))  # steps 0..7
        actual_steps = {int(t) for t in times}
        assert expected_steps == actual_steps

    def test_body_invisible_before_arrival(self):
        # BODY-00003 arrives at step 3; at steps 0,1,2 → invisible
        vis = self._get_vis_attr("BODY-00003")
        for step in (0, 1, 2):
            val = vis.Get(Usd.TimeCode(float(step)))
            assert val == "invisible", \
                f"BODY-00003 should be invisible at step {step}, got {val!r}"

    def test_body_visible_after_arrival(self):
        # BODY-00003 arrives at step 3 → visible from step 3 onward
        vis = self._get_vis_attr("BODY-00003")
        for step in (3, 4, 5, 6, 7):
            val = vis.Get(Usd.TimeCode(float(step)))
            assert val == "inherited", \
                f"BODY-00003 should be visible at step {step}, got {val!r}"

    def test_body_at_lane_position_when_in_lane(self):
        # BODY-00000 is in L1[0] at step 0 → lane_index=0, slot_index=0 → (0,0,0)
        attr = self._get_translate_attr("BODY-00000")
        pos = attr.Get(Usd.TimeCode(0.0))
        expected = slot_position(0, 0)
        assert pos is not None
        assert abs(pos[0] - expected[0]) < 1e-4, f"X: {pos[0]} != {expected[0]}"
        assert abs(pos[2] - expected[2]) < 1e-4, f"Z: {pos[2]} != {expected[2]}"

    def test_released_body_moves_to_assembly_rail(self):
        # BODY-00000 released at step 3 (first released → index 0) → X=60
        attr = self._get_translate_attr("BODY-00000")
        pos_at_3 = attr.Get(Usd.TimeCode(3.0))
        assert pos_at_3 is not None
        assert abs(pos_at_3[0] - 60.0) < 1e-4, \
            f"Released body X should be 60.0, got {pos_at_3[0]}"

    def test_body_not_yet_arrived_is_offstage(self):
        # BODY-00003 not arrived at step 0 → Y=-100
        attr = self._get_translate_attr("BODY-00003")
        pos = attr.Get(Usd.TimeCode(0.0))
        assert pos is not None
        assert pos[1] == -100.0, f"Pre-arrival Y should be -100, got {pos[1]}"


# ---------------------------------------------------------------------------
# build_stage — file round-trip
# ---------------------------------------------------------------------------

class TestBuildStageFile:
    """build_stage writes a .usda that can be re-opened with Usd.Stage.Open."""

    def test_writes_usda_and_reopens(self, trajectory):
        fd, out_path = tempfile.mkstemp(suffix=".usda")
        os.close(fd)
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
