"""
retrieval.py — deterministic local RAG over ADRs + KPI glossary.

No network, no embeddings, no API key.

Scoring: token-overlap (BM25-inspired term frequency) over chunked docs.
Each chunk carries a `source` field (filename + heading) for citation.
"""

from __future__ import annotations

import math
import re
from pathlib import Path
from typing import TypedDict

# ---------------------------------------------------------------------------
# Locate repo root relative to THIS file so retrieval works from any CWD
# ---------------------------------------------------------------------------
_AGENT_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _AGENT_DIR.parent

_ADR_002 = _REPO_ROOT / "docs" / "adr" / "ADR-002-sequencing-solver.md"
# ADR-001 (OHT routing/Dijkstra) is intentionally excluded — it documents the
# ABANDONED routing scenario quarantined in legacy-oht/. Indexing it would
# ground the PBS advisory agent on irrelevant routing content.

# ---------------------------------------------------------------------------
# KPI glossary (inline — derived from the spec and ADR-002 metrics table)
# ---------------------------------------------------------------------------
_GLOSSARY_TEXT = """\
# KPI Glossary

## colorChanges
colorChanges counts the number of times the paint colour changes between consecutive bodies
released from the Painted Body Store (PBS) into the assembly line. Each colour switch
triggers a paint-gun flush, causing waste and downtime. The dynamic resequencing policy
(W_COLOR=4.0) prioritises reducing colorChanges — the dominant objective per Ford Saarlouis
PBS literature. A lower colorChanges value is better.

## avgBatchLength
avgBatchLength is the average number of consecutive same-colour bodies released as a
contiguous block (colour batch). Longer batches mean fewer colour switches. The dynamic
policy extends batches by scoring same-colour candidates higher (W_COLOR bonus). A higher
avgBatchLength is better.

## dueDateDeviation
dueDateDeviation measures how far a body's actual release position deviates from its
JIS (Just-In-Sequence) sequence number. Zero deviation means perfectly on-sequence.
The anti-starvation hard constraint in DynamicSequencingPolicy overrides colour scoring
when dueDateSeq <= assemblyOutSize (overdue), preventing indefinite postponement.
The W_DUE=3.0 weight and due-date bonus favour timely bodies within the due-date
window, but the colour-objective (W_COLOR=4.0) may outweigh it on some seeds —
honestly noted in ADR-002 as an "OBSERVED" invariant, not a hard guarantee.
Lower dueDateDeviation is better.

## throughput
throughput is the number of bodies successfully released to the assembly out-buffer
over the simulation run. Both static and dynamic policies share the same lane capacity,
so throughput deltas are expected to be near-zero. A slight positive delta is favourable.

## laneUtilization
laneUtilization is the fraction of PBS lanes that contributed at least one release
across the run (utilisation breadth). Higher utilisation indicates balanced lane usage
and avoids hotspot starvation. The option-leveling weight (W_OPTION=2.0) indirectly
spreads load across lanes by penalising consecutive high-work-content option bodies.

## riskScore
riskScore is the scramble forecast risk score in [0, 1] returned by /api/predict/scramble.
It is a TRANSPARENT weighted average (not a learned model) of three interpretable
current-buffer features: colour fragmentation (weight 0.45), overdue-front ratio (0.35),
and lane imbalance (0.20). A higher riskScore indicates the release sequence is more
likely to scramble (lose colour batching and/or JIS adherence) if the current policy
continues unchanged. The heuristic and its learned-model upgrade path are recorded in
ADR-003.

## level
level is the categorical risk classification derived from riskScore
(see ScrambleLevel; thresholds match the Java control implementation):
  LOW    -> riskScore < 0.35
  MEDIUM -> 0.35 <= riskScore < 0.65
  HIGH   -> riskScore >= 0.65
The stabilizationHint field in the ScrambleForecast provides an actionable suggestion
when risk is MEDIUM or HIGH.
"""

_GLOSSARY_SOURCE_PREFIX = "docs/kpi-glossary (inline)"


# ---------------------------------------------------------------------------
# Chunk type
# ---------------------------------------------------------------------------


class Chunk(TypedDict):
    source: str
    text: str


# ---------------------------------------------------------------------------
# Tokenisation helpers
# ---------------------------------------------------------------------------
_TOKEN_RE = re.compile(r"[a-zA-Z0-9]+")


def _tokenise(text: str) -> list[str]:
    return [t.lower() for t in _TOKEN_RE.findall(text)]


# ---------------------------------------------------------------------------
# Document loading + chunking
# ---------------------------------------------------------------------------


