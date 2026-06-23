package com.resequencetwin.control.graph;

import org.jgrapht.Graph;
import org.jgrapht.alg.scoring.BetweennessCentrality;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Congestion hotspot detection over the resource/track graph.
 *
 * <p>Two complementary signals:
 * <ol>
 *   <li><b>Structural hotspots</b> — resources with high betweenness centrality
 *       (JGraphT {@link BetweennessCentrality}); these are structurally vulnerable
 *       even under low load because all paths run through them.</li>
 *   <li><b>Utilization hotspots</b> — resources whose occupancy/capacity ratio
 *       exceeds a configurable threshold; detected directly from live twin state.</li>
 * </ol>
 *
 * <p>Both hotspot lists feed Chunk 2b (link-weight policy) and Chunk 3 (agent triage).
 *
 * <p>NOTE: single-threaded assumption — graph construction and betweenness
 * computation run in a single call; no concurrent mutation protection needed.
 */
public class CongestionDetector {

    private static final Logger LOG = Logger.getLogger(CongestionDetector.class.getName());

    /** Default utilization threshold above which a resource is considered congested. */
    public static final double DEFAULT_UTILIZATION_THRESHOLD = 0.80;

    private final Graph<String, DefaultEdge> resourceGraph;

    private CongestionDetector(Graph<String, DefaultEdge> resourceGraph) {
        this.resourceGraph = resourceGraph;
    }

    /**
     * Factory: build a {@link CongestionDetector} from an explicit resource graph.
     *
     * @param nodes     resource IDs (vertices)
     * @param edges     directed links between resources (source → target)
     */
    public static CongestionDetector forResourceGraph(
        List<String> nodes,
        List<Map.Entry<String, String>> edges
    ) {
        Graph<String, DefaultEdge> g = new DefaultDirectedGraph<>(DefaultEdge.class);
        nodes.forEach(g::addVertex);
        edges.forEach(e -> {
            // Ensure both endpoints exist even if not in the nodes list
            g.addVertex(e.getKey());
            g.addVertex(e.getValue());
            g.addEdge(e.getKey(), e.getValue());
        });
        return new CongestionDetector(g);
    }

    // -------------------------------------------------------------------------
    // Structural hotspots (betweenness centrality — JGraphT)
    // -------------------------------------------------------------------------

    /**
     * Returns the top-N resource nodes ranked by betweenness centrality.
     *
     * <p>Betweenness centrality measures how often a node lies on the shortest path
     * between other node pairs — high betweenness = structurally critical bottleneck.
     * Computation is delegated to JGraphT {@link BetweennessCentrality}; hand-rolling
     * is prohibited (spec §0).
     *
     * @param topN number of top hotspots to return (returns fewer if graph is smaller)
     * @return hotspot list sorted descending by betweenness score
     */
    public List<Hotspot> structuralHotspots(int topN) {
        // NOTE: single-threaded assumption — BetweennessCentrality is not thread-safe.
        BetweennessCentrality<String, DefaultEdge> bc =
            new BetweennessCentrality<>(resourceGraph);

        Map<String, Double> scores = bc.getScores();

        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(topN)
            .map(e -> new Hotspot(e.getKey(), HotspotKind.STRUCTURAL, e.getValue()))
            .toList();
    }

    /**
     * Expose the resource graph for downstream consumers (e.g., routing policy).
     */
    public Graph<String, DefaultEdge> resourceGraph() {
        return resourceGraph;
    }

    /**
     * Combined entry point for Chunk 3: returns structural betweenness top-N hotspots
     * plus all utilization hotspots that exceed the threshold, merged into a single list.
     *
     * <p>Ordering: structural hotspots (descending betweenness) first, then utilization
     * hotspots (descending ratio).
     *
     * @param utils      resource utilization snapshots from the digital twin
     * @param threshold  utilization fraction (0.0–1.0) above which a resource is flagged
     * @param topN       maximum number of structural hotspots to include
     * @return combined list of all detected hotspots
     */
    public List<Hotspot> detectAll(List<ResourceUtilization> utils, double threshold, int topN) {
        List<Hotspot> combined = new ArrayList<>();
        combined.addAll(structuralHotspots(topN));
        combined.addAll(utilizationHotspots(utils, threshold));
        return List.copyOf(combined);
    }

    // -------------------------------------------------------------------------
    // Utilization hotspots (threshold-based — hand-built logic)
    // -------------------------------------------------------------------------

    /**
     * Returns resources whose utilization (occupancy / capacity) strictly exceeds
     * the given threshold.
     *
     * <p>This is direct domain logic — not delegated to a library.
     *
     * @param utilizations  list of resource utilization snapshots from twin state
     * @param threshold     fraction (0.0–1.0); resource is flagged if util {@code >} threshold
     * @return hotspot list sorted descending by utilization ratio
     */
    public static List<Hotspot> utilizationHotspots(
        List<ResourceUtilization> utilizations,
        double threshold
    ) {
        List<Hotspot> result = new ArrayList<>();
        for (ResourceUtilization u : utilizations) {
            if (u.capacity() <= 0) {
                LOG.warning("Skipping resource '" + u.resourceId()
                    + "' with non-positive capacity: " + u.capacity());
                continue;
            }
            double ratio = (double) u.occupancy() / u.capacity();
            if (ratio > threshold) {
                result.add(new Hotspot(u.resourceId(), HotspotKind.UTILIZATION, ratio));
            }
        }
        result.sort(Comparator.comparingDouble(Hotspot::score).reversed());
        return result;
    }

    // -------------------------------------------------------------------------
    // Domain output types
    // -------------------------------------------------------------------------

    /**
     * Kind of congestion hotspot.
     */
    public enum HotspotKind {
        /** High betweenness centrality — structurally vulnerable. */
        STRUCTURAL,
        /** High occupancy/capacity ratio — currently saturated. */
        UTILIZATION
    }

    /**
     * A detected congestion hotspot.
     *
     * <p>Fed to Chunk 2b (link-weight policy) and Chunk 3 (agent explain_bottleneck).
     *
     * @param resourceId  resource identifier
     * @param kind        whether this is structural or utilization-based
     * @param score       betweenness score (STRUCTURAL) or utilization ratio (UTILIZATION)
     */
    public record Hotspot(String resourceId, HotspotKind kind, double score) {}

    /**
     * Snapshot of a resource's occupancy and capacity from the digital twin.
     *
     * @param resourceId  resource identifier (Ditto Thing ID)
     * @param occupancy   current number of units present
     * @param capacity    maximum units the resource can hold
     */
    public record ResourceUtilization(String resourceId, int occupancy, int capacity) {}
}
