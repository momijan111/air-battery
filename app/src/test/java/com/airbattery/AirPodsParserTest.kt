package com.airbattery

import org.junit.Assert.*
import org.junit.Test

class AirPodsParserTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // 54文字(27バイト)のテスト用データ。各フィールドを手で設定したもの。
    private fun sample(model: String, b5: String, b6: String, b7: String) =
        hex("0719" + "01" + model + b5 + b6 + b7 + "00".repeat(19))

    @Test
    fun parsesNotFlipped() {
        // byte5 上位=0x2(bit1=1 → 反転なし) 下位=0xA(左右とも装着)
        // byte6 = 0x8 (右80%), 0x6 (左60%) / byte7 = 0x1 (左充電中), 0x5 (ケース50%)
        val s = AirPodsParser.parse(sample("0E20", "2A", "86", "15"))!!
        assertEquals("AirPods Pro", s.model)
        assertEquals(60, s.leftPercent)
        assertEquals(80, s.rightPercent)
        assertEquals(50, s.casePercent)
        assertTrue(s.leftCharging)
        assertFalse(s.rightCharging)
        assertFalse(s.caseCharging)
        assertTrue(s.leftInEar)
        assertTrue(s.rightInEar)
    }

    @Test
    fun parsesFlipped() {
        // byte5 上位=0x0(bit1=0 → 反転) 下位=0x2 → 反転時は右が装着
        val s = AirPodsParser.parse(sample("1420", "02", "86", "4F"))!!
        assertEquals("AirPods Pro (第2世代)", s.model)
        assertEquals(80, s.leftPercent)
        assertEquals(60, s.rightPercent)
        assertNull(s.casePercent) // 0xF = 不明
        assertTrue(s.caseCharging)
        assertFalse(s.leftInEar)
        assertTrue(s.rightInEar)
    }

    @Test
    fun findsBlockAfterOtherBlock() {
        // 先頭に別の種類(0x10, 長さ2)のかたまりがあり、その後ろに AirPods 形式がある場合
        val data = hex("10" + "02" + "AABB") + sample("0E20", "2A", "86", "15")
        val s = AirPodsParser.parse(data)!!
        assertEquals(60, s.leftPercent)
    }

    @Test
    fun rejectsOtherData() {
        assertNull(AirPodsParser.parse(null))
        assertNull(AirPodsParser.parse(ByteArray(10)))
        assertNull(AirPodsParser.parse(ByteArray(27))) // 先頭が 07 19 ではない
    }
}
