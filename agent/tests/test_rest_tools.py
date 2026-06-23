"""
R4c — tests for the three PBS REST tools.

All tests run OFFLINE: they inject a custom httpx transport
(httpx.MockTransport) so no real network is touched.

Tested behaviours
-----------------
- Tool issues an HTTP GET (not POST/PUT/etc.)
- Request targets the correct path with correct query params
- Parsed JSON body is returned on HTTP 200
- Clean error dict (no unhandled exception) on HTTP 4xx / 5xx
- Clean error dict (no unhandled exception) on connection error
"""

from __future__ import annotations

import json
from typing import Any

import httpx

import server

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

_BASE = "http://localhost:8081"  # control service server.port=8081


def _make_transport(
    status: int = 200,
    body: Any = None,
    *,
    raise_exc: Exception | None = None,
    recorded: list[httpx.Request] | None = None,
) -> httpx.MockTransport:
    """Return a transport that optionally records requests and returns a canned response."""

    def _handler(request: httpx.Request) -> httpx.Response:
        if recorded is not None:
            recorded.append(request)
        if raise_exc is not None:
            raise raise_exc
        return httpx.Response(
            status_code=status,
            headers={"Content-Type": "application/json"},
            content=json.dumps(body).encode(),
        )

    return httpx.MockTransport(_handler)


def _client(transport: httpx.MockTransport) -> httpx.Client:
    return httpx.Client(transport=transport, base_url=_BASE)


# ---------------------------------------------------------------------------
# Envelope payload stubs (synthetic=True as the real control service returns)
# ---------------------------------------------------------------------------

_KPI_STUB = {
    "synthetic": True,
    "disclaimer": "PoC synthetic data",
    "staticKpi": {"colorChanges": 50, "avgBatchLength": 2.0},
    "dynamicKpi": {"colorChanges": 25, "avgBatchLength": 3.8},
    "colorChangesDelta": -0.50,
    "avgBatchLengthDelta": 0.90,
    "dueDateDeviationDelta": -0.10,
    "throughputDelta": 0.02,
    "laneUtilizationDelta": 0.05,
}

_EXPLAIN_STUB = {
    "synthetic": True,
    "disclaimer": "PoC synthetic data",
    "chosenLaneId": "L1",
    "chosenBodyId": "B42",
    "reason": "HIGHEST_SCORE",
    "candidates": [],
    "context": {"seed": 42, "afterReleases": 15, "assemblyOutSize": 30},
}

_PREDICT_STUB = {
    "synthetic": True,
    "disclaimer": "PoC synthetic data",
    "riskScore": 0.35,
    "level": "MEDIUM",
    "factors": [],
    "topContributor": "laneImbalance",
    "stabilizationHint": "Release from the least-utilised lane.",
}


# ===========================================================================
# get_kpi
# ===========================================================================


def test_get_kpi_issues_get_request():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_KPI_STUB, recorded=recorded))
    server.get_kpi(client=client)
    assert len(recorded) == 1
    assert recorded[0].method == "GET"


def test_get_kpi_hits_correct_path():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_KPI_STUB, recorded=recorded))
    server.get_kpi(client=client)
    assert recorded[0].url.path == "/api/kpi"


def test_get_kpi_sends_default_params():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_KPI_STUB, recorded=recorded))
    server.get_kpi(client=client)
    params = dict(recorded[0].url.params)
    assert params.get("seed") == "42"
    assert params.get("bodies") == "100"


def test_get_kpi_sends_custom_params():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_KPI_STUB, recorded=recorded))
    server.get_kpi(seed=7, bodies=50, client=client)
    params = dict(recorded[0].url.params)
    assert params.get("seed") == "7"
    assert params.get("bodies") == "50"


def test_get_kpi_returns_parsed_json():
    client = _client(_make_transport(body=_KPI_STUB))
    result = server.get_kpi(client=client)
    assert result == _KPI_STUB


def test_get_kpi_non_200_returns_error_dict():
    client = _client(_make_transport(status=400, body={"error": "bad request"}))
    result = server.get_kpi(client=client)
    assert "error" in result
    assert "400" in result["error"]


def test_get_kpi_connection_error_returns_error_dict():
    client = _client(
        _make_transport(raise_exc=httpx.ConnectError("unreachable"))
    )
    result = server.get_kpi(client=client)
    assert "error" in result


