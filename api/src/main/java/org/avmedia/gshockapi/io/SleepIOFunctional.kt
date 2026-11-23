package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.model.Hypnogram
import org.avmedia.gshockapi.model.LifeLogFile
import org.avmedia.gshockapi.model.NightlyRecharge
import org.avmedia.gshockapi.model.SleepData
import org.avmedia.gshockapi.model.SleepStage
import org.avmedia.gshockapi.model.SleepStageTransition
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure decoding of DW-H5600 life-log sleep files. No I/O, fully unit-testable.
 *
 * File framing (after un-inverting): `<id u8> <total length u32 LE> <records...>`.
 * Dates are BCD `YY MM DD`; a record whose payload starts with 0xFE is an empty slot.
 */
object SleepIOFunctional {
    const val FILE_NIGHTLY_RECHARGE = 0x5A
    const val FILE_HYPNOGRAM = 0x53

    private const val FILE_HEADER = 5
    private const val RECHARGE_RECORD_SIZE = 55
    private const val HYPNOGRAM_BLOCK_SIZE = 2016
    private const val HYPNOGRAM_ENTRIES_OFFSET = 8
    private const val EMPTY_MARKER = 0xFE

    /** CRC-16/KERMIT, used by the watch for every 512-byte transfer block (computed over on-wire bytes). */
    fun crc16Kermit(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc
    }

    fun invert(data: ByteArray): ByteArray = ByteArray(data.size) { (data[it].toInt() xor 0xFF).toByte() }

    private fun bcd(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v shr 4) * 10 + (v and 0x0F)
    }

    private fun bcdDate(data: ByteArray, offset: Int): LocalDate? =
        runCatching { LocalDate.of(2000 + bcd(data[offset]), bcd(data[offset + 1]), bcd(data[offset + 2])) }.getOrNull()

    private fun le(data: ByteArray, offset: Int): ByteBuffer = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.LITTLE_ENDIAN)

    private fun u8(data: ByteArray, o: Int) = data[o].toInt() and 0xFF

    private fun u16(data: ByteArray, o: Int) = le(data, o).short.toInt() and 0xFFFF

    private fun f32(data: ByteArray, o: Int) = le(data, o).float

    private fun hm(data: ByteArray, o: Int): Duration = Duration.ofHours(u8(data, o).toLong()).plusMinutes(u8(data, o + 1).toLong())

    private fun fileBody(file: ByteArray): ByteArray? {
        if (file.size < FILE_HEADER) return null
        val declared = le(file, 1).int
        if (declared < FILE_HEADER || declared > file.size) return null
        return file.copyOfRange(FILE_HEADER, declared)
    }

    /** Parses file 0x5a. Times are attached to the night via the hypnogram, if matched later. */
    fun parseNightlyRecharge(file: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<NightlyRecharge> {
        val body = fileBody(file) ?: return emptyList()
        return body.toList().chunked(RECHARGE_RECORD_SIZE)
            .filter { it.size == RECHARGE_RECORD_SIZE }
            .mapNotNull { parseRechargeRecord(it.toByteArray(), zone) }
    }

    private fun parseRechargeRecord(rec: ByteArray, zone: ZoneId): NightlyRecharge? {
        val date = bcdDate(rec, 0) ?: return null
        val d = rec.copyOfRange(5, rec.size) // skip date + 2 constant bytes (04 08)
        if (u8(d, 0) == EMPTY_MARKER) return null

        val bedTime = LocalTime.of(u8(d, 43) % 24, u8(d, 44) % 60)
        val wakeTime = LocalTime.of(u8(d, 45) % 24, u8(d, 46) % 60)
        val span = hm(d, 27)
        // The record date is the *UTC* date of the bedtime (also UTC), so together they are the exact
        // start. Verified on 2026-11-23: record 2026-11-22 + bed 23:36 UTC = 00:36 CET on the 23rd.
        val start = date.atTime(bedTime).toInstant(ZoneOffset.UTC)
        var end = start.plus(span)
        // Wake time is authoritative to the minute; keep it consistent with the span.
        val wakeCandidate = end.atZone(ZoneOffset.UTC).toLocalDate().atTime(wakeTime).toInstant(ZoneOffset.UTC)
        if (Duration.between(end, wakeCandidate).abs() <= Duration.ofMinutes(2)) end = wakeCandidate

        return NightlyRecharge(
            recordDate = date,
            sleepStart = start,
            sleepEnd = end,
            sleepCharge = u8(d, 25),
            ansCharge = f32(d, 3),
            sleepSpan = span,
            actualSleep = hm(d, 29),
            rem = hm(d, 37),
            deep = hm(d, 39),
            light = hm(d, 41),
            heartRateBpm = u8(d, 7),
            heartRateBaselineBpm = u8(d, 8),
            beatIntervalMs = u16(d, 9),
            beatIntervalBaselineMs = u16(d, 11),
            hrvMs = u16(d, 13),
            hrvBaselineMs = u16(d, 15),
            breathingRate = f32(d, 17),
            breathingRateBaseline = f32(d, 21),
            raw = rec,
        )
    }

    /** Parses file 0x53: one 2016-byte block per night of `(u32 seconds, i32 stage)` transitions. */
    fun parseHypnograms(file: ByteArray): List<Hypnogram> {
        val body = fileBody(file) ?: return emptyList()
        return (0 until body.size / HYPNOGRAM_BLOCK_SIZE).mapNotNull { n ->
            val block = body.copyOfRange(n * HYPNOGRAM_BLOCK_SIZE, (n + 1) * HYPNOGRAM_BLOCK_SIZE)
            if (u8(block, 0) == EMPTY_MARKER) return@mapNotNull null
            val date = bcdDate(block, 0) ?: return@mapNotNull null
            val transitions = mutableListOf<SleepStageTransition>()
            var o = HYPNOGRAM_ENTRIES_OFFSET
            while (o + 8 <= block.size) {
                val buf = le(block, o)
                val seconds = buf.int.toLong() and 0xFFFFFFFFL
                val stage = SleepStage.fromCode(buf.int) ?: break
                if (transitions.isNotEmpty() && Duration.ofSeconds(seconds) < transitions.last().offset) break
                transitions += SleepStageTransition(Duration.ofSeconds(seconds), stage)
                o += 8
            }
            if (transitions.isEmpty()) null else Hypnogram(date, transitions)
        }
    }

    /**
     * Pairs hypnograms with Nightly Recharge records by sleep span (both come from the same
     * night, but the watch's date stamps can differ by a day between the two files).
     */
    fun combine(files: List<LifeLogFile>, zone: ZoneId = ZoneId.systemDefault()): SleepData {
        val warnings = mutableListOf<String>()
        val nights = files.filter { it.id == FILE_NIGHTLY_RECHARGE }.flatMap { parseNightlyRecharge(it.data, zone) }
        val hypnograms = files.filter { it.id == FILE_HYPNOGRAM }.flatMap { parseHypnograms(it.data) }.toMutableList()

        val matched =
            nights.map { night ->
                fun gap(h: Hypnogram) = (h.span - night.sleepSpan).abs()
                val best = hypnograms.minByOrNull(::gap)?.takeIf { gap(it) <= Duration.ofMinutes(1) }
                if (best == null) {
                    warnings += "No hypnogram for night ${night.recordDate}"
                    night
                } else {
                    hypnograms.remove(best)
                    night.copy(hypnogram = best)
                }
            }
        return SleepData(nights = matched, unmatchedHypnograms = hypnograms, rawFiles = files, warnings = warnings)
    }
}
