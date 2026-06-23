"""
Offline TDD tests for the CPU schematic GIF renderer (schematic.py).

All tests are headless (matplotlib Agg backend, no display, no GPU, no network).
The fixture trajectory is defined in conftest.py (FIXTURE_TRAJECTORY).

Schematic is a 2D data-visualisation of the PBS trajectory — explicitly NOT
the 3D USD render. The .usda is the 3D twin for an RTX machine.
"""
from __future__ import annotations

import os

import matplotlib
matplotlib.use("Agg")  # must be set before any other matplotlib import

import pytest
from PIL import Image


# ---------------------------------------------------------------------------
# Import-level smoke
# ---------------------------------------------------------------------------

def test_schematic_module_importable():
    """schematic.py is importable without a display or GPU."""
    import viz.schematic  # noqa: F401


def test_render_frames_is_callable():
    """render_frames is a public callable with the expected signature."""
    from viz.schematic import render_frames
    import inspect
    sig = inspect.signature(render_frames)
    assert "trajectory" in sig.parameters
    assert "out_path" in sig.parameters


# ---------------------------------------------------------------------------
# GIF output — file creation
# ---------------------------------------------------------------------------

class TestRenderFramesGifCreation:
    """render_frames creates a non-empty, valid animated GIF file."""

    def test_gif_file_is_created(self, tmp_path, trajectory):
        from viz.schematic import render_frames
        out = tmp_path / "out.gif"
        render_frames(trajectory, str(out))
        assert out.exists(), "GIF file was not created"

    def test_gif_file_is_non_empty(self, tmp_path, trajectory):
        from viz.schematic import render_frames
        out = tmp_path / "out.gif"
        render_frames(trajectory, str(out))
        assert out.stat().st_size > 0, "GIF file must not be empty"

    def test_gif_is_valid_image_format(self, tmp_path, trajectory):
        from viz.schematic import render_frames
        out = tmp_path / "out.gif"
        render_frames(trajectory, str(out))
        img = Image.open(str(out))
        assert img.format == "GIF", f"Expected GIF format, got {img.format}"

    def test_gif_is_animated_multiple_frames(self, tmp_path, trajectory):
        """GIF must have more than 1 frame (animated)."""
        from viz.schematic import render_frames
        out = tmp_path / "out.gif"
        render_frames(trajectory, str(out))
        img = Image.open(str(out))
        assert getattr(img, "is_animated", False) or getattr(img, "n_frames", 1) > 1, \
            "GIF must be animated (n_frames > 1)"

    def test_gif_frame_count_matches_trajectory_frames(self, tmp_path, trajectory):
        """Number of GIF frames must equal len(trajectory['frames'])."""
        from viz.schematic import render_frames
        out = tmp_path / "out.gif"
        render_frames(trajectory, str(out))
        img = Image.open(str(out))
        n_frames = getattr(img, "n_frames", 1)
        expected = len(trajectory["frames"])
        assert n_frames == expected, \
            f"GIF has {n_frames} frames but trajectory has {expected} frames"


# ---------------------------------------------------------------------------
# Color mapping — body_facecolor helper
# ---------------------------------------------------------------------------

class TestBodyFacecolor:
    """body_facecolor maps color names to matplotlib-compatible RGB tuples."""

    def test_red_maps_to_reddish_rgb(self):
        from viz.schematic import body_facecolor
        r, g, b = body_facecolor("RED")
        assert r > 0.5, "RED r-channel should dominate"
        assert r > g and r > b

    def test_blue_maps_to_bluish_rgb(self):
        from viz.schematic import body_facecolor
        r, g, b = body_facecolor("BLUE")
        assert b > 0.5 and b > r and b > g

    def test_white_is_near_one(self):
        from viz.schematic import body_facecolor
        r, g, b = body_facecolor("WHITE")
        assert r > 0.8 and g > 0.8 and b > 0.8

    def test_black_is_near_zero(self):
        from viz.schematic import body_facecolor
        r, g, b = body_facecolor("BLACK")
        assert r < 0.2 and g < 0.2 and b < 0.2

    def test_silver_is_mid_grey(self):
        from viz.schematic import body_facecolor
        r, g, b = body_facecolor("SILVER")
        assert 0.3 < r < 0.9
        # silver channels should be close to each other (near-grey)
        assert abs(r - g) < 0.15 and abs(g - b) < 0.15

    def test_all_values_in_0_1_range(self):
        from viz.schematic import body_facecolor
        for color in ("RED", "BLUE", "WHITE", "BLACK", "SILVER"):
            for component in body_facecolor(color):
                assert 0.0 <= component <= 1.0, \
                    f"{color} component {component} out of [0, 1]"

    def test_white_and_silver_are_distinct(self):
        """WHITE and SILVER must map to distinguishable colors."""
        from viz.schematic import body_facecolor
        assert body_facecolor("WHITE") != body_facecolor("SILVER")

    def test_unknown_color_returns_grey_fallback(self):
        """Unknown color names should not raise — return a neutral fallback."""
        from viz.schematic import body_facecolor
        result = body_facecolor("CHARTREUSE")
        assert len(result) == 3
        assert all(0.0 <= c <= 1.0 for c in result)


