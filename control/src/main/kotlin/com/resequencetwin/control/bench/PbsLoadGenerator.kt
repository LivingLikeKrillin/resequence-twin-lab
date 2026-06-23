package com.resequencetwin.control.bench

import com.resequencetwin.control.pbs.Body
import java.util.Collections
import java.util.Random

/**
 * Seeded paint-optimal (color-batched) body stream generator for PBS benchmarks (rev3 §4).
 *
 * Bodies are emitted in consecutive color batches (paint-optimal order from paint shop).
 * Model, options, and `dueDateSeq` are drawn from a seeded [Random].
 * Same seed → identical stream every time (deterministic; no `Math.random()`).
 *
 * **dueDateSeq semantics (JIS realism)**
 * In the real automotive PBS scenario the JIS plan has NO relation to paint-shop color
 * batching. `dueDateSeq` = the body's TARGET ASSEMBLY POSITION in the JIS plan.
 * This creates the authentic PBS trade-off between color-batch cohesion and JIS order.
 *
 * **Defaults match paint_stream.py color/model/option defaults:**
 * - Colors: RED, BLUE, WHITE, BLACK, SILVER
 * - Models: Fiesta, Focus, Puma, Kuga, Mustang
 * - Options pool: SUNROOF, LEATHER, SPORT_PKG, TOWING_PKG, AMBIENT_LIGHT
 * - Batch size range: 3–7
 * - Max options per body: 2
 */
class PbsLoadGenerator(
    private val seed: Long,
    private val totalBodies: Int,
    private val colors: List<String>,
    private val models: List<String>,
    private val optionsPool: List<String>,
    private val minBatch: Int,
    private val maxBatch: Int,
    private val maxOptionsPerBody: Int
) {

    /** Constructor using all defaults (seed + total bodies only). */
    constructor(seed: Long, totalBodies: Int) : this(
        seed, totalBodies,
        DEFAULT_COLORS, DEFAULT_MODELS, DEFAULT_OPTIONS,
        DEFAULT_MIN_BATCH, DEFAULT_MAX_BATCH, DEFAULT_MAX_OPTIONS_PER_BODY
    )

    init {
        require(totalBodies >= 0) { "totalBodies must be >= 0" }
        require(minBatch >= 1 && maxBatch >= minBatch) {
            "Invalid batch range [$minBatch,$maxBatch]"
        }
    }

    companion object {
        val DEFAULT_COLORS: List<String>  = listOf("RED", "BLUE", "WHITE", "BLACK", "SILVER")
        val DEFAULT_MODELS: List<String>  = listOf("Fiesta", "Focus", "Puma", "Kuga", "Mustang")
        val DEFAULT_OPTIONS: List<String> = listOf("SUNROOF", "LEATHER", "SPORT_PKG", "TOWING_PKG", "AMBIENT_LIGHT")
        const val DEFAULT_MIN_BATCH: Int = 3
        const val DEFAULT_MAX_BATCH: Int = 7
        const val DEFAULT_MAX_OPTIONS_PER_BODY: Int = 2

        /**
         * Pick [n] distinct options from pool without replacement.
         *
         * Mirrors Python `rng.sample(options_pool, n_opts)`.
         * Uses Fisher-Yates partial shuffle for correctness.
         */
        private fun pickOptions(rng: Random, pool: List<String>, n: Int): Set<String> {
            if (n == 0) return emptySet()
            val count = minOf(n, pool.size)
            val copy = ArrayList(pool)
            for (i in 0 until count) {
                val j = i + rng.nextInt(copy.size - i)
                val tmp = copy[i]
                copy[i] = copy[j]
                copy[j] = tmp
            }
            return Collections.unmodifiableSet(LinkedHashSet(copy.subList(0, count)))
        }
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    /** Intermediate holder before JIS due-date is assigned. */
    private data class BodyPrototype(
        val id: String,
        val color: String,
        val model: String,
        val opts: Set<String>
    )

    // -------------------------------------------------------------------------
    // Generation
    // -------------------------------------------------------------------------

    /**
     * Generate a color-batched paint stream with SHUFFLED JIS due-dates.
     *
     * Algorithm:
     * 1. Build the color-batched body list (bodies arrive in paint-optimal order).
     * 2. Generate a shuffled permutation of [0..totalBodies-1] representing the JIS
     *    assembly target positions. This shuffle is driven by the same seeded RNG,
     *    offset after the body-generation draws — ensuring determinism.
     * 3. Assign each body its JIS target position (shuffled) as `dueDateSeq`.
     *
     * @return ordered list of bodies (paint arrival order), each with a JIS-scrambled
     *         `dueDateSeq`; same seed → same list
     */
    fun generate(): List<Body> {
        if (totalBodies == 0) return emptyList()

        val rng = Random(seed)

        // ---- Phase 1: generate color/model/options in paint-batched order ----
        val prototypes = ArrayList<BodyPrototype>(totalBodies)
        var bodyCounter = 0

        while (prototypes.size < totalBodies) {
            // Pick color: avoid repeat if possible (mirror Python)
            val lastColor = if (prototypes.isEmpty()) null else prototypes[prototypes.size - 1].color
            val available: List<String> = if (colors.size == 1 || lastColor == null) {
                colors
            } else {
                ArrayList(colors).also { it.remove(lastColor) }
            }
            val color = available[rng.nextInt(available.size)]

            // Batch size (clamped to remaining)
            var batchSize = minBatch + rng.nextInt(maxBatch - minBatch + 1)
            batchSize = minOf(batchSize, totalBodies - prototypes.size)

            for (i in 0 until batchSize) {
                val model = models[rng.nextInt(models.size)]
                val nOpts = rng.nextInt(maxOptionsPerBody + 1)
                val opts = pickOptions(rng, optionsPool, nOpts)
                prototypes.add(BodyPrototype(
                    String.format("BODY-%05d", bodyCounter++),
                    color, model, opts
                ))
            }
        }

        // ---- Phase 2: generate shuffled JIS due-date assignment ----
        // Shuffle [0..totalBodies-1] using the SAME rng (continues from where phase 1 left off)
        // This models the JIS plan being independent of paint arrival order.
        val jisPlan = ArrayList<Int>(totalBodies)
        for (i in 0 until totalBodies) jisPlan.add(i)
        Collections.shuffle(jisPlan, rng)

        // ---- Phase 3: assemble Body records ----
        val bodies = ArrayList<Body>(totalBodies)
        for (i in 0 until totalBodies) {
            val p = prototypes[i]
            bodies.add(Body.incoming(p.id, p.color, p.model, p.opts, jisPlan[i]))
        }

        return bodies.toList()
    }
}
