package org.avmedia.gshockapi

import org.avmedia.gshockapi.io.LifeLogTransferSession
import org.avmedia.gshockapi.io.LifeLogTransferSession.Action
import org.avmedia.gshockapi.io.LifeLogTransferSession.Channel
import org.avmedia.gshockapi.io.SleepIOFunctional
import org.avmedia.gshockapi.model.LifeLogFile
import org.avmedia.gshockapi.model.SleepStage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Fixtures captured from a DW-H5600 syncing with the official app on 2026-11-22. */
class SleepIOTest {
    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("dwh5600/$name")!!.readBytes()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun mins(h: Long, m: Long) = Duration.ofHours(h).plusMinutes(m)

    @Test
    fun parsesNightlyRechargeMatchingOfficialApp() {
        val night = SleepIOFunctional.parseNightlyRecharge(res("recharge_2026-11-21.bin"), ROME).single()
        assertEquals(67, night.sleepCharge)
        assertEquals(0.386f, night.ansCharge, 0.001f)
        assertEquals(mins(8, 27), night.sleepSpan)
        assertEquals(mins(7, 34), night.actualSleep)
        assertEquals(mins(0, 53), night.interruptions)
        assertEquals(mins(1, 48), night.rem)
        assertEquals(mins(4, 2), night.light)
        assertEquals(mins(1, 44), night.deep)
        assertEquals(55, night.heartRateBpm)
        assertEquals(70, night.hrvMs)
        // App showed 11:49 PM - 8:16 AM in CET (UTC+1)
        assertEquals(Instant.parse("2026-11-21T22:49:00Z"), night.sleepStart)
        assertEquals(Instant.parse("2026-11-22T07:16:00Z"), night.sleepEnd)
    }

    @Test
    fun hypnogramTotalsMatchOfficialApp() {
        val h = SleepIOFunctional.parseHypnograms(res("hypnogram_2026-11-21.bin")).single()
        assertEquals(LocalDate.of(2026, 11, 21), h.date)
        assertEquals(118, h.transitions.size)
        assertEquals(Duration.ofSeconds(30450), h.span)
        val t = h.totals()
        assertEquals(mins(0, 53), t[SleepStage.WAKE])
        assertEquals(mins(1, 48), t[SleepStage.REM])
        assertEquals(Duration.ofSeconds(14550), t[SleepStage.LIGHT]) // 4:02:30, app shows 4:02
        assertEquals(mins(1, 44), t[SleepStage.DEEP])
        assertEquals(SleepStage.DEEP, h.transitions[12].stage) // first deep phase at +0:40:30
        assertEquals(Duration.ofSeconds(2430), h.transitions[12].offset)
    }

    @Test
    fun combinesMultipleNightsBySpan() {
        val data =
            SleepIOFunctional.combine(
                listOf(LifeLogFile(0x2E, 0x5A, res("recharge_3nights.bin")), LifeLogFile(0x2E, 0x53, res("hypnogram_3nights.bin"))),
                ROME,
            )
        assertEquals(2, data.nights.size) // third slot is empty
        assertTrue(data.unmatchedHypnograms.isEmpty())
        data.nights.forEach { n ->
            val h = assertNotNull(n.hypnogram).let { n.hypnogram!! }
            assertEquals(n.rem.toMinutes(), h.totals()[SleepStage.REM]!!.toMinutes())
            assertEquals(n.deep.toMinutes(), h.totals()[SleepStage.DEEP]!!.toMinutes())
        }
        assertEquals(listOf(88, 60), data.nights.map { it.sleepCharge })
        // Official app files the 88 / ANS -10.0 night under Nov 19: asleep at 03:31 CET = 02:31 UTC
        val n19 = data.nights.first()
        assertEquals(LocalDate.of(2026, 11, 19), n19.recordDate)
        assertEquals(-10.0f, n19.ansCharge, 0.001f)
        assertEquals(Instant.parse("2026-11-19T02:31:00Z"), n19.sleepStart)
        assertEquals(Instant.parse("2026-11-19T10:32:00Z"), n19.sleepEnd)
    }

