package com.airbattery

/**
 * AirPods が BLE で発信している Apple のメーカー固有データ(会社ID 0x004C = 76)を解析する。
 *
 * このデータ形式は Apple が公開している仕様ではなく、オープンソースアプリ OpenPods
 * (https://github.com/adolfintel/OpenPods) などで解析されている非公式な形式。
 * 以下のインデックスは OpenPods の PodsStatus.java が使っている「16進文字列の何文字目か」
 * (0始まり)を、バイト + 上位/下位4ビットに置き換えたもの。
 *
 *   16進文字 6-9  (byte 3-4)        : モデルID
 *   16進文字 10   (byte 5 上位4bit) : bit1 が 0 なら左右が反転している
 *   16進文字 11   (byte 5 下位4bit) : 装着状態
 *   16進文字 12   (byte 6 上位4bit) : 右(反転時は左)の電池
 *   16進文字 13   (byte 6 下位4bit) : 左(反転時は右)の電池
 *   16進文字 14   (byte 7 上位4bit) : 充電中フラグ bit0=左 bit1=右 bit2=ケース
 *   16進文字 15   (byte 7 下位4bit) : ケースの電池
 * 電池の値は 0〜10 が 0〜100%(10%刻み)、15 は「不明/未接続」。
 */
object AirPodsParser {
    const val APPLE_MANUFACTURER_ID = 76
    const val DATA_LENGTH = 27

    data class Status(
        val model: String,
        /** null は不明(ケースに入っている・未接続など) */
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
        val leftCharging: Boolean,
        val rightCharging: Boolean,
        val caseCharging: Boolean,
        val leftInEar: Boolean,
        val rightInEar: Boolean,
    )

    private fun nibble(data: ByteArray, hexIndex: Int): Int {
        val b = data[hexIndex / 2].toInt() and 0xFF
        return if (hexIndex % 2 == 0) b shr 4 else b and 0x0F
    }

    private fun toPercent(v: Int): Int? = if (v in 0..10) v * 10 else null

    fun modelName(data: ByteArray): String {
        val id = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        return when (id) {
            0x0220 -> "AirPods (第1世代)"
            0x0F20 -> "AirPods (第2世代)"
            0x1320 -> "AirPods (第3世代)"
            0x0E20 -> "AirPods Pro"
            0x1420, 0x2420 -> "AirPods Pro (第2世代)"
            0x2720 -> "AirPods Pro (第3世代)"
            else -> when (nibble(data, 7)) {
                0xA -> "AirPods Max"
                0xB -> "Powerbeats Pro"
                else -> "不明なモデル (ID: %04X)".format(id)
            }
        }
    }

    /**
     * Apple のメーカー固有データは「種類(1バイト) 長さ(1バイト) 中身」の並びになっている。
     * その中から種類 0x07 のかたまりを探し、「種類+長さ+中身」を返す。見つからなければ null。
     */
    fun findProximityPairing(data: ByteArray?): ByteArray? {
        if (data == null) return null
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            val end = i + 2 + len
            if (type == 0x07) return data.copyOfRange(i, minOf(end, data.size))
            if (end > data.size) return null
            i = end
        }
        return null
    }

    /** Apple のデータの中に AirPods 形式(0x07 0x19、計27バイト)のかたまりがあれば解析結果を返す */
    fun parse(appleData: ByteArray?): Status? {
        val data = findProximityPairing(appleData)
        if (data == null || data.size != DATA_LENGTH) return null
        if (data[0].toInt() != 0x07 || data[1].toInt() != 0x19) return null

        val flipped = (nibble(data, 10) and 0x02) == 0
        val left = nibble(data, if (flipped) 12 else 13)
        val right = nibble(data, if (flipped) 13 else 12)
        val case = nibble(data, 15)
        val charge = nibble(data, 14)
        val inEar = nibble(data, 11)

        return Status(
            model = modelName(data),
            leftPercent = toPercent(left),
            rightPercent = toPercent(right),
            casePercent = toPercent(case),
            leftCharging = (charge and (if (flipped) 0b10 else 0b01)) != 0,
            rightCharging = (charge and (if (flipped) 0b01 else 0b10)) != 0,
            caseCharging = (charge and 0b100) != 0,
            leftInEar = (inEar and (if (flipped) 0b1000 else 0b0010)) != 0,
            rightInEar = (inEar and (if (flipped) 0b0010 else 0b1000)) != 0,
        )
    }
}
