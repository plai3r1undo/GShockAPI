package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.model.LifeLogActivity
import org.avmedia.gshockapi.model.LifeLogFile
import org.avmedia.gshockapi.model.StepInterval
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Pure decoding of DW-H5600 daytime activity files (life-log category 0x2E).
 *
 * - 0x00 (status): `1a 0b 16 HH MM` = UTC date (binary YY MM DD) and time of the transfer,
 *   then u16 ?, u16 0, u32 today's steps, u16 today's kcal (all LE).
 * - 0x50: one record per completed 5-minute interval since the last transfer:
 *   `<steps u16 LE> 04 08`. The last interval ends at the transfer time rounded down to 5 min.
 * - 0x51: energy for the same 5-minute intervals: `<u16 LE> 04 08`, 1/128 kcal
 *   (990 = 7.7 kcal at rest). Over 2.5 h its sum matched the growth of the daily kcal total
 *   within 0.5%.
 * - 0x65: daily summary; bytes 39/40 (from file start) = active time h, m.
 *
 * Not decoded (kept raw by callers): 0x52 (2-min, all 0xFEFE so far), 0x56 (1-min intensity
 * flag, tentative), 0x72 (32-byte motion record per 5 min), rest of 0x00/0x65.
 */
object ActivityIOFunctional {
    const val FILE_STATUS = 0x00
    const val FILE_STEPS_5MIN = 0x50
    const val FILE_ENERGY_5MIN = 0x51
    const val FILE_DAILY_SUMMARY = 0x65

    private const val FILE_HEADER = 5
    private const val STEP_RECORD_SIZE = 4
    private const val INTERVAL_MINUTES = 5L
    private const val ACTIVE_HOURS_OFFSET = 39
    private const val ACTIVE_MINUTES_OFFSET = 40
    private const val EMPTY = 0xFEFE
    private const val ENERGY_UNITS_PER_KCAL = 128f

    /** Decodes the activity of one transfer, or null if the status file is missing or malformed. */
    fun parse(files: List<LifeLogFile>): LifeLogActivity? {
        val byId = files.associateBy { it.id }
        val status = byId[FILE_STATUS]?.data ?: return null
        if (status.size < 20) return null
        val timestamp = runCatching {
            LocalDateTime.of(
                2000 + u8(status, 5), u8(status, 6), u8(status, 7),
                u8(status, 8), u8(status, 9),
            ).toInstant(ZoneOffset.UTC)
        }.getOrNull() ?: return null

        return LifeLogActivity(
            timestamp = timestamp,
            todaySteps = u32(status, 14).toInt(),
            todayKcal = u16(status, 18),
            activeTime = byId[FILE_DAILY_SUMMARY]?.data?.let(::activeTime),
            intervals = byId[FILE_STEPS_5MIN]?.data
                ?.let { stepIntervals(it, timestamp, byId[FILE_ENERGY_5MIN]?.data) }.orEmpty(),
        )
    }

    internal fun stepIntervals(
        file: ByteArray,
        timestamp: java.time.Instant,
        energyFile: ByteArray? = null,
    ): List<StepInterval> {
        val values = records(file)
        // Energy uses the same intervals; ignore it if the counts ever disagree.
        val energy = energyFile?.let(::records)?.takeIf { it.size == values.size }
        val minute = timestamp.truncatedTo(ChronoUnit.MINUTES)
        val minuteOfHour = minute.atZone(ZoneOffset.UTC).minute
        val end = minute.minus(Duration.ofMinutes(minuteOfHour % INTERVAL_MINUTES))
        val first = end.minus(Duration.ofMinutes(INTERVAL_MINUTES * values.size))
        return values.mapIndexedNotNull { i, v ->
            if (v == EMPTY) null
            else StepInterval(
                start = first.plus(Duration.ofMinutes(INTERVAL_MINUTES * i)),
                steps = v,
                kcal = energy?.get(i)?.takeIf { it != EMPTY }?.let { it / ENERGY_UNITS_PER_KCAL },
            )
        }
    }

    private fun records(file: ByteArray): List<Int> =
        (FILE_HEADER until file.size - STEP_RECORD_SIZE + 1 step STEP_RECORD_SIZE).map { u16(file, it) }

    private fun activeTime(file: ByteArray): Duration? {
        if (file.size <= ACTIVE_MINUTES_OFFSET) return null
        val h = u8(file, ACTIVE_HOURS_OFFSET)
        val m = u8(file, ACTIVE_MINUTES_OFFSET)
        if (h > 23 || m > 59) return null
        return Duration.ofMinutes(h * 60L + m)
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16))
}