    @Test
    fun crcIsKermit() {
        assertEquals(0x2189, SleepIOFunctional.crc16Kermit("123456789".toByteArray()))
    }

    /** Replays the watch's notifications; our writes must match what the official app sent. */
    @Test
    fun replaysCapturedTransfer() {
        val lines =
            res("sync_2026-11-22.trace").decodeToString().lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split(" ").let { p -> Triple(p[0], Channel.valueOf(p[1]), hex(p[2])) } }
        val start = lines.indexOfFirst { it.first == "TX" && it.second == Channel.CTRL && it.third.contentEquals(hex("002effffffffff")) }
        val end = lines.indexOfFirst { it.first == "RX" && it.second == Channel.CTRL && it.third.contentEquals(hex("042effffffffff")) }
        assertTrue(start >= 0 && end > start)
        // Up to (excluding) the next transfer request
        val next = (end until lines.size).firstOrNull { lines[it].first == "TX" && lines[it].second == Channel.CTRL && lines[it].third[0].toInt() == 0x00 } ?: lines.size
        val segment = lines.subList(start, next)

        val session = LifeLogTransferSession(0x2E)
        val ours = mutableListOf<Pair<Channel, String>>()
        fun take(actions: List<Action>) = actions.filterIsInstance<Action.Write>().forEach { ours += it.channel to it.bytes.toHexStr() }
        take(session.start())
        segment.filter { it.first == "RX" }.forEach { take(session.onNotification(it.second, it.third)) }

        val theirs = segment.filter { it.first == "TX" }.map { it.second to it.third.toHexStr() }
        assertEquals(theirs, ours)
        assertTrue(session.isDone)
        assertTrue(session.warnings.toString(), session.warnings.isEmpty())

        val files = session.files.associateBy { it.id }
        assertEquals(listOf(0x00, 0x65, 0x50, 0x51, 0x52, 0x53, 0x56, 0x5A, 0x72), session.files.map { it.id })
        assertArrayEquals(res("recharge_2026-11-21.bin"), files.getValue(0x5A).data)
        assertArrayEquals(res("hypnogram_2026-11-21.bin"), files.getValue(0x53).data)

        val night = SleepIOFunctional.combine(session.files, ROME).nights.single()
        assertEquals(67, night.sleepCharge)
        assertNotNull(night.hypnogram)
    }

    private companion object {
        val ROME: java.time.ZoneId = java.time.ZoneId.of("Europe/Rome")
    }

    private fun ByteArray.toHexStr() = joinToString("") { "%02x".format(it) }

    /** Sleep starting after local midnight: the watch dates it by the UTC day (the day before). */
    @Test
    fun nightStartingAfterLocalMidnightUsesUtcDate() {
        val files = listOf(
            org.avmedia.gshockapi.model.LifeLogFile(0x2E, 0x5A, res("recharge_2026-11-23.bin")),
            org.avmedia.gshockapi.model.LifeLogFile(0x2E, 0x53, res("hypnogram_2026-11-23.bin")),
        )
        val night = SleepIOFunctional.combine(files, java.time.ZoneId.of("Europe/Rome")).nights.single()
        assertEquals(LocalDate.of(2026, 11, 22), night.recordDate)
        // Watch showed 75 / +3.6; slept 00:36 - 09:27 CET on the 23rd.
        assertEquals(75, night.sleepCharge)
        assertEquals(3.6f, (night.ansCharge * 10).toInt() / 10f, 0f)
        assertEquals(Instant.parse("2026-11-22T23:36:00Z"), night.sleepStart)
        assertEquals(Instant.parse("2026-11-23T08:27:00Z"), night.sleepEnd)
        assertEquals(mins(8, 51), night.sleepSpan)
        assertEquals(mins(8, 6), night.actualSleep)
        assertNotNull(night.hypnogram)
    }
}
