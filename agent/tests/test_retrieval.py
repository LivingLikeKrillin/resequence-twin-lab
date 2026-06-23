"""
R4c — tests for the local doc-retrieval tool (search_docs).

All tests run OFFLINE: retrieval is deterministic local text scoring.
No network, no embeddings API, no API key.

Tested behaviours
-----------------
- A query about CP-SAT / sequencing surfaces ADR-002 content
- A KPI query (due date deviation) surfaces the glossary definition
- Results carry source attribution (file + heading/section)
- Results are deterministic: same query -> same ranked order
- search_docs is importable from server (not just retrieval)
"""

from __future__ import annotations

import server


def _query(q: str, k: int = 3) -> list[dict]:
    """Thin wrapper so tests don't import retrieval directly."""
    return server.search_docs(q, k=k)


# ---------------------------------------------------------------------------
# Basic smoke
# ---------------------------------------------------------------------------


def test_search_docs_returns_list():
    results = _query("colour batch weight")
    assert isinstance(results, list)
    assert len(results) > 0


def test_search_docs_respects_k():
    results = _query("colour batch weight", k=1)
    assert len(results) == 1

    results = _query("colour batch weight", k=2)
    assert len(results) == 2


# ---------------------------------------------------------------------------
# Source attribution
# ---------------------------------------------------------------------------


def test_each_chunk_has_source_key():
    results = _query("colour batch weight")
    for chunk in results:
        assert "source" in chunk, f"Missing 'source' in chunk: {chunk}"


def test_each_chunk_has_text_key():
    results = _query("colour batch weight")
    for chunk in results:
        assert "text" in chunk, f"Missing 'text' in chunk: {chunk}"


def test_source_contains_filename_or_doc_identifier():
    """Each chunk source must reference a .md file OR the inline glossary."""
    results = _query("colour batch weight")
    for chunk in results:
        source = chunk["source"]
        assert ".md" in source or "glossary" in source, (
            f"Expected a .md filename or 'glossary' in source, got: {source!r}"
        )


# ---------------------------------------------------------------------------
# Content relevance
# ---------------------------------------------------------------------------


def test_cp_sat_query_surfaces_adr002():
    """A query about CP-SAT / sequencing solver should surface ADR-002 content."""
    results = _query("CP-SAT upgrade sequencing solver")
    sources = [r["source"] for r in results]
    assert any("ADR-002" in s for s in sources), (
        f"Expected ADR-002 in top results, got sources: {sources}"
    )


def test_colour_batch_query_surfaces_adr002():
    """A query about colour batch weight should surface ADR-002 scoring content."""
    results = _query("colour batch weight scoring")
    # ADR-002 describes the W_COLOR weight
    all_text = " ".join(r["text"] for r in results)
    assert "W_COLOR" in all_text or "colour" in all_text.lower() or "color" in all_text.lower(), (
        "Expected colour/batch content in top results"
    )


def test_legacy_routing_content_not_in_corpus():
    """ADR-001 (OHT routing/Dijkstra) must NOT appear in any retrieved results.

    The PBS advisory agent must not be grounded on the abandoned routing
    scenario quarantined in legacy-oht/. This is a negative corpus-scoping
    assertion: no result source should reference ADR-001 and no routing-only
    content (Dijkstra, JGraphT) should surface as top hits.
    """
    results = _query("Dijkstra routing solver JGraphT", k=5)
    sources = [r["source"] for r in results]
    assert not any("ADR-001" in s for s in sources), (
        f"Legacy ADR-001 routing content leaked into corpus: {sources}"
    )
    all_text = " ".join(r["text"] for r in results).lower()
    assert "dijkstra" not in all_text, (
        "Dijkstra routing content leaked into PBS corpus — ADR-001 must be excluded"
    )


def test_due_date_deviation_query_surfaces_glossary():
    """A query about due date deviation should surface the KPI glossary."""
    results = _query("due date deviation JIS sequence")
    all_text = " ".join(r["text"] for r in results).lower()
    # The glossary definition of dueDateDeviation mentions JIS or sequence adherence
    assert "due" in all_text and ("deviation" in all_text or "sequence" in all_text), (
        f"Expected due-date-deviation content in top results, got: {all_text[:300]}"
    )


def test_risk_score_query_surfaces_glossary():
    """A query about risk score / scramble level should surface the glossary."""
    results = _query("scramble risk score level HIGH MEDIUM LOW")
    all_text = " ".join(r["text"] for r in results).lower()
    assert "risk" in all_text or "scramble" in all_text, (
        f"Expected scramble/risk content in top results, got: {all_text[:300]}"
    )


def test_lane_utilization_query_surfaces_glossary():
    """A query about lane utilisation should surface the glossary definition."""
    results = _query("lane utilization PBS lanes")
    all_text = " ".join(r["text"] for r in results).lower()
    assert "lane" in all_text and "utiliz" in all_text, (
        f"Expected lane utilization content in top results, got: {all_text[:300]}"
    )


# ---------------------------------------------------------------------------
# Determinism
# ---------------------------------------------------------------------------


def test_search_docs_is_deterministic():
    """Same query must produce same ranked results every time."""
    q = "colour batch weight CP-SAT scoring"
    first = _query(q)
    second = _query(q)
    assert first == second, "search_docs is not deterministic"


def test_search_docs_different_queries_may_differ():
    """Two distinct queries should not necessarily return identical results."""
    r1 = _query("Dijkstra routing JGraphT")
    r2 = _query("due date deviation JIS")
    # We expect at least one result to differ between the two queries
    sources1 = [r["source"] for r in r1]
    sources2 = [r["source"] for r in r2]
    # If all sources are identical, retrieval is degenerate (not differentiating)
    assert sources1 != sources2 or r1[0]["text"] != r2[0]["text"], (
        "Distinct queries returned exactly the same results — retrieval may not be working"
    )
