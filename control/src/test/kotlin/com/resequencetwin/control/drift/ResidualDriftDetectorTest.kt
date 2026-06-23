package com.resequencetwin.control.drift

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ResidualDriftDetectorTest {

    @Test
    fun `first update has zero residual and is not breached`() {
        val d = ResidualDriftDetector(metric = "releases", alpha = 0.3, threshold = 1.0)
        val f = d.update(predicted = 5.0, observed = 5.0)
        assertThat(f.residual).isEqualTo(0.0)
        assertThat(f.ewma).isEqualTo(0.0)
        assertThat(f.breached).isFalse()
    }

    @Test
    fun `matched rates keep residual zero and not breached`() {
        val d = ResidualDriftDetector(metric = "releases", alpha = 0.3, threshold = 1.0)
        d.update(0.0, 0.0)
        val f1 = d.update(2.0, 2.0)   // dPred=2, dObs=2 -> residual 0
        val f2 = d.update(4.0, 4.0)
        assertThat(f1.residual).isEqualTo(0.0)
        assertThat(f2.breached).isFalse()
    }

    @Test
    fun `sustained divergence eventually breaches the threshold`() {
        val d = ResidualDriftDetector(metric = "releases", alpha = 0.5, threshold = 1.0)
        d.update(0.0, 0.0)
        // twin predicts +3 per tick, reality only +1 -> residual +2 each tick; EWMA climbs past 1.0
        val findings = (1..5).map { i -> d.update(predicted = 3.0 * i, observed = 1.0 * i) }
        assertThat(findings.last().residual).isEqualTo(2.0)
        assertThat(findings.last().ewma).isGreaterThan(1.0)
        assertThat(findings.last().breached).isTrue()
    }

    @Test
    fun `ewma uses the configured alpha`() {
        val d = ResidualDriftDetector(metric = "m", alpha = 0.3, threshold = 100.0)
        d.update(0.0, 0.0)
        val f = d.update(predicted = 10.0, observed = 0.0)  // residual 10, ewma = 0.3*10 + 0.7*0 = 3.0
        assertThat(f.residual).isEqualTo(10.0)
        assertThat(f.ewma).isCloseTo(3.0, within(1e-9))
        assertThat(f.metric).isEqualTo("m")
    }

    @Test
    fun `determinism — identical input sequences produce identical outputs`() {
        fun run(): List<BehavioralDriftFinding> {
            val d = ResidualDriftDetector("m", alpha = 0.4, threshold = 1.5)
            return listOf(d.update(0.0, 0.0), d.update(3.0, 1.0), d.update(6.0, 2.0), d.update(7.0, 5.0))
        }
        assertThat(run()).isEqualTo(run())
    }
}
