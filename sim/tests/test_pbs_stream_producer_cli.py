"""
Tests for the pbs_stream_producer CLI layer (Task 2: produce + main).

Offline tests (default run):
  - All tests here run without a Kafka broker or kafka-python import being invoked.
  - The dry-run path avoids kafka entirely; lazy import is tested implicitly by
    the fact that these tests pass without a running broker.

Integration test (bottom of file):
  - Marked @pytest.mark.integration — skipped by default (see pyproject addopts).
  - Requires Docker stack with Kafka on localhost:19092.
"""
from __future__ import annotations

import io
import json
import sys

import pytest

import pbs_stream_producer as psp

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_VALID_TYPES = {"BodyArrived", "ReleaseTick", "LaneBlocked", "LaneUnblocked"}
_BODY_ARRIVED_KEYS = {"type", "eventId", "seq", "bodyId", "color", "model", "options", "dueDateSeq"}


def _parse_dry_run(buf: io.StringIO) -> list[str]:
    """Return non-empty lines from a dry-run StringIO buffer."""
    return [line for line in buf.getvalue().splitlines() if line.strip()]


# ---------------------------------------------------------------------------
# produce() — dry-run path
# ---------------------------------------------------------------------------

class TestProduceDryRun:
    """produce() with dry_run=True writes records to `out` without touching Kafka."""

    def test_returns_count(self):
        records = ["line1", "line2", "line3"]
        buf = io.StringIO()
        count = psp.produce(records, bootstrap_servers="unused", topic="unused",
                            rate=0.0, dry_run=True, out=buf)
        assert count == 3

    def test_each_record_on_own_line(self):
        records = ['{"a":1}', '{"b":2}']
        buf = io.StringIO()
        psp.produce(records, bootstrap_servers="unused", topic="unused",
                    rate=0.0, dry_run=True, out=buf)
        lines = _parse_dry_run(buf)
        assert lines == records

    def test_empty_records(self):
        buf = io.StringIO()
        count = psp.produce([], bootstrap_servers="unused", topic="unused",
                            rate=0.0, dry_run=True, out=buf)
        assert count == 0
        assert buf.getvalue() == ""

    def test_no_kafka_import_on_dry_run_path(self):
        """kafka module must NOT be imported when dry_run=True."""
        # Remove kafka from sys.modules if it was somehow already present so we
        # can detect a fresh import.
        kafka_was_present = "kafka" in sys.modules
        buf = io.StringIO()
        psp.produce(["record"], bootstrap_servers="unused", topic="unused",
                    rate=0.0, dry_run=True, out=buf)
        if not kafka_was_present:
            # If kafka was not pre-imported, it should still not be after a dry run.
            # (If it was already imported from another test, we can't assert this.)
            assert "kafka" not in sys.modules, "kafka was imported on the dry-run path"


# ---------------------------------------------------------------------------
# main() — dry-run via argv
# ---------------------------------------------------------------------------

