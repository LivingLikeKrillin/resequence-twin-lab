package com.resequencetwin.control.stream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PbsEventJsonTest {

    private val mapper = jacksonObjectMapper()

    // ── BodyArrived (full payload) ──────────────────────────────────────────

    @Test
    fun `BodyArrived deserializes with all fields`() {
        val json = """{"type":"BodyArrived","eventId":"e1","seq":1,
            |"bodyId":"B1","color":"RED","model":"M1",
            |"options":["SUNROOF"],"dueDateSeq":5}""".trimMargin()

        val event = mapper.readValue<PbsEvent>(json)

        assertThat(event).isInstanceOf(BodyArrived::class.java)
        val arrived = event as BodyArrived
        assertThat(arrived.eventId).isEqualTo("e1")
        assertThat(arrived.seq).isEqualTo(1L)
        assertThat(arrived.bodyId).isEqualTo("B1")
        assertThat(arrived.color).isEqualTo("RED")
        assertThat(arrived.model).isEqualTo("M1")
        assertThat(arrived.options).containsExactly("SUNROOF")
        assertThat(arrived.dueDateSeq).isEqualTo(5)
    }

    @Test
    fun `BodyArrived omitting options and dueDateSeq uses defaults`() {
        val json = """{"type":"BodyArrived","eventId":"e2","seq":2,"bodyId":"B2","color":"BLUE","model":"M2"}"""

        val event = mapper.readValue<PbsEvent>(json)

        assertThat(event).isInstanceOf(BodyArrived::class.java)
        val arrived = event as BodyArrived
        assertThat(arrived.eventId).isEqualTo("e2")
        assertThat(arrived.seq).isEqualTo(2L)
        assertThat(arrived.bodyId).isEqualTo("B2")
        assertThat(arrived.color).isEqualTo("BLUE")
        assertThat(arrived.model).isEqualTo("M2")
        assertThat(arrived.options).isEmpty()
        assertThat(arrived.dueDateSeq).isEqualTo(0)
    }

    // ── ReleaseTick ─────────────────────────────────────────────────────────

    @Test
    fun `ReleaseTick deserializes correctly`() {
        val json = """{"type":"ReleaseTick","eventId":"t1","seq":2}"""

        val event = mapper.readValue<PbsEvent>(json)

        assertThat(event).isInstanceOf(ReleaseTick::class.java)
        val tick = event as ReleaseTick
        assertThat(tick.eventId).isEqualTo("t1")
        assertThat(tick.seq).isEqualTo(2L)
    }

    // ── LaneBlocked ─────────────────────────────────────────────────────────

    @Test
    fun `LaneBlocked deserializes correctly`() {
        val json = """{"type":"LaneBlocked","eventId":"b1","seq":3,"laneId":"L1"}"""

        val event = mapper.readValue<PbsEvent>(json)

        assertThat(event).isInstanceOf(LaneBlocked::class.java)
        val blocked = event as LaneBlocked
        assertThat(blocked.eventId).isEqualTo("b1")
        assertThat(blocked.seq).isEqualTo(3L)
        assertThat(blocked.laneId).isEqualTo("L1")
    }

    // ── LaneUnblocked ───────────────────────────────────────────────────────

    @Test
    fun `LaneUnblocked deserializes correctly`() {
        val json = """{"type":"LaneUnblocked","eventId":"u1","seq":4,"laneId":"L2"}"""

        val event = mapper.readValue<PbsEvent>(json)

        assertThat(event).isInstanceOf(LaneUnblocked::class.java)
        val unblocked = event as LaneUnblocked
        assertThat(unblocked.eventId).isEqualTo("u1")
        assertThat(unblocked.seq).isEqualTo(4L)
        assertThat(unblocked.laneId).isEqualTo("L2")
    }

    // ── Round-trip ──────────────────────────────────────────────────────────

    @Test
    fun `BodyArrived round-trip serialize then deserialize preserves equality`() {
        val original = BodyArrived(
            eventId = "rt1",
            seq = 10L,
            bodyId = "B99",
            color = "GREEN",
            model = "SUV",
            options = setOf("SUNROOF", "HUD"),
            dueDateSeq = 42,
        )

        val json = mapper.writeValueAsString(original)
        val deserialized = mapper.readValue<PbsEvent>(json)

        assertThat(deserialized).isEqualTo(original)
    }

    // ── Unknown type fails fast ─────────────────────────────────────────────

    @Test
    fun `unknown type value throws`() {
        val json = """{"type":"NonExistentEvent","eventId":"x","seq":99}"""

        assertThatThrownBy { mapper.readValue<PbsEvent>(json) }
            .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidTypeIdException::class.java)
    }
}
