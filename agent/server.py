"""
resequence-twin agent — MCP read-only advisory server (R4c).

Exposes four tools over the MCP stdio transport:

  get_kpi(seed, bodies)
      → GET /api/kpi — static-vs-dynamic KPI benchmark.

  explain_release(seed, bodies, after_releases)
      → GET /api/explain/release — ReleaseExplanation for one release decision.

  predict_scramble(seed, bodies, after_releases)
      → GET /api/predict/scramble — ScrambleForecast risk assessment.

  search_docs(query, k)
      → local BM25 retrieval over ADRs + KPI glossary (no network, no API key).

Design notes
------------
- This server does NOT embed an LLM.  Claude (or another MCP host) is the
  consumer; it calls these tools and reasons over the results itself.
- RAG = deterministic, local retrieval via retrieval.py.  No embeddings
  service, no LlamaIndex, no Anthropic SDK required here.
- Each tool's logic is a plain Python function accepting an injectable
  httpx.Client so tests can exercise it offline without MCP transport.
- The control REST surface is READ-ONLY (GET only); we never issue POST/PUT.
"""

from __future__ import annotations

import os
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP

from retrieval import Chunk, search_docs as _search_docs

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# The control service listens on server.port=8081 (host 8080 is taken by other local
# services per docker-compose.yml). Override with env RESEQUENCE_TWIN_CONTROL_URL if needed.
_DEFAULT_BASE_URL = "http://localhost:8081"


def _base_url() -> str:
    return os.environ.get("RESEQUENCE_TWIN_CONTROL_URL", _DEFAULT_BASE_URL)


# ---------------------------------------------------------------------------
# Tool registry (single source of truth — MCP wiring and list_tools() both
# derive from this list so they stay in sync automatically)
# ---------------------------------------------------------------------------

REGISTERED_TOOLS: list[str] = [
    "get_kpi",
    "explain_release",
    "predict_scramble",
    "search_docs",
]


def list_tools() -> list[str]:
    """Return the names of all registered MCP tools (copy — mutation-safe)."""
    return list(REGISTERED_TOOLS)


# ---------------------------------------------------------------------------
# Internal REST helper
# ---------------------------------------------------------------------------


def _get(
    path: str,
    params: dict[str, Any],
    *,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """Issue GET <base_url><path>?<params> and return parsed JSON.

    On non-200 or connection failure returns ``{"error": "<description>"}``
    instead of raising so the MCP host receives a clean diagnostic.
    """
    owned = client is None
    c = client if client is not None else httpx.Client(base_url=_base_url())
    try:
        resp = c.get(path, params=params)
        if resp.status_code != 200:
            return {
                "error": (
                    f"Control service returned HTTP {resp.status_code} for {path}. "
                    f"Body: {resp.text[:200]}"
                )
            }
        return resp.json()
    except httpx.ConnectError as exc:
        return {
            "error": (
                f"Cannot reach control service at {_base_url()}{path}: {exc}. "
                "Is the Spring boot app running?"
            )
        }
    except (httpx.RequestError, ValueError) as exc:
        return {"error": f"HTTP request error for {path}: {exc}"}
    finally:
        if owned:
            c.close()


# ---------------------------------------------------------------------------
# Plain-Python tool implementations (injectable client for offline testing)
# ---------------------------------------------------------------------------


def get_kpi(
    seed: int = 42,
    bodies: int = 100,
    *,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """Fetch the static-vs-dynamic KPI benchmark from /api/kpi."""
    return _get("/api/kpi", {"seed": seed, "bodies": bodies}, client=client)


def explain_release(
    seed: int = 42,
    bodies: int = 100,
    after_releases: int = 15,
    *,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """Fetch a ReleaseExplanation for one decision from /api/explain/release."""
    return _get(
        "/api/explain/release",
        {"seed": seed, "bodies": bodies, "afterReleases": after_releases},
        client=client,
    )


def predict_scramble(
    seed: int = 42,
    bodies: int = 100,
    after_releases: int = 15,
    *,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """Fetch a ScrambleForecast risk assessment from /api/predict/scramble."""
    return _get(
        "/api/predict/scramble",
        {"seed": seed, "bodies": bodies, "afterReleases": after_releases},
        client=client,
    )


def search_docs(query: str, k: int = 3) -> list[Chunk]:
    """Return the top-k most relevant doc chunks for *query* (local, offline)."""
    return _search_docs(query, k=k)


# ---------------------------------------------------------------------------
# MCP wiring via FastMCP
# ---------------------------------------------------------------------------

mcp = FastMCP("resequence-twin-advisory")


@mcp.tool(name="get_kpi")
def mcp_get_kpi(seed: int = 42, bodies: int = 100) -> dict[str, Any]:
    """Fetch static-vs-dynamic KPI benchmark (colorChanges, batchLength, etc.)."""
    return get_kpi(seed=seed, bodies=bodies)


@mcp.tool(name="explain_release")
def mcp_explain_release(
    seed: int = 42,
    bodies: int = 100,
    after_releases: int = 15,
) -> dict[str, Any]:
    """Explain the scoring decision for a specific release step."""
    return explain_release(seed=seed, bodies=bodies, after_releases=after_releases)


@mcp.tool(name="predict_scramble")
def mcp_predict_scramble(
    seed: int = 42,
    bodies: int = 100,
    after_releases: int = 15,
) -> dict[str, Any]:
    """Predict the scramble risk (riskScore, level, stabilizationHint)."""
    return predict_scramble(seed=seed, bodies=bodies, after_releases=after_releases)


@mcp.tool(name="search_docs")
def mcp_search_docs(query: str, k: int = 3) -> list[Chunk]:
    """Search ADRs + KPI glossary locally (offline, no API key needed)."""
    return search_docs(query, k=k)


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
