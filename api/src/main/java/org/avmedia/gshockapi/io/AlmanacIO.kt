package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioTimeZoneHelper
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Location of a world-time city (register 0x24), which the DW-H5600 uses to compute the
 * almanac (sunrise, sunset, moon age). Without it the almanac screen stays empty.
 *
 * Decoded from the official app's traffic:
 * `24 <slot> 01 <latitude f64 BE> <longitude f64 BE> <source>`, where source is
 * [SOURCE_PHONE] when the coordinates are the phone's position and [SOURCE_CITY] when they
 * are the city's own coordinates.
 */
object AlmanacIOFunctional {
    const val SOURCE_CITY: Byte = 0x01
    const val SOURCE_PHONE: Byte = 0x04

    fun encodeLocation(
        slot: Int,
        latitude: Double,
        longitude: Double,
        source: Byte,
    ): ByteArray {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
        return ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .put(0x24)
            .put(slot.toByte())
            .put(0x01)
            .putDouble(latitude)
            .putDouble(longitude)
            .put(source)
            .array()
    }
}

@RequiresApi(Build.VERSION_CODES.O)
object AlmanacIO {
    @Volatile
    private var phoneLocation: Pair<Double, Double>? = null

    /** Location sent with the next time sync; null falls back to the home city's coordinates. */
    fun setLocation(latitude: Double?, longitude: Double?) {
        phoneLocation = if (latitude != null && longitude != null) latitude to longitude else null
    }

    /** Writes the home city (slot 0) location, called during the time-setting sequence. */
    fun writeHomeLocation() {
        val record = phoneLocation?.let { (lat, lon) ->
            AlmanacIOFunctional.encodeLocation(0, lat, lon, AlmanacIOFunctional.SOURCE_PHONE)
        } ?: run {
            val zone = TimeIO.getCasioTimezone().zoneId
            val (lat, lon, known) = CasioTimeZoneHelper.getWorldCityCoordinates(zone)
            Timber.i("Almanac: no phone location, using ${zone.id} (known=$known)")
            AlmanacIOFunctional.encodeLocation(0, lat, lon, AlmanacIOFunctional.SOURCE_CITY)
        }
        IO.writeCmd(GetSetMode.SET, record)
    }
}
