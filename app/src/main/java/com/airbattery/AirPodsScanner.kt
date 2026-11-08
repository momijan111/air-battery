package com.airbattery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
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
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val recent = mutableMapOf<String, Pair<ScanResult, Long>>()
    private var running = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
    }

    private fun handle(result: ScanResult) {
        val now = SystemClock.elapsedRealtimeNanos()
        recent[result.device.address] = result to now
        recent.entries.removeAll { now - it.value.second > 10_000_000_000L }
        val best = recent.values.maxByOrNull { it.first.rssi }?.first ?: return
        if (best.rssi < MIN_RSSI) return
        val data = best.scanRecord?.getManufacturerSpecificData(AirPodsParser.APPLE_MANUFACTURER_ID)
        val status = AirPodsParser.parse(data) ?: return
        onResult(status, best.rssi)
    }

    @SuppressLint("MissingPermission") // 呼び出し前に MainActivity で権限を確認している
    fun start(): Boolean {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        if (running) return true
        val data = ByteArray(AirPodsParser.DATA_LENGTH).also { it[0] = 0x07; it[1] = 0x19 }
        val mask = ByteArray(AirPodsParser.DATA_LENGTH).also { it[0] = -1; it[1] = -1 }
        val filter = ScanFilter.Builder()
            .setManufacturerData(AirPodsParser.APPLE_MANUFACTURER_ID, data, mask)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
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