def _chunk_markdown(path: Path, source_prefix: str) -> list[Chunk]:
    """Split a Markdown file into per-heading chunks."""
    text = path.read_text(encoding="utf-8")
    # Split on lines starting with '#'
    parts = re.split(r"(?m)^(#{1,6}\s+.+)$", text)
    chunks: list[Chunk] = []
    current_heading = source_prefix
    buffer: list[str] = []

    for part in parts:
        if re.match(r"^#{1,6}\s+", part):
            if buffer and "".join(buffer).strip():
                chunks.append(
                    Chunk(
                        source=f"{source_prefix} > {current_heading.strip()}",
                        text=" ".join(buffer).strip(),
                    )
                )
            current_heading = part.strip("#").strip()
            buffer = []
        else:
            buffer.append(part)

    if buffer and "".join(buffer).strip():
        chunks.append(
            Chunk(
                source=f"{source_prefix} > {current_heading.strip()}",
                text=" ".join(buffer).strip(),
            )
        )

    return chunks


def _chunk_glossary() -> list[Chunk]:
    """Chunk the inline KPI glossary per ## heading.

    The bare top-level '# KPI Glossary' heading is NOT emitted as a chunk —
    it carries no retrievable content and would only add noise to BM25 results.
    """
    parts = re.split(r"(?m)^(##\s+.+)$", _GLOSSARY_TEXT)
    chunks: list[Chunk] = []
    current_heading = "KPI Glossary"
    buffer: list[str] = []

    for part in parts:
        if re.match(r"^##\s+", part):
            content = "".join(buffer).strip()
            if content and content != "# KPI Glossary":
                chunks.append(
                    Chunk(
                        source=f"{_GLOSSARY_SOURCE_PREFIX} > {current_heading}",
                        text=" ".join(buffer).strip(),
                    )
                )
            current_heading = part.strip("#").strip()
            buffer = []
        else:
            buffer.append(part)

    if buffer and "".join(buffer).strip():
        chunks.append(
            Chunk(
                source=f"{_GLOSSARY_SOURCE_PREFIX} > {current_heading}",
                text=" ".join(buffer).strip(),
            )
        )

    return chunks


# ---------------------------------------------------------------------------
# Corpus build (eager, once at module import)
# ---------------------------------------------------------------------------


def _build_corpus() -> list[Chunk]:
    corpus: list[Chunk] = []
    corpus.extend(_chunk_markdown(_ADR_002, "docs/adr/ADR-002-sequencing-solver.md"))
    corpus.extend(_chunk_glossary())
    return corpus


_CORPUS: list[Chunk] = _build_corpus()


# ---------------------------------------------------------------------------
# BM25-style scoring (k1=1.5, b=0.75)
# ---------------------------------------------------------------------------

_K1 = 1.5
_B = 0.75


def _build_index(corpus: list[Chunk]) -> tuple[list[list[str]], dict[str, float]]:
    """Return (token lists per doc, IDF table)."""
    tokenised = [_tokenise(c["text"]) for c in corpus]
    df: dict[str, int] = {}
    N = len(tokenised)
    for tokens in tokenised:
        for t in set(tokens):
            df[t] = df.get(t, 0) + 1
    idf = {t: math.log((N - df_t + 0.5) / (df_t + 0.5) + 1) for t, df_t in df.items()}
    return tokenised, idf


_TOKENISED_CORPUS, _IDF = _build_index(_CORPUS)
_AVG_DL = (
    sum(len(t) for t in _TOKENISED_CORPUS) / len(_TOKENISED_CORPUS)
    if _TOKENISED_CORPUS
    else 1.0
)


def _bm25_score(query_tokens: list[str], doc_tokens: list[str]) -> float:
    dl = len(doc_tokens)
    tf_map: dict[str, int] = {}
    for t in doc_tokens:
        tf_map[t] = tf_map.get(t, 0) + 1

    score = 0.0
    for qt in query_tokens:
        if qt not in _IDF:
            continue
        tf = tf_map.get(qt, 0)
        numerator = tf * (_K1 + 1)
        denominator = tf + _K1 * (1 - _B + _B * dl / _AVG_DL)
        score += _IDF[qt] * numerator / denominator
    return score


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def search_docs(query: str, k: int = 3) -> list[Chunk]:
    """Return the top-k most relevant chunks for *query* (deterministic)."""
    query_tokens = _tokenise(query)
    if not query_tokens:
        return []

    scored = [
        (i, _bm25_score(query_tokens, _TOKENISED_CORPUS[i]))
        for i in range(len(_CORPUS))
    ]
    # Sort descending by score, then ascending by index for stable tie-breaking
    scored.sort(key=lambda x: (-x[1], x[0]))
    return [_CORPUS[i] for i, _ in scored[:k]]
