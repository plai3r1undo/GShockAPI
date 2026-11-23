package org.avmedia.gshockapi.model

import androidx.annotation.Keep
import java.time.Duration
import java.time.Instant

/** Steps counted, and energy burned (incl. resting, when known), in the 5-minute interval starting at [start]. */
@Keep
data class StepInterval(
    val start: Instant,
    val steps: Int,
    val kcal: Float? = null,
)

/**
 * Heart rate measured in the 2-minute interval starting at [start]. [quality] is the watch's
 * confidence: 100, 75, 50 or 25 (low values go with motion artefacts, e.g. 158 bpm at 25).
 */
@Keep
data class HeartRateSample(
    val start: Instant,
    val bpm: Int,
    val quality: Int,
)

/**
 * Daytime activity from one life-log transfer (DW-H5600).
 *
 * [todaySteps], [todayKcal] and [activeTime] are the watch's running totals for its current
 * (local) day at [timestamp]; they match what the watch shows. [intervals] are the 5-minute
 * step bins completed since the previous transfer (the watch sends each bin once).
 * [heartRate] are the 2-minute heart-rate readings since the previous transfer; empty while
 * the watch is not measuring (so far: only in its night window).
 */
@Keep
data class LifeLogActivity(
    /** Watch time when the transfer was made. */
    val timestamp: Instant,
    val todaySteps: Int,
    /** Total energy burned today, including resting energy (as shown by the watch). */
    val todayKcal: Int,
    val activeTime: Duration?,
    val intervals: List<StepInterval>,
    val heartRate: List<HeartRateSample> = emptyList(),
)
