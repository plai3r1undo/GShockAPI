package org.avmedia.gshockapi

import org.avmedia.gshockapi.io.ActivityIOFunctional
import org.avmedia.gshockapi.model.HeartRateSample
import org.avmedia.gshockapi.model.LifeLogActivity
import org.avmedia.gshockapi.model.LifeLogFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Two real transfers from the night of 2026-11-22/23 (0x00 status + 0x52 heart rate only):
 * 23:25 CET, when the readings start, and 10:38 CET, after waking up (the night's 0x5a said
 * 53 bpm average, bedtime 23:36 UTC, wake 08:27 UTC).
 */
class HeartRateIOTest {
    private val transfers: List<LifeLogActivity> by lazy {
        val dir = File(javaClass.classLoader!!.getResource("dwh5600/heartrate_2026-11-23")!!.toURI())
        dir.listFiles()!!.groupBy { it.name.substringBefore('_') }.toSortedMap().values.map { group ->
            val files = group.map { f ->
                LifeLogFile(0x2E, f.name.substringAfterLast('_').removeSuffix(".bin").toInt(16), f.readBytes())
            }
            ActivityIOFunctional.parse(files)!!
        }
    }

    @Test
    fun eveningReadingsStartAtTheBeginningOfTheTransfer() {
        // Status 22:25 UTC, 30 records of 2 min: 21:24..22:24, readings only in the first 13.
        val hr = transfers[0].heartRate
        assertEquals(Instant.parse("2026-11-22T22:25:00Z"), transfers[0].timestamp)
        assertEquals(
            listOf(60, 57, 62, 59, 61, 60, 62, 58, 64, 74, 79, 62, 158),
            hr.map { it.bpm },
        )
        assertEquals(HeartRateSample(Instant.parse("2026-11-22T21:24:00Z"), 60, 100), hr.first())
        // Motion artefact while getting up: lowest quality.
        assertEquals(HeartRateSample(Instant.parse("2026-11-22T21:48:00Z"), 158, 25), hr.last())
    }

    @Test
    fun morningReadingsAreOldestFirst() {
        // Status 09:38 UTC, 34 records: 08:30..09:36. The first two are empty; the high, low-quality
        // readings come right after waking (08:27 UTC), when 0x50 also has steps.
        val hr = transfers[1].heartRate
        assertEquals(32, hr.size)
        assertEquals(HeartRateSample(Instant.parse("2026-11-23T08:34:00Z"), 111, 25), hr.first())
        assertEquals(HeartRateSample(Instant.parse("2026-11-23T09:36:00Z"), 76, 100), hr.last())
        assertTrue(hr.zipWithNext().all { (a, b) -> b.start.isAfter(a.start) })
        assertEquals(setOf(25, 50, 75, 100), hr.map { it.quality }.toSet())
    }

    @Test
    fun emptyFileHasNoReadings() {
        val empty = byteArrayOf(0x52, 0x09, 0, 0, 0, 0xFE.toByte(), 0xFE.toByte(), 0x04, 0x08)
        assertEquals(emptyList<HeartRateSample>(), ActivityIOFunctional.heartRate(empty, Instant.parse("2026-11-23T10:41:00Z")))
    }
}
