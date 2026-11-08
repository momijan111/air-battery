package com.airbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock

/**
 * 周囲の AirPods の BLE 広告を受信する。
 * 近くにある他人の AirPods も受信してしまうため、OpenPods と同様に
 * 直近10秒で最も電波の強いもの(RSSI -60dBm 以上)を採用する。
 */
class AirPodsScanner(
    context: Context,
    private val onResult: (status: AirPodsParser.Status, rssi: Int) -> Unit,
    /** 診断用: 受信したすべての信号(Apple 以外も含む)。appleData は Apple 以外なら null */
    private val onRaw: (address: String, appleData: ByteArray?, rssi: Int) -> Unit = { _, _, _ -> },
    /** 診断用: スキャン開始に失敗したときのエラーコード */
    private val onFailed: (errorCode: Int) -> Unit = {},
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val recent = mutableMapOf<String, Pair<ScanResult, Long>>()
    private var running = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            running = false
            onFailed(errorCode)
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(AirPodsParser.APPLE_MANUFACTURER_ID)
        onRaw(result.device.address, data, result.rssi)
        // AirPods 形式として解析できる信号だけを候補にする
        if (AirPodsParser.parse(data) == null) return
        val now = SystemClock.elapsedRealtimeNanos()
        recent[result.device.address] = result to now
        recent.entries.removeAll { now - it.value.second > 10_000_000_000L }
        val best = recent.values.maxByOrNull { it.first.rssi }?.first ?: return
        if (best.rssi < MIN_RSSI) return
        val status = AirPodsParser.parse(
            best.scanRecord?.getManufacturerSpecificData(AirPodsParser.APPLE_MANUFACTURER_ID)
        ) ?: return
        onResult(status, best.rssi)
    }

    @SuppressLint("MissingPermission") // 呼び出し前に MainActivity で権限を確認している
    fun start(): Boolean {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        if (running) return true
        // 診断のため、フィルタなしで周囲の信号をすべて受け取り、コード側で判定する
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        running = true
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        adapter?.bluetoothLeScanner?.stopScan(callback)
        running = false
    }

    companion object {
        const val MIN_RSSI = -60
    }
}
