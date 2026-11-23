package org.avmedia.gshockapi

import org.avmedia.gshockapi.io.ActivityIOFunctional
import org.avmedia.gshockapi.model.LifeLogActivity
import org.avmedia.gshockapi.model.LifeLogFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

/** Seven real transfers from 2026-11-22 (12:56..16:22 CET); the watch showed 1801 steps, ~1593 kcal, 0:50 active. */
class ActivityIOTest {
    private val transfers: List<LifeLogActivity> by lazy {
        val dir = File(javaClass.classLoader!!.getResource("dwh5600/activity_2026-11-22")!!.toURI())
        dir.listFiles()!!.groupBy { it.name.substringBefore('_') }.toSortedMap().values.map { group ->
            val files = group.map { f ->
                LifeLogFile(0x2E, f.name.substringAfterLast('_').removeSuffix(".bin").toInt(16), f.readBytes())
            }
            ActivityIOFunctional.parse(files)!!
        }
    }

    @Test
    fun totalsMatchWatch() {
        val last = transfers.last()
        assertEquals(Instant.parse("2026-11-22T15:22:00Z"), last.timestamp)
        assertEquals(1801, last.todaySteps)
        assertEquals(1588, last.todayKcal)
        assertEquals(Duration.ofMinutes(50), last.activeTime)
        assertEquals(listOf(1033, 1033, 1033, 1113, 1305, 1555, 1801), transfers.map { it.todaySteps })
    }

    @Test
    fun intervalsAreContiguousAndAddUp() {
        transfers.zipWithNext().forEach { (a, b) ->
            assertEquals(a.intervals.last().start.plusSeconds(300), b.intervals.first().start)
        }
        assertEquals(Instant.parse("2026-11-22T15:15:00Z"), transfers.last().intervals.last().start)
        // Steps in the bins equal the growth of the daily total (except the bin still in progress).
        assertEquals(80, transfers[3].intervals.sumOf { it.steps })
        assertEquals(192, transfers[4].intervals.sumOf { it.steps })
        assertEquals(250, transfers[5].intervals.sumOf { it.steps })
    }

    @Test
    fun energyMatchesDailyKcalGrowth() {
        // Transfers 3..7 cover 12:55..15:20 UTC; the watch's total grew 1308 -> 1588 kcal (12:54 -> 15:22).
        val kcal = transfers.subList(2, 7).flatMap { it.intervals }.sumOf { it.kcal!!.toDouble() }
        assertEquals(1588.0 - 1308.0, kcal, 280 * 0.03)
        assertEquals(990 / 128f, transfers[1].intervals.first().kcal!!, 0.001f)
    }
}