class TestMainDryRun:
    """main() routes --dry-run output to stdout; summary goes to stderr."""

    def test_stdout_lines_count_matches_record_count(self, capsys):
        rc = psp.main(["--dry-run", "--seed", "7", "--bodies", "10"])
        assert rc == 0
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        # Build expected record count independently
        expected_records = psp.to_records(psp.build_event_stream(seed=7, bodies=10))
        assert len(lines) == len(expected_records)

    def test_every_stdout_line_is_valid_json(self, capsys):
        psp.main(["--dry-run", "--seed", "7", "--bodies", "10"])
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        for line in lines:
            parsed = json.loads(line)
            assert parsed["type"] in _VALID_TYPES, f"Unknown type: {parsed['type']}"

    def test_body_arrived_has_required_keys(self, capsys):
        psp.main(["--dry-run", "--seed", "7", "--bodies", "10"])
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        arrivals = [json.loads(l) for l in lines if json.loads(l)["type"] == "BodyArrived"]
        assert len(arrivals) == 10, "Expected 10 BodyArrived events"
        for arr in arrivals:
            assert _BODY_ARRIVED_KEYS.issubset(arr.keys()), f"Missing keys in BodyArrived: {arr}"

    def test_deterministic_for_same_seed(self, capsys):
        psp.main(["--dry-run", "--seed", "42", "--bodies", "15"])
        first_run = capsys.readouterr().out
        psp.main(["--dry-run", "--seed", "42", "--bodies", "15"])
        second_run = capsys.readouterr().out
        assert first_run == second_run, "Same seed must produce identical output"

    def test_different_seeds_differ(self, capsys):
        psp.main(["--dry-run", "--seed", "1", "--bodies", "10"])
        run1 = capsys.readouterr().out
        psp.main(["--dry-run", "--seed", "2", "--bodies", "10"])
        run2 = capsys.readouterr().out
        assert run1 != run2, "Different seeds should produce different output"

    def test_summary_goes_to_stderr_not_stdout(self, capsys):
        psp.main(["--dry-run", "--seed", "7", "--bodies", "10"])
        captured = capsys.readouterr()
        # stdout must be pure JSON lines only — every non-empty line must parse as JSON
        for line in captured.out.splitlines():
            if line.strip():
                json.loads(line)  # must not raise
        # stderr must have something (the summary)
        assert captured.err.strip(), "Summary must be written to stderr"

    def test_summary_mentions_seed_and_bodies(self, capsys):
        psp.main(["--dry-run", "--seed", "99", "--bodies", "5"])
        captured = capsys.readouterr()
        assert "99" in captured.err, "Seed should appear in summary"
        assert "5" in captured.err, "Bodies count should appear in summary"

    def test_summary_mentions_dry_run(self, capsys):
        psp.main(["--dry-run", "--seed", "7", "--bodies", "10"])
        captured = capsys.readouterr()
        assert "dry" in captured.err.lower(), "Summary should mention dry-run mode"


# ---------------------------------------------------------------------------
# main() — inject parsing
# ---------------------------------------------------------------------------

class TestMainInjectParsing:
    """--inject argument parsing: append style and comma-separated style."""

    def test_inject_malformed_produces_one_invalid_json_line(self, capsys):
        rc = psp.main(["--dry-run", "--seed", "7", "--bodies", "10", "--inject", "malformed"])
        assert rc == 0
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        invalid_lines = []
        for line in lines:
            try:
                json.loads(line)
            except json.JSONDecodeError:
                invalid_lines.append(line)
        assert len(invalid_lines) == 1, f"Expected exactly 1 malformed line, got {len(invalid_lines)}"

    def test_inject_lane_block_with_enough_bodies(self, capsys):
        rc = psp.main(["--dry-run", "--seed", "7", "--bodies", "10", "--inject", "lane-block"])
        assert rc == 0
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        types = [json.loads(l)["type"] for l in lines]
        assert "LaneBlocked" in types
        assert "LaneUnblocked" in types

    def test_inject_multiple_repeatable(self, capsys):
        """--inject can be given multiple times (action='append')."""
        rc = psp.main([
            "--dry-run", "--seed", "7", "--bodies", "10",
            "--inject", "malformed",
            "--inject", "lane-block",
        ])
        assert rc == 0
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        invalid = sum(1 for l in lines if _try_parse(l) is None)
        valid_types = [_try_parse(l)["type"] for l in lines if _try_parse(l) is not None]
        assert invalid == 1
        assert "LaneBlocked" in valid_types

    def test_inject_comma_separated(self, capsys):
        """--inject lane-block,malformed (comma-separated in one value) is accepted."""
        rc = psp.main([
            "--dry-run", "--seed", "7", "--bodies", "10",
            "--inject", "lane-block,malformed",
        ])
        assert rc == 0
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        invalid = sum(1 for l in lines if _try_parse(l) is None)
        valid_types = [_try_parse(l)["type"] for l in lines if _try_parse(l) is not None]
        assert invalid == 1
        assert "LaneBlocked" in valid_types

    def test_unknown_inject_errors(self, capsys):
        """Unknown inject value → non-zero exit, clear error message, no traceback."""
        rc = psp.main(["--dry-run", "--seed", "7", "--bodies", "10", "--inject", "not-a-valid-inject"])
        assert rc != 0
        captured = capsys.readouterr()
        # stdout must NOT contain a traceback
        assert "Traceback" not in captured.out
        assert "Traceback" not in captured.err
        assert "not-a-valid-inject" in captured.err

    def test_invalid_inject_error_message_is_helpful(self, capsys):
        rc = psp.main(["--dry-run", "--seed", "7", "--bodies", "10", "--inject", "bogus"])
        assert rc != 0
        captured = capsys.readouterr()
        combined = captured.out + captured.err
        assert "bogus" in combined or "unknown" in combined.lower() or "invalid" in combined.lower()


