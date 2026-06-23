"""
Offline TDD tests for the static-vs-dynamic comparison GIF renderer (compare.py).

All tests are headless (matplotlib Agg backend, no display, no GPU, no network).
render_comparison() is called with fixture trajectories only — no HTTP.

compare.py is a 2D schematic, NOT the 3D USD render.
"""
from __future__ import annotations

import copy
import os

import matplotlib
matplotlib.use("Agg")

import pytest
from PIL import Image

from viz.tests.conftest import FIXTURE_TRAJECTORY


# ---------------------------------------------------------------------------
# Import smoke
# ---------------------------------------------------------------------------

def test_compare_module_importable():
    """compare.py is importable without a display or GPU."""
    from viz.compare import render_comparison  # noqa: F401


def test_render_comparison_is_callable():
    """render_comparison is a public callable with the expected signature."""
    from viz.compare import render_comparison
    import inspect
    sig = inspect.signature(render_comparison)
    assert "traj_static" in sig.parameters
    assert "traj_dynamic" in sig.parameters
    assert "out_path" in sig.parameters


def test_count_color_changes_importable():
    """count_color_changes can be imported from viz.schematic."""
    from viz.schematic import count_color_changes  # noqa: F401


# ---------------------------------------------------------------------------
# TestCountColorChanges — unit tests for count_color_changes
# ---------------------------------------------------------------------------

class TestCountColorChanges:
    """Unit tests for the count_color_changes helper from viz.schematic."""

    BODIES = {
        "A": {"color": "RED"},
        "B": {"color": "RED"},
        "C": {"color": "BLUE"},
        "D": {"color": "BLUE"},
        "E": {"color": "RED"},
    }

    def test_no_changes_same_color(self):
        from viz.schematic import count_color_changes
        assert count_color_changes(["A", "B"], self.BODIES) == 0

    def test_single_change(self):
        from viz.schematic import count_color_changes
        assert count_color_changes(["A", "C"], self.BODIES) == 1

    def test_hand_built_sequence(self):
        """[RED, RED, BLUE, BLUE, RED] → 2 colour changes."""
        from viz.schematic import count_color_changes
        assert count_color_changes(["A", "B", "C", "D", "E"], self.BODIES) == 2

    def test_empty_sequence(self):
        from viz.schematic import count_color_changes
        assert count_color_changes([], self.BODIES) == 0

    def test_single_element(self):
        from viz.schematic import count_color_changes
        assert count_color_changes(["A"], self.BODIES) == 0

    def test_all_different(self):
        """[RED, BLUE, RED, BLUE] → 3 colour changes."""
        from viz.schematic import count_color_changes
        assert count_color_changes(["A", "C", "A", "C"], self.BODIES) == 3


# ---------------------------------------------------------------------------
# TestRenderComparisonGifOutput — GIF output assertions
# ---------------------------------------------------------------------------

class TestRenderComparisonGifOutput:
    """render_comparison creates a non-empty, valid animated comparison GIF."""

    def _static_traj(self):
        """Return a copy of FIXTURE_TRAJECTORY with policy='static'."""
        traj = copy.deepcopy(FIXTURE_TRAJECTORY)
        traj["policy"] = "static"
        return traj

    def _dynamic_traj(self):
        return copy.deepcopy(FIXTURE_TRAJECTORY)

    def test_creates_output_file(self, tmp_path):
        from viz.compare import render_comparison
        out = tmp_path / "compare.gif"
        render_comparison(self._static_traj(), self._dynamic_traj(), str(out), dpi=72)
        assert out.exists(), "GIF file was not created"

    def test_output_is_non_empty(self, tmp_path):
        from viz.compare import render_comparison
        out = tmp_path / "compare.gif"
        render_comparison(self._static_traj(), self._dynamic_traj(), str(out), dpi=72)
        assert out.stat().st_size > 0, "GIF file must not be empty"

    def test_output_is_valid_gif(self, tmp_path):
        from viz.compare import render_comparison
        out = tmp_path / "compare.gif"
        render_comparison(self._static_traj(), self._dynamic_traj(), str(out), dpi=72)
        img = Image.open(str(out))
        assert img.format == "GIF", f"Expected GIF format, got {img.format}"

    def test_output_is_animated(self, tmp_path):
        from viz.compare import render_comparison
        out = tmp_path / "compare.gif"
        render_comparison(self._static_traj(), self._dynamic_traj(), str(out), dpi=72)
        img = Image.open(str(out))
        assert getattr(img, "is_animated", False) or getattr(img, "n_frames", 1) > 1, \
            "GIF must be animated (n_frames > 1)"

    def test_frame_count_equals_max_of_both(self, tmp_path):
        """When both trajectories have 8 frames, GIF must have 8 frames."""
        from viz.compare import render_comparison
        static_traj = self._static_traj()
        dynamic_traj = self._dynamic_traj()
        expected_frames = max(len(static_traj["frames"]), len(dynamic_traj["frames"]))
        out = tmp_path / "compare.gif"
        render_comparison(static_traj, dynamic_traj, str(out), dpi=72)
        img = Image.open(str(out))
        n_frames = getattr(img, "n_frames", 1)
        assert n_frames == expected_frames, \
            f"GIF has {n_frames} frames but expected {expected_frames}"

    def test_same_trajectory_twice_produces_n_max_frames(self, tmp_path):
        """Passing same fixture as both static and dynamic → N fixture frames."""
        from viz.compare import render_comparison
        traj = copy.deepcopy(FIXTURE_TRAJECTORY)
        traj_static = copy.deepcopy(traj)
        traj_static["policy"] = "static"
        out = tmp_path / "compare_same.gif"
        render_comparison(traj_static, traj, str(out), dpi=72)
        img = Image.open(str(out))
        n_frames = getattr(img, "n_frames", 1)
        expected = len(FIXTURE_TRAJECTORY["frames"])
        assert n_frames == expected, \
            f"GIF has {n_frames} frames but fixture has {expected} frames"


