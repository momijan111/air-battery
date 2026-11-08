package com.airbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException

/**
 * AAP (Apple Accessory Protocol) で AirPods と直接通信する(実験的機能)。
 *
 * パケットの形式は LibrePods の docs/AAP Definitions.md
 * (https://github.com/librepods-org/librepods/blob/main/docs/AAP%20Definitions.md) に記載の内容に基づく。
 * この資料は AirPods Pro 2 (USB-C) ファームウェア 7A305 で調べたもの。
 *
 * 接続には L2CAP PSM 0x1001 を使う。公開 API では開けないため、非公開の
 * BluetoothDevice.createInsecureL2capSocket(int) を HiddenApiBypass 経由で呼ぶ
 * (https://github.com/ioscastaway/airpods-on-android の README による)。
 * 端末や Android のバージョンによっては接続できない(LibrePods の README では root が必要とされる端末がある)。
 */
class AapClient(private val listener: Listener) {

    interface Listener {
        fun onState(state: String)
        fun onBattery(items: List<Battery>)
        fun onListeningMode(mode: Int)
        fun onEar(primary: Int, secondary: Int)
        fun onPacket(hex: String)
    }

    enum class Part { LEFT, RIGHT, CASE, UNKNOWN }

    /** status: 1=充電中 2=放電中 4=未接続 0=不明 */
    data class Battery(val part: Part, val level: Int, val status: Int)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var thread: Thread? = null

    val isConnected get() = socket?.isConnected == true

    private fun post(block: () -> Unit) = main.post(block)

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
        val method = try {
            BluetoothDevice::class.java.getMethod("createInsecureL2capSocket", Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            HiddenApiBypass.getDeclaredMethod(BluetoothDevice::class.java, "createInsecureL2capSocket", Int::class.javaPrimitiveType)
        }
        method.isAccessible = true
        return method.invoke(device, PSM) as BluetoothSocket
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (thread?.isAlive == true) return
        val name = device.name ?: device.address
        thread = Thread {
            try {
                post { listener.onState("$name に接続中…") }
                val s = openSocket(device)
                socket = s
                s.connect()
                post { listener.onState("接続しました") }
                send(HANDSHAKE)
                send(REQUEST_NOTIFICATIONS)
                val buf = ByteArray(1024)
                val input = s.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    handle(buf.copyOf(n))
                }
                post { listener.onState("切断されました") }
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                post { listener.onState("接続できませんでした: ${cause.javaClass.simpleName}: ${cause.message}") }
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
        }.also { it.start() }
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(bytes: ByteArray) {
        val s = socket ?: throw IOException("未接続")
        s.outputStream.write(bytes)
        s.outputStream.flush()
    }

    /** ノイズコントロールを切り替える。mode: 1=オフ 2=ノイズキャンセリング 3=外部音取り込み 4=適応型 */
    fun setListeningMode(mode: Int) {
        Thread {
            try {
                send(byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, mode.toByte(), 0x00, 0x00, 0x00))
            } catch (e: IOException) {
                post { listener.onState("送信できませんでした: ${e.message}") }
            }
        }.start()
    }

    private fun handle(p: ByteArray) {
        val hex = p.joinToString(" ") { "%02X".format(it) }
        post { listener.onPacket(hex) }
        parse(p)?.let { r ->
            post {
                when (r) {
                    is Parsed.BatteryList -> listener.onBattery(r.items)
                    is Parsed.Mode -> listener.onListeningMode(r.mode)
                    is Parsed.Ear -> listener.onEar(r.primary, r.secondary)
                }
            }
        }
    }

    sealed class Parsed {
        data class BatteryList(val items: List<Battery>) : Parsed()
        data class Mode(val mode: Int) : Parsed()
        data class Ear(val primary: Int, val secondary: Int) : Parsed()
    }

    companion object {
        const val PSM = 0x1001

        val HANDSHAKE = byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val REQUEST_NOTIFICATIONS = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xFF.toByte())

        private fun u(b: Byte) = b.toInt() and 0xFF

        /** 受信パケットを解析する(テストしやすいよう static にしている) */
        fun parse(p: ByteArray): Parsed? {
            if (p.size < 6 || u(p[0]) != 0x04 || u(p[1]) != 0x00 || u(p[2]) != 0x04 || u(p[3]) != 0x00) return null
            return when (u(p[4])) {
                // 04 00 04 00 04 00 [個数] ([部位] 01 [残量] [状態] 01) × 個数
                0x04 -> {
                    if (p.size < 7) return null
                    val count = u(p[6])
                    val items = (0 until count).mapNotNull { i ->
                        val o = 7 + i * 5
                        if (o + 3 >= p.size) return@mapNotNull null
                        val part = when (u(p[o])) {
                            0x04 -> Part.LEFT   // 資料の表: Left = 04
                            0x02 -> Part.RIGHT  // 資料の表: Right = 02
                            0x08 -> Part.CASE
                            else -> Part.UNKNOWN
                        }
                        Battery(part, u(p[o + 2]), u(p[o + 3]))
                    }
                    Parsed.BatteryList(items)
                }
                // 04 00 04 00 06 00 [主] [副]  00=装着 01=外している 02=ケース内
                0x06 -> if (p.size >= 8) Parsed.Ear(u(p[6]), u(p[7])) else null
                // 04 00 04 00 09 00 0D [モード] 00 00 00
                0x09 -> if (p.size >= 8 && u(p[6]) == 0x0D) Parsed.Mode(u(p[7])) else null
                else -> null
            }
        }
    }
}
