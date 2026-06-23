"""
R4c tests — updated from OHT skeleton to PBS tool registry.

Verifies that:
  - REGISTERED_TOOLS lists exactly the four PBS tool names (3 REST + 1 doc-search)
  - None of the old OHT names remain
  - list_tools() is read-only (mutating the return value does not affect the registry)
  - The MCP-exposed tool names (via FastMCP) exactly match REGISTERED_TOOLS
"""

from server import list_tools, mcp, REGISTERED_TOOLS

_EXPECTED_TOOLS = {"get_kpi", "explain_release", "predict_scramble", "search_docs"}


def test_registered_tools_count():
    """Exactly four MCP tools are registered (3 REST + 1 doc-search)."""
    assert len(REGISTERED_TOOLS) == 4


def test_registered_tools_are_pbs_names():
    """REGISTERED_TOOLS contains all four expected PBS tool names."""
    assert set(REGISTERED_TOOLS) == _EXPECTED_TOOLS


def test_no_oht_names_remain():
    """Old OHT tool names must not appear in the registry.

    Note: get_kpi is intentionally excluded from the stale-name check below —
    it is a legitimately reused PBS tool name, not an OHT leftover.
    """
    oht_names = {"explain_bottleneck", "suggest_rebalance"}
    assert oht_names.isdisjoint(set(REGISTERED_TOOLS)), (
        f"Stale OHT name(s) found: {oht_names & set(REGISTERED_TOOLS)}"
    )


def test_list_tools_returns_expected_names():
    """list_tools() returns all four PBS tool names."""
    tools = list_tools()
    assert set(tools) == _EXPECTED_TOOLS


def test_list_tools_is_readonly():
    """Mutating the return value does not affect the registry."""
    tools = list_tools()
    tools.append("injected_tool")
    assert "injected_tool" not in REGISTERED_TOOLS


async def test_mcp_exposed_names_match_registered_tools():
    """The actual MCP-exposed tool names (via FastMCP.list_tools) must match REGISTERED_TOOLS.

    FastMCP derives the exposed name from the function name by default; we use
    explicit @mcp.tool(name=...) decorators to ensure the host discovers exactly
    the names declared in REGISTERED_TOOLS.  This test catches any future drift
    between the decorator wiring and the registry.
    """
    mcp_tools = await mcp.list_tools()
    mcp_names = sorted(t.name for t in mcp_tools)
    assert mcp_names == sorted(REGISTERED_TOOLS), (
        f"MCP-exposed names {mcp_names!r} do not match REGISTERED_TOOLS {sorted(REGISTERED_TOOLS)!r}"
    )