# ---------------------------------------------------------------------------
# Outline requirement for light colors
# ---------------------------------------------------------------------------

class TestBodyNeedsOutline:
    """body_needs_outline returns True for colors that require a visible border."""

    def test_white_needs_outline(self):
        from viz.schematic import body_needs_outline
        assert body_needs_outline("WHITE") is True

    def test_silver_needs_outline(self):
        from viz.schematic import body_needs_outline
        assert body_needs_outline("SILVER") is True

    def test_red_does_not_need_outline(self):
        from viz.schematic import body_needs_outline
        assert body_needs_outline("RED") is False

    def test_blue_does_not_need_outline(self):
        from viz.schematic import body_needs_outline
        assert body_needs_outline("BLUE") is False

    def test_black_does_not_need_outline(self):
        from viz.schematic import body_needs_outline
        assert body_needs_outline("BLACK") is False


# ---------------------------------------------------------------------------
# PNG sequence output
# ---------------------------------------------------------------------------

class TestRenderPngSequence:
    """render_png_sequence writes one PNG per frame."""

    def test_png_files_created(self, tmp_path, trajectory):
        from viz.schematic import render_png_sequence
        render_png_sequence(trajectory, str(tmp_path))
        pngs = sorted(tmp_path.glob("frame_*.png"))
        assert len(pngs) > 0, "No PNG files were created"

    def test_png_count_matches_trajectory_frames(self, tmp_path, trajectory):
        from viz.schematic import render_png_sequence
        render_png_sequence(trajectory, str(tmp_path))
        pngs = sorted(tmp_path.glob("frame_*.png"))
        expected = len(trajectory["frames"])
        assert len(pngs) == expected, \
            f"Expected {expected} PNGs, got {len(pngs)}"

    def test_png_files_are_valid_images(self, tmp_path, trajectory):
        from viz.schematic import render_png_sequence
        render_png_sequence(trajectory, str(tmp_path))
        for png in sorted(tmp_path.glob("frame_*.png")):
            img = Image.open(str(png))
            assert img.format == "PNG", f"{png} is not a valid PNG"

    def test_png_files_are_non_empty(self, tmp_path, trajectory):
        from viz.schematic import render_png_sequence
        render_png_sequence(trajectory, str(tmp_path))
        for png in sorted(tmp_path.glob("frame_*.png")):
            assert png.stat().st_size > 0, f"{png} is empty"

    def test_png_filenames_are_zero_padded(self, tmp_path, trajectory):
        from viz.schematic import render_png_sequence
        render_png_sequence(trajectory, str(tmp_path))
        pngs = sorted(tmp_path.glob("frame_*.png"))
        # first file should be frame_0000.png
        assert pngs[0].name == "frame_0000.png", \
            f"First PNG should be frame_0000.png, got {pngs[0].name}"


# ---------------------------------------------------------------------------
# Determinism
# ---------------------------------------------------------------------------

class TestDeterminism:
    """render_frames is deterministic: same input → same output bytes."""

    def test_two_renders_produce_identical_files(self, tmp_path, trajectory):
        from viz.schematic import render_frames
        out_a = tmp_path / "a.gif"
        out_b = tmp_path / "b.gif"
        render_frames(trajectory, str(out_a))
        render_frames(trajectory, str(out_b))
        assert out_a.read_bytes() == out_b.read_bytes(), \
            "render_frames must be deterministic"


# ---------------------------------------------------------------------------
# Headless / no-display guard
# ---------------------------------------------------------------------------

def test_render_frames_does_not_need_display(tmp_path, trajectory):
    """render_frames works even if DISPLAY is unset (headless server)."""
    import os
    env_backup = os.environ.pop("DISPLAY", None)
    try:
        from viz.schematic import render_frames
        out = tmp_path / "headless.gif"
        render_frames(trajectory, str(out))  # must not raise
        assert out.exists()
    finally:
        if env_backup is not None:
            os.environ["DISPLAY"] = env_backup
