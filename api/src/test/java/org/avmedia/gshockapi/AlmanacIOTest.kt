package org.avmedia.gshockapi

import org.avmedia.gshockapi.io.AlmanacIOFunctional
import org.junit.Assert.assertEquals
import org.junit.Test

class AlmanacIOTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** CHICAGO record exactly as the official app wrote it to world-city slot 1. */
    @Test
    fun encodesCityRecordLikeTheOfficialApp() {
        val record = AlmanacIOFunctional.encodeLocation(1, 41.87811, -87.6298, AlmanacIOFunctional.SOURCE_CITY)
        assertEquals("2401014044f065e8922531c055e84ea4a8c15501", record.hex())
    }

    @Test
    fun phoneLocationUsesSource4() {
        val record = AlmanacIOFunctional.encodeLocation(0, 47.3769, 8.5417, AlmanacIOFunctional.SOURCE_PHONE)
        assertEquals(20, record.size)
        assertEquals("240001", record.copyOfRange(0, 3).hex())
        assertEquals(0x04.toByte(), record.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLatitude() {
        AlmanacIOFunctional.encodeLocation(0, 91.0, 0.0, AlmanacIOFunctional.SOURCE_CITY)
    }
}