# ---------------------------------------------------------------------------
# main() — ValueError from unsatisfiable inject
# ---------------------------------------------------------------------------

class TestMainValueError:
    """build_event_stream() ValueError → clean CLI error (no traceback)."""

    def test_unsatisfiable_out_of_order_returns_nonzero(self, capsys):
        # out-of-order requires bodies >= 3; bodies=2 triggers ValueError
        rc = psp.main(["--dry-run", "--bodies", "2", "--inject", "out-of-order"])
        assert rc != 0

    def test_unsatisfiable_no_traceback_on_stdout(self, capsys):
        psp.main(["--dry-run", "--bodies", "2", "--inject", "out-of-order"])
        captured = capsys.readouterr()
        assert "Traceback" not in captured.out, "No Python traceback should appear on stdout"

    def test_unsatisfiable_no_traceback_on_stderr(self, capsys):
        psp.main(["--dry-run", "--bodies", "2", "--inject", "out-of-order"])
        captured = capsys.readouterr()
        assert "Traceback" not in captured.err, "No Python traceback should appear on stderr"

    def test_unsatisfiable_error_message_printed(self, capsys):
        rc = psp.main(["--dry-run", "--bodies", "2", "--inject", "out-of-order"])
        assert rc != 0
        captured = capsys.readouterr()
        combined = captured.out + captured.err
        assert "out-of-order" in combined or "error" in combined.lower()


# ---------------------------------------------------------------------------
# main() — defaults
# ---------------------------------------------------------------------------

class TestMainDefaults:
    """Verify argparse defaults without running Kafka."""

    def test_default_seed_produces_output(self, capsys):
        # seed=42 bodies=30 is the default; just check it runs and produces output
        rc = psp.main(["--dry-run"])
        assert rc == 0
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        assert len(lines) > 0

    def test_default_bodies_is_30(self, capsys):
        psp.main(["--dry-run"])
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        valid = [json.loads(l) for l in lines]
        arrivals = [e for e in valid if e["type"] == "BodyArrived"]
        assert len(arrivals) == 30, f"Default bodies should be 30, got {len(arrivals)}"

    def test_no_inject_default(self, capsys):
        psp.main(["--dry-run"])
        captured = capsys.readouterr()
        lines = [l for l in captured.out.splitlines() if l.strip()]
        # No malformed lines expected with no injects
        for line in lines:
            json.loads(line)  # all lines must be valid JSON


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _try_parse(line: str) -> dict | None:
    try:
        return json.loads(line)
    except json.JSONDecodeError:
        return None


# ---------------------------------------------------------------------------
# Integration test (requires broker — skipped by default)
# ---------------------------------------------------------------------------

@pytest.mark.integration
def test_produce_to_real_broker():
    """Integration test: produce a small stream to a real Kafka broker.

    PREREQUISITES:
      - Docker stack running with Kafka on localhost:19092
      - Topic 'pbs-events' exists (or auto-created by broker config)

    Run manually:
      cd sim && .venv/Scripts/python.exe -m pytest -m integration -v

    This test is SKIPPED in the default suite (pyproject addopts = "-m 'not integration'").
    """
    stream = psp.build_event_stream(seed=99, bodies=5)
    records = psp.to_records(stream)
    count = psp.produce(
        records,
        bootstrap_servers="localhost:19092",
        topic="pbs-events",
        rate=0.0,  # send as fast as possible
        dry_run=False,
    )
    assert count == len(records), f"Expected {len(records)} sent, got {count}"
    assert count > 0
