package com.airbattery

import org.junit.Assert.*
import org.junit.Test

class AapClientTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun parsesBatteryExampleFromDocs() {
        // LibrePods docs/AAP Definitions.md に載っている例
        val r = AapClient.parse(hex("04 00 04 00 04 00 03 02 01 64 02 01 04 01 63 01 01 08 01 11 02 01"))
            as AapClient.Parsed.BatteryList
        assertEquals(3, r.items.size)
        assertEquals(AapClient.Battery(AapClient.Part.RIGHT, 100, 2), r.items[0])
        assertEquals(AapClient.Battery(AapClient.Part.LEFT, 99, 1), r.items[1])
        assertEquals(AapClient.Battery(AapClient.Part.CASE, 17, 2), r.items[2])
    }

    @Test
    fun parsesListeningModeAndEar() {
        assertEquals(AapClient.Parsed.Mode(2), AapClient.parse(hex("04 00 04 00 09 00 0D 02 00 00 00")))
        assertEquals(AapClient.Parsed.Ear(0, 1), AapClient.parse(hex("04 00 04 00 06 00 00 01")))
        assertNull(AapClient.parse(hex("04 00 04 00 09 00 28 01 00 00 00")))
        assertNull(AapClient.parse(hex("00 00 04 00")))
    }
}