# ===========================================================================
# explain_release
# ===========================================================================


def test_explain_release_issues_get_request():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_EXPLAIN_STUB, recorded=recorded))
    server.explain_release(client=client)
    assert recorded[0].method == "GET"


def test_explain_release_hits_correct_path():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_EXPLAIN_STUB, recorded=recorded))
    server.explain_release(client=client)
    assert recorded[0].url.path == "/api/explain/release"


def test_explain_release_sends_default_params():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_EXPLAIN_STUB, recorded=recorded))
    server.explain_release(client=client)
    params = dict(recorded[0].url.params)
    assert params.get("seed") == "42"
    assert params.get("bodies") == "100"
    assert params.get("afterReleases") == "15"


def test_explain_release_sends_custom_params():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_EXPLAIN_STUB, recorded=recorded))
    server.explain_release(seed=1, bodies=20, after_releases=5, client=client)
    params = dict(recorded[0].url.params)
    assert params.get("seed") == "1"
    assert params.get("bodies") == "20"
    assert params.get("afterReleases") == "5"


def test_explain_release_returns_parsed_json():
    client = _client(_make_transport(body=_EXPLAIN_STUB))
    result = server.explain_release(client=client)
    assert result == _EXPLAIN_STUB


def test_explain_release_non_200_returns_error_dict():
    client = _client(_make_transport(status=500, body={"error": "server error"}))
    result = server.explain_release(client=client)
    assert "error" in result
    assert "500" in result["error"]


def test_explain_release_connection_error_returns_error_dict():
    client = _client(
        _make_transport(raise_exc=httpx.ConnectError("unreachable"))
    )
    result = server.explain_release(client=client)
    assert "error" in result


# ===========================================================================
# predict_scramble
# ===========================================================================


def test_predict_scramble_issues_get_request():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_PREDICT_STUB, recorded=recorded))
    server.predict_scramble(client=client)
    assert recorded[0].method == "GET"


def test_predict_scramble_hits_correct_path():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_PREDICT_STUB, recorded=recorded))
    server.predict_scramble(client=client)
    assert recorded[0].url.path == "/api/predict/scramble"


def test_predict_scramble_sends_default_params():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_PREDICT_STUB, recorded=recorded))
    server.predict_scramble(client=client)
    params = dict(recorded[0].url.params)
    assert params.get("seed") == "42"
    assert params.get("bodies") == "100"
    assert params.get("afterReleases") == "15"


def test_predict_scramble_sends_custom_params():
    recorded: list[httpx.Request] = []
    client = _client(_make_transport(body=_PREDICT_STUB, recorded=recorded))
    server.predict_scramble(seed=99, bodies=200, after_releases=30, client=client)
    params = dict(recorded[0].url.params)
    assert params.get("seed") == "99"
    assert params.get("bodies") == "200"
    assert params.get("afterReleases") == "30"


def test_predict_scramble_returns_parsed_json():
    client = _client(_make_transport(body=_PREDICT_STUB))
    result = server.predict_scramble(client=client)
    assert result == _PREDICT_STUB


def test_predict_scramble_non_200_returns_error_dict():
    client = _client(_make_transport(status=400, body={"error": "bad params"}))
    result = server.predict_scramble(client=client)
    assert "error" in result
    assert "400" in result["error"]


def test_predict_scramble_connection_error_returns_error_dict():
    client = _client(
        _make_transport(raise_exc=httpx.ConnectError("unreachable"))
    )
    result = server.predict_scramble(client=client)
    assert "error" in result


# ===========================================================================
# _get() — malformed body (Fix 1)
# ===========================================================================


def _make_raw_transport(status: int, raw_body: bytes) -> httpx.MockTransport:
    """Return a transport that serves a raw (non-JSON) body on every request."""

    def _handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            status_code=status,
            headers={"Content-Type": "text/plain"},
            content=raw_body,
        )

    return httpx.MockTransport(_handler)


def test_non_json_200_body_returns_error_dict():
    """A 200 response with a non-JSON body must return an error dict, not raise."""
    client = httpx.Client(
        transport=_make_raw_transport(200, b"not json"),
        base_url=_BASE,
    )
    result = server.get_kpi(client=client)
    assert isinstance(result, dict), "Expected a dict, got something else"
    assert "error" in result, f"Expected 'error' key, got: {result}"
