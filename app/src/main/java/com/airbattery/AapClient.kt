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

    /** 非公開 API でクラシック L2CAP のソケットを作る。secure=false なら Insecure 版 */
    private fun openSocket(device: BluetoothDevice, name: String): BluetoothSocket {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/") }
        return HiddenApiBypass.invoke(BluetoothDevice::class.java, device, name, PSM) as BluetoothSocket
    }

    /** 接続処理の世代番号。disconnect() や新しい connect() で古い処理を止めるために使う */
    @Volatile private var generation = 0

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (thread?.isAlive == true) return
        val gen = ++generation
        val name = device.name ?: device.address
        thread = Thread {
            fun stopped() = gen != generation
            val log = StringBuilder()
            fun step(msg: String) {
                log.appendLine(msg)
                val text = log.toString().trim()
                post { listener.onState(text) }
            }
            var attempt = 0
            while (!stopped() && attempt < MAX_ATTEMPTS) {
                attempt++
                for (method in listOf("createInsecureL2capSocket", "createL2capSocket")) {
                    if (stopped()) return@Thread
                    val s = try {
                        openSocket(device, method)
                    } catch (e: Throwable) {
                        val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                        step("[$attempt] $method 作成失敗: ${c.javaClass.simpleName} ${c.message ?: ""}")
                        continue
                    }
                    socket = s
                    step("[$attempt] $method で $name に接続中…")
                    // connect() が戻らない場合に備え、時間切れでソケットを閉じる
                    val watchdog = Thread {
                        try { Thread.sleep(CONNECT_TIMEOUT_MS) } catch (_: InterruptedException) { return@Thread }
                        if (!s.isConnected) runCatching { s.close() }
                    }.also { it.start() }
                    try {
                        s.connect()
                        watchdog.interrupt()
                        step("[$attempt] 接続しました")
                        post { listener.onState(CONNECTED) }
                        primer(s)
                        val buf = ByteArray(2048)
                        val input = s.inputStream
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            handle(buf.copyOf(n))
                        }
                        attempt = 0
                        step("切断されました")
                    } catch (e: Throwable) {
                        watchdog.interrupt()
                        val timedOut = !s.isConnected && e is IOException
                        step("[$attempt] $method 失敗: ${e.javaClass.simpleName} ${e.message ?: ""}" +
                            if (timedOut) "（${CONNECT_TIMEOUT_MS / 1000}秒以内に完了しないと打ち切ります）" else "")
                    } finally {
                        runCatching { s.close() }
                        socket = null
                    }
                }
                if (stopped()) break
                val wait = minOf(500L shl (attempt - 1), 15_000L)
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            }
            if (!stopped()) step("${MAX_ATTEMPTS}回試しましたが接続できませんでした")
        }.also { it.start() }
    }

    /** 接続直後の初期化パケット。AirPods が取りこぼすことがあるため 0秒・0.2秒・5秒 に送る */
    private fun primer(s: BluetoothSocket) {
        Thread {
            fun sendAll() = runCatching {
                listOf(HANDSHAKE, SET_FEATURES, REQUEST_NOTIFICATIONS).forEach { s.outputStream.write(it); s.outputStream.flush() }
            }
            sendAll()
            try {
                Thread.sleep(200); sendAll()
                Thread.sleep(4800); sendAll()
            } catch (_: InterruptedException) {}
        }.start()
    }

    fun disconnect() {
        generation++
        thread?.interrupt()
        thread = null
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
        const val CONNECTED = "接続しました"
        private const val MAX_ATTEMPTS = 5
        private const val CONNECT_TIMEOUT_MS = 10_000L
        val SET_FEATURES = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

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
