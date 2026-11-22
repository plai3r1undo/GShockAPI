package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.model.SleepData
import timber.log.Timber

/** Fetches Nightly Recharge + hypnogram data from watches that support it (DW-H5600). */
object SleepIO {
    suspend fun request(): SleepData {
        if (!WatchInfo.hasSleepData) {
            Timber.i("Sleep data not supported on watch model: ${WatchInfo.model}")
            return SleepData.unavailable("not supported on ${WatchInfo.model}")
        }
        val result = LifeLogTransferIO.fetch(LifeLogTransferIO.CATEGORY_HEALTH)
        val data = SleepIOFunctional.combine(result.files)
        return data.copy(
            warnings = result.warnings + data.warnings,
            activity = ActivityIOFunctional.parse(result.files),
        )
    }
}
