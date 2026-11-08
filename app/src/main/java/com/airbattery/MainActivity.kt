package com.airbattery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private var status by mutableStateOf<AirPodsParser.Status?>(null)
    private var rssi by mutableStateOf<Int?>(null)
    private var lastUpdate by mutableStateOf(0L)
    private var connectedNames by mutableStateOf<List<String>>(emptyList())
    private var lastSeen by mutableStateOf<LastSeenStore.LastSeen?>(null)
    private var message by mutableStateOf<String?>(null)

    private lateinit var scanner: AirPodsScanner
    private lateinit var store: LastSeenStore

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startIfReady() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LastSeenStore(this)
        lastSeen = store.load()
        scanner = AirPodsScanner(this) { s, r ->
            runOnUiThread { onAirPodsSeen(s, r) }
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startIfReady()
    }

    override fun onPause() {
        super.onPause()
        scanner.stop()
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startIfReady() {
        val missing = requiredPermissions.filterNot(::hasPermission)
        if (missing.isNotEmpty()) {
            message = "Bluetooth と位置情報の権限が必要です。"
            return
        }
        refreshConnectedDevices()
        message = if (scanner.start()) null else "Bluetooth がオフになっています。オンにしてください。"
    }

    private fun onAirPodsSeen(s: AirPodsParser.Status, r: Int) {
        status = s
        rssi = r
        val now = System.currentTimeMillis()
        lastUpdate = now
        // 位置の記録は30秒に1回まで
        if (now - (lastSeen?.timeMillis ?: 0L) > 30_000L) {
            val loc = lastKnownLocation()
            val seen = LastSeenStore.LastSeen(now, loc?.latitude, loc?.longitude, loc?.accuracy)
            store.save(seen)
            lastSeen = store.load()
        }
    }

    @SuppressLint("MissingPermission") // startIfReady() で権限確認済みのときのみ呼ばれる
    private fun lastKnownLocation(): Location? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** スマホに A2DP(音楽再生) で接続中の Bluetooth 機器名を取得する */
    @SuppressLint("MissingPermission")
    private fun refreshConnectedDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val names = proxy.connectedDevices.map { d: BluetoothDevice -> d.name ?: d.address }
                runOnUiThread { connectedNames = names }
                adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun openMap(seen: LastSeenStore.LastSeen) {
        val lat = seen.lat ?: return
        val lon = seen.lon ?: return
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(AirPods)")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "地図アプリが見つかりません", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun Screen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AirBattery", fontSize = 28.sp, fontWeight = FontWeight.Bold)

            message?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(msg)
                        if (requiredPermissions.any { !hasPermission(it) }) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                                Text("権限を許可する")
                            }
                        }
                    }
                }
            }

            ConnectedCard()
            BatteryCard()
            LocationCard()

            Text(
                "※ 電池残量は AirPods が発信する Bluetooth 信号(Apple 非公開の形式)を解析して表示しています。" +
                    "10% 単位で、近くにある他人の AirPods を拾う可能性もあります。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun ConnectedCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("接続中のオーディオ機器", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (connectedNames.isEmpty()) Text("なし") else connectedNames.forEach { Text(it) }
                TextButton(onClick = { refreshConnectedDevices() }) { Text("再読み込み") }
            }
        }
    }

    @Composable
    private fun BatteryCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val s = status
                if (s == null) {
                    Text("電池残量", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("AirPods を探しています… ケースのふたを開けるか、耳に装着してください。")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    return@Column
                }
                Text(s.model, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BatteryItem("左", s.leftPercent, s.leftCharging, s.leftInEar)
                    BatteryItem("右", s.rightPercent, s.rightCharging, s.rightInEar)
                    BatteryItem("ケース", s.casePercent, s.caseCharging, null)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "最終更新: " + DateFormat.getTimeInstance().format(Date(lastUpdate)),
                    fontSize = 12.sp,
                )
            }
        }
    }

    @Composable
    private fun BatteryItem(label: String, percent: Int?, charging: Boolean, inEar: Boolean?) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label)
            Text(percent?.let { "$it%" } ?: "--", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.width(72.dp))
            }
            if (charging) Text("⚡充電中", fontSize = 12.sp)
            if (inEar == true) Text("装着中", fontSize = 12.sp)
        }
    }

    @Composable
    private fun LocationCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("位置", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val r = rssi
                if (r != null && System.currentTimeMillis() - lastUpdate < 15_000L) {
                    val level = when {
                        r >= -45 -> "とても近い"
                        r >= -55 -> "近い"
                        else -> "やや離れている"
                    }
                    Text("電波の強さ: $r dBm（$level）")
                    Text(
                        "※ 電波の強さによる目安です。正確な距離ではありません。",
                        fontSize = 12.sp,
                    )
                } else {
                    Text("現在、近くで検出されていません。")
                }
                Spacer(Modifier.height(12.dp))
                val seen = lastSeen
                if (seen == null) {
                    Text("最後に検出した場所: 記録なし")
                } else {
                    Text("最後に検出した日時: " + DateFormat.getDateTimeInstance().format(Date(seen.timeMillis)))
                    if (seen.lat != null && seen.lon != null) {
                        Text("場所(スマホの位置): %.5f, %.5f".format(seen.lat, seen.lon))
                        seen.accuracyM?.let { Text("精度: 約 ${it.toInt()} m", fontSize = 12.sp) }
                        Button(onClick = { openMap(seen) }) { Text("地図で開く") }
                    } else {
                        Text("位置情報は取得できませんでした。")
                    }
                }
            }
        }
    }
}