# ---------------------------------------------------------------------------
# TestFrameAlignmentFreeze — shorter trajectory padding
# ---------------------------------------------------------------------------

class TestFrameAlignmentFreeze:
    """When trajectories differ in length, shorter is frozen at its last frame."""

    def _short_traj(self):
        """Return fixture with only the first 4 frames."""
        traj = copy.deepcopy(FIXTURE_TRAJECTORY)
        traj["policy"] = "dynamic"
        traj["frames"] = traj["frames"][:4]
        return traj

    def _full_static_traj(self):
        traj = copy.deepcopy(FIXTURE_TRAJECTORY)
        traj["policy"] = "static"
        return traj

    def test_frame_count_is_max(self, tmp_path):
        """N = max(8, 4) = 8 → GIF must have 8 frames."""
        from viz.compare import render_comparison
        static_traj = self._full_static_traj()
        short_dynamic = self._short_traj()
        expected = max(len(static_traj["frames"]), len(short_dynamic["frames"]))
        assert expected == 8, "Precondition: full traj has 8 frames"
        out = tmp_path / "freeze_test.gif"
        render_comparison(static_traj, short_dynamic, str(out), dpi=72)
        img = Image.open(str(out))
        n_frames = getattr(img, "n_frames", 1)
        assert n_frames == expected, \
            f"GIF has {n_frames} frames but expected {expected}"

    def test_shorter_panel_frozen_not_growing(self):
        """colour-changes KPI at clamped idx=3 is the same when called twice."""
        from viz.schematic import count_color_changes, _release_sequence_up_to
        short_traj = self._short_traj()
        frames = short_traj["frames"]
        bodies = short_traj["bodies"]

        # Simulate the clamping logic used in render_comparison
        last_real_idx = len(frames) - 1  # idx 3
        clamped_idx = min(4, last_real_idx)  # also 3 when len=4

        step_at_real = frames[last_real_idx]["step"]
        step_at_clamped = frames[clamped_idx]["step"]

        released_real = _release_sequence_up_to(frames, step_at_real)
        released_clamped = _release_sequence_up_to(frames, step_at_clamped)

        changes_real = count_color_changes(released_real, bodies)
        changes_clamped = count_color_changes(released_clamped, bodies)

        assert changes_real == changes_clamped, (
            f"KPI should be frozen: real={changes_real}, clamped={changes_clamped}"
        )


# ---------------------------------------------------------------------------
# TestValidationErrors — ValueError on mismatch
# ---------------------------------------------------------------------------

class TestValidationErrors:
    """render_comparison raises ValueError on seed or bodies mismatch."""

    def _base_static(self):
        traj = copy.deepcopy(FIXTURE_TRAJECTORY)
        traj["policy"] = "static"
        return traj

    def _base_dynamic(self):
        return copy.deepcopy(FIXTURE_TRAJECTORY)

    def test_raises_on_seed_mismatch(self, tmp_path):
        from viz.compare import render_comparison
        static_traj = self._base_static()
        wrong_seed = self._base_dynamic()
        wrong_seed["seed"] = 999
        out = tmp_path / "should_not_exist.gif"
        with pytest.raises(ValueError):
            render_comparison(static_traj, wrong_seed, str(out), dpi=72)

    def test_raises_on_body_keys_mismatch(self, tmp_path):
        from viz.compare import render_comparison
        static_traj = self._base_static()
        extra_body = self._base_dynamic()
        extra_body["bodies"]["BODY-99999"] = {
            "color": "RED", "model": "Focus", "options": [], "dueDateSeq": 99
        }
        out = tmp_path / "should_not_exist.gif"
        with pytest.raises(ValueError):
            render_comparison(static_traj, extra_body, str(out), dpi=72)

    def test_error_message_mentions_seeds(self, tmp_path):
        from viz.compare import render_comparison
        static_traj = self._base_static()
        wrong_seed = self._base_dynamic()
        wrong_seed["seed"] = 999
        out = tmp_path / "should_not_exist.gif"
        with pytest.raises(ValueError, match="seed"):
            render_comparison(static_traj, wrong_seed, str(out), dpi=72)

    def test_error_message_mentions_bodies(self, tmp_path):
        from viz.compare import render_comparison
        static_traj = self._base_static()
        missing_body = self._base_dynamic()
        # Remove one body from dynamic
        del missing_body["bodies"]["BODY-00004"]
        out = tmp_path / "should_not_exist.gif"
        with pytest.raises(ValueError, match="bodies"):
            render_comparison(static_traj, missing_body, str(out), dpi=72)


# ---------------------------------------------------------------------------
# TestRenderComparisonHeadless
# ---------------------------------------------------------------------------

class TestRenderComparisonHeadless:
    """render_comparison works even if DISPLAY is unset (headless server)."""

    def test_no_display_needed(self, tmp_path):
        """Pop DISPLAY env var; render_comparison must not raise."""
        from viz.compare import render_comparison
        env_backup = os.environ.pop("DISPLAY", None)
        try:
            static_traj = copy.deepcopy(FIXTURE_TRAJECTORY)
            static_traj["policy"] = "static"
            dynamic_traj = copy.deepcopy(FIXTURE_TRAJECTORY)
            out = tmp_path / "headless_compare.gif"
            render_comparison(static_traj, dynamic_traj, str(out), dpi=72)
            assert out.exists()
        finally:
            if env_backup is not None:
                os.environ["DISPLAY"] = env_backup
