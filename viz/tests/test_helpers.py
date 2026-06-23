"""Tests for sanitize_id helper and COLOR_MAP constant."""
import pytest
from viz.usd_exporter import sanitize_id, COLOR_MAP


class TestSanitizeId:
    """sanitize_id converts body ids to valid USD prim-name strings."""

    def test_hyphen_replaced_by_underscore(self):
        assert sanitize_id("BODY-00000") == "Body_00000"

    def test_already_clean_id(self):
        assert sanitize_id("BODY_00001") == "Body_00001"

    def test_prefix_capitalised(self):
        result = sanitize_id("BODY-12345")
        assert result.startswith("Body_")

    def test_numeric_suffix_preserved(self):
        assert sanitize_id("BODY-99999") == "Body_99999"

    def test_multiple_hyphens(self):
        result = sanitize_id("ABC-DEF-001")
        assert "-" not in result


class TestColorMap:
    """COLOR_MAP maps all 5 body colours to distinct Gf.Vec3f tuples."""

    def test_all_five_colours_present(self):
        expected = {"RED", "BLUE", "WHITE", "BLACK", "SILVER"}
        assert set(COLOR_MAP.keys()) == expected

    def test_all_colours_are_distinct(self):
        values = list(COLOR_MAP.values())
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
