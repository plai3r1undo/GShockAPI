package org.avmedia.gshockapi.model

import androidx.annotation.Keep
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Sleep stage as reported by the watch (Polar sleep-wake state codes). */
@Keep
enum class SleepStage(val code: Int) {
    WAKE(-2),
    REM(-3),
    LIGHT(-5),
    DEEP(-6),
    ;

    companion object {
        fun fromCode(code: Int): SleepStage? = entries.firstOrNull { it.code == code }
    }
}

/** A stage change: from [offset] after sleep start until the next transition, the sleeper is in [stage]. */
@Keep
data class SleepStageTransition(
    val offset: Duration,
    val stage: SleepStage,
)

/**
 * Sleep-stage timeline for one night (life-log file 0x53). [date] is the evening date:
 * for sleep starting after midnight it is the day before the Nightly Recharge record date.
 * The last transition (WAKE) marks the end of the sleep span.
 */
@Keep
data class Hypnogram(
    val date: LocalDate,
    val transitions: List<SleepStageTransition>,
) {
    val span: Duration get() = transitions.lastOrNull()?.offset ?: Duration.ZERO

    /** Total time per stage, computed from consecutive transitions. */
    fun totals(): Map<SleepStage, Duration> =
        transitions
            .zipWithNext { a, b -> a.stage to (b.offset - a.offset) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, d) -> d.fold(Duration.ZERO, Duration::plus) }
}

/**
 * One night of Nightly Recharge data (life-log file 0x5a) as computed by the watch.
 * Durations are rounded to the minute by the watch.
 */
@Keep
data class NightlyRecharge(
    /**
     * Date stored by the watch: the UTC date of [sleepStart]. Unique per night, but for sleep
     * starting between local midnight and the UTC offset it is the day before the local date.
     */
    val recordDate: LocalDate,
    /** Sleep start / end. The watch stores these as UTC times of day. */
    val sleepStart: Instant,
    val sleepEnd: Instant,
    /** Sleep charge score as shown by the official app (e.g. 67). */
    val sleepCharge: Int,
    /** ANS charge, range -10.0..+10.0. */
    val ansCharge: Float,
    val sleepSpan: Duration,
    val actualSleep: Duration,
    val rem: Duration,
    val light: Duration,
    val deep: Duration,
    val heartRateBpm: Int,
    val heartRateBaselineBpm: Int,
    val beatIntervalMs: Int,
    val beatIntervalBaselineMs: Int,
    val hrvMs: Int,
    val hrvBaselineMs: Int,
    val breathingRate: Float,
    val breathingRateBaseline: Float,
    /** Stage timeline for this night, when the watch sent one. */
    val hypnogram: Hypnogram? = null,
    /** Undecoded record bytes (after inversion), for future decoding. */
    val raw: ByteArray = ByteArray(0),
) {
    val interruptions: Duration get() = sleepSpan - actualSleep
}

/** A raw file received through the life-log transfer, already un-inverted. */
@Keep
data class LifeLogFile(
    val category: Int,
    val id: Int,
    val data: ByteArray,
)

/**
 * Result of a sleep sync. The watch only sends records it has not sent before,
 * so callers must persist [nights] (and ideally [rawFiles]) themselves.
 */
@Keep
data class SleepData(
    val nights: List<NightlyRecharge>,
    /** Hypnograms that could not be matched to a Nightly Recharge record. */
    val unmatchedHypnograms: List<Hypnogram> = emptyList(),
    /** Every file received in the transfer, including ones not yet decoded. */
    val rawFiles: List<LifeLogFile> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Daytime activity (steps, kcal, active time) received in the same transfer. */
    val activity: LifeLogActivity? = null,
) {
    companion object {
        fun unavailable(reason: String? = null) = SleepData(emptyList(), warnings = listOfNotNull(reason))
    }
}
