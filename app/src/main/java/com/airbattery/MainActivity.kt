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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
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

    // 診断情報
    private var rawCount by mutableStateOf(0)
    private var allCount by mutableStateOf(0)
    private var appleCount by mutableStateOf(0)
    private var scanError by mutableStateOf<Int?>(null)
    /** Apple の信号を種類(先頭バイト)ごとに数えたもの */
    private val appleTypes = mutableStateMapOf<Int, Int>()
    /** 送信元ごとの最新の Apple の信号(16進, 電波の強さ, 時刻) */
    private data class AppleSample(val hex: String, val rssi: Int, val time: Long)
    private val appleSamples = mutableStateMapOf<String, AppleSample>()
    private var scanStarted by mutableStateOf(false)
    private var rawLastHex by mutableStateOf<String?>(null)
    private var rawLastLen by mutableStateOf<Int?>(null)
    private var rawLastRssi by mutableStateOf<Int?>(null)
    private var locationEnabled by mutableStateOf<Boolean?>(null)

    // AAP(直接通信)の状態
    private var aapState by mutableStateOf("未接続")
    private var aapPacket by mutableStateOf<String?>(null)
    private var aapMode by mutableStateOf<Int?>(null)
    private var aapEar by mutableStateOf<Pair<Int, Int>?>(null)
    private var aapConnected by mutableStateOf(false)
    private var chargeLeft by mutableStateOf(false)
    private var chargeRight by mutableStateOf(false)
    private var chargeCase by mutableStateOf(false)

    private val aap = AapClient(object : AapClient.Listener {
        override fun onState(state: String) {
            if (state == AapClient.CONNECTED) { aapConnected = true; return }
            aapState = state
            if (!state.lines().last().endsWith("接続しました")) aapConnected = false
        }
        override fun onBattery(items: List<AapClient.Battery>) {
            val now = System.currentTimeMillis()
            items.forEach { b ->
                if (b.status == 4) return@forEach // 未接続(ケース外など)は残量を更新しない
                val k = Known(b.level.coerceIn(0, 100), now)
                val charging = b.status == 1
                when (b.part) {
                    AapClient.Part.LEFT -> { knownLeft = k; chargeLeft = charging }
                    AapClient.Part.RIGHT -> { knownRight = k; chargeRight = charging }
                    AapClient.Part.CASE -> { knownCase = k; chargeCase = charging }
                    AapClient.Part.UNKNOWN -> {}
                }
            }
        }
        override fun onListeningMode(mode: Int) { aapMode = mode }
        override fun onEar(primary: Int, secondary: Int) { aapEar = primary to secondary }
        override fun onPacket(hex: String) { aapPacket = hex }
    })

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
        scanner = AirPodsScanner(
            this,
            onResult = { s, r -> runOnUiThread { onAirPodsSeen(s, r) } },
            onRaw = { addr, d, r ->
                runOnUiThread {
                    allCount++
                    if (d != null) {
                        appleCount++
                        val hex = d.joinToString("") { "%02X".format(it) }
                        if (d.isNotEmpty()) {
                            val type = d[0].toInt() and 0xFF
                            appleTypes[type] = (appleTypes[type] ?: 0) + 1
                        }
                        val now = System.currentTimeMillis()
                        appleSamples[addr] = AppleSample(hex, r, now)
                        appleSamples.entries.removeAll { now - it.value.time > 15_000L }
                        val block = AirPodsParser.findProximityPairing(d)
                        if (block != null) {
                            rawCount++
                            rawLastLen = block.size
                            rawLastRssi = r
                            rawLastHex = block.joinToString("") { "%02X".format(it) }
                        }
                    }
                }
            },
            onFailed = { code -> runOnUiThread { scanError = code } },
        )
        setContent {
            MaterialTheme { Screen() }
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

    override fun onDestroy() {
        super.onDestroy()
        aap.disconnect()
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
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else null
        scanStarted = scanner.start()
        message = if (scanStarted) null else "Bluetooth がオフになっています。オンにしてください。"
    }

    private fun onAirPodsSeen(s: AirPodsParser.Status, r: Int) {
        status = s
        rssi = r
        val now = System.currentTimeMillis()
        lastUpdate = now
        updateKnown(s, now)
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
                val devices = proxy.connectedDevices
                val names = devices.map { d: BluetoothDevice -> d.name ?: d.address }
                val target = devices.firstOrNull { it.name?.contains("AirPods", ignoreCase = true) == true }
                    ?: devices.firstOrNull()
                runOnUiThread {
                    connectedNames = names
                    if (target != null && !aap.isConnected) aap.connect(target)
                }
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

    // ---------------- 画面 ----------------

    private val Bg = Color(0xFFEEF0F6)
    private val CardBg = Color(0xFFF8F7FC)
    private val TileBg = Color(0xFFE9EAF1)
    private val Accent = Color(0xFF4A5B8C)
    private val Track = Color(0xFFDCDFE8)
    private val PillBg = Color(0xFFC9D5F7)
    private val TextMain = Color(0xFF1F2430)
    private val TextSub = Color(0xFF5C6170)

    /** 各パーツの最後にわかった残量と、その時刻 */
    private data class Known(val percent: Int, val time: Long)
    private var knownLeft by mutableStateOf<Known?>(null)
    private var knownRight by mutableStateOf<Known?>(null)
    private var knownCase by mutableStateOf<Known?>(null)

    private fun updateKnown(s: AirPodsParser.Status, now: Long) {
        s.leftPercent?.let { knownLeft = Known(it, now) }
        s.rightPercent?.let { knownRight = Known(it, now) }
        s.casePercent?.let { knownCase = Known(it, now) }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        )
    }

    @Composable
    private fun Screen() {
        // 「N分前」表示を更新するため 15 秒ごとに再描画
        var tick by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) { tick = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .verticalScroll(rememberScrollState())
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AirBattery", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextSub, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(PillBg.copy(alpha = 0.6f)).clickable { openAppSettings() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定", tint = TextMain, modifier = Modifier.size(24.dp))
                }
            }

            message?.let { msg ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFFFFDAD6)).padding(20.dp)) {
                    Column {
                        Text(msg, color = Color(0xFF410002))
                        if (requiredPermissions.any { !hasPermission(it) }) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { permissionLauncher.launch(requiredPermissions) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                                Text("権限を許可する")
                            }
                        }
                    }
                }
            }

            MainCard(tick)
            NoiseControl()
            LocationCard()
            DiagnosticsCard()

            Text(
                "※ 電池残量は AirPods が発信する Bluetooth 信号(Apple 非公開の形式)を解析して表示しています。" +
                    "10% 単位で、近くにある他人の AirPods を拾う可能性もあります。",
                fontSize = 12.sp, color = TextSub, modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(CardBg).padding(24.dp),
            content = content,
        )
    }

    @Composable
    private fun MainCard(now: Long) {
        val s = status
        val live = s != null && System.currentTimeMillis() - lastUpdate < 15_000L
        val connected = connectedNames.isNotEmpty() || aapConnected
        SectionCard {
            // 状態ピル
            val pill = when {
                live -> "検出中"
                connected -> "接続中"
                else -> "未接続"
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (live || connected) PillBg else TileBg)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (live || connected) Accent else TextSub))
                Spacer(Modifier.width(10.dp))
                Text(pill, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3A66))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                s?.model ?: connectedNames.firstOrNull() ?: "AirPods",
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextMain,
            )
            Spacer(Modifier.height(24.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BatteryRing("左", R.drawable.ic_bud_left, knownLeft, (live && s?.leftCharging == true) || (aapConnected && chargeLeft), Modifier.weight(1f))
                BatteryRing("右", R.drawable.ic_bud_right, knownRight, (live && s?.rightCharging == true) || (aapConnected && chargeRight), Modifier.weight(1f))
                BatteryRing("ケース", R.drawable.ic_case, knownCase, (live && s?.caseCharging == true) || (aapConnected && chargeCase), Modifier.weight(1f))
            }
            if (s == null && knownLeft == null && knownRight == null && knownCase == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "AirPods を探しています… ケースのふたを開けるか、耳に装着してください。",
                    fontSize = 13.sp, color = TextSub,
                )
            }
            Spacer(Modifier.height(24.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val ear = aapEar
                val wear = when {
                    ear != null -> when {
                        ear.first == 0 && ear.second == 0 -> "両耳を装着"
                        ear.first == 0 || ear.second == 0 -> "片耳を装着"
                        else -> "装着していない"
                    }
                    !live -> "--"
                    s!!.leftInEar && s.rightInEar -> "両耳を装着"
                    s.leftInEar -> "左耳のみ装着"
                    s.rightInEar -> "右耳のみ装着"
                    else -> "装着していない"
                }
                InfoTile("装着", wear, Modifier.weight(1f))
                InfoTile("音声の出力先", connectedNames.firstOrNull() ?: "なし", Modifier.weight(1f)) { refreshConnectedDevices() }
            }
        }
    }

    @Composable
    private fun NoiseControl() {
        val modeName = mapOf(1 to "オフ", 2 to "ノイズキャンセリング", 3 to "外部音取り込み", 4 to "適応型")
        Column {
            SectionTitle(
                "ノイズコントロール",
                if (!aapConnected) "AirPods と直接通信できたときだけ使えます（詳細は診断情報）"
                else "現在: " + (aapMode?.let { modeName[it] ?: "不明($it)" } ?: "不明"),
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(CardBg).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeTile("◉", "ノイズキャンセル", "周囲の音を抑える", 2, Modifier.weight(1f))
                    ModeTile("((•))", "外部音取り込み", "周りの音を聞く", 3, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeTile("✦", "適応型", "環境に合わせる", 4, Modifier.weight(1f))
                    ModeTile("⊘", "オフ", "音の調整をしない", 1, Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun ModeTile(icon: String, title: String, sub: String, mode: Int, modifier: Modifier) {
        val selected = aapConnected && aapMode == mode
        val enabled = aapConnected
        val fg = when {
            selected -> Color.White
            enabled -> TextMain
            else -> TextSub.copy(alpha = 0.6f)
        }
        Column(
            modifier.clip(RoundedCornerShape(28.dp)).background(if (selected) Accent else TileBg)
                .clickable(enabled = enabled) { aap.setListeningMode(mode) }
                .padding(20.dp),
        ) {
            Text(icon, fontSize = 22.sp, color = fg)
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = fg)
            Text(sub, fontSize = 13.sp, color = if (selected) Color.White.copy(alpha = 0.85f) else TextSub)
        }
    }

    @Composable
    private fun BatteryRing(label: String, icon: Int, known: Known?, charging: Boolean, modifier: Modifier) {
        val ageMs = known?.let { System.currentTimeMillis() - it.time }
        val stale = ageMs != null && ageMs > 30_000L
        val progress = (known?.percent ?: 0) / 100f
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                    val stroke = 9.dp.toPx()
                    drawArc(Track, 0f, 360f, false, style = Stroke(stroke))
                    if (progress > 0f) {
                        drawArc(
                            if (stale) Accent.copy(alpha = 0.45f) else Accent,
                            -90f, 360f * progress, false, style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Icon(painterResource(icon), contentDescription = label, tint = TextMain, modifier = Modifier.size(44.dp))
                if (charging) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp).size(26.dp).clip(CircleShape).background(Accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_bolt), contentDescription = "充電中", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(label, color = TextSub, fontSize = 15.sp)
            Text(known?.let { "${it.percent}%" } ?: "--", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMain)
            val sub = when {
                charging -> "充電中"
                stale -> "前回 · " + ago(ageMs!!)
                else -> ""
            }
            Text(sub, fontSize = 13.sp, color = TextSub)
        }
    }

    private fun ago(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "1分以内"
            min < 60 -> "${min}分前"
            else -> "${min / 60}時間前"
        }
    }

    @Composable
    private fun InfoTile(title: String, value: String, modifier: Modifier, onClick: (() -> Unit)? = null) {
        Column(
            modifier.clip(RoundedCornerShape(28.dp)).background(TileBg)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(title, fontSize = 13.sp, color = TextSub)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun SectionTitle(title: String, sub: String? = null) {
        Column(Modifier.padding(start = 8.dp, bottom = 12.dp)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextMain)
            sub?.let { Text(it, fontSize = 14.sp, color = TextSub) }
        }
    }

    @Composable
    private fun LocationCard() {
        Column {
            SectionTitle("位置", "AirPods 自体の現在地は Android からは取得できません")
            SectionCard {
                val r = rssi
                if (r != null && System.currentTimeMillis() - lastUpdate < 15_000L) {
                    val level = when {
                        r >= -45 -> "とても近い"
                        r >= -55 -> "近い"
                        else -> "やや離れている"
                    }
                    Text(level, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Text("電波の強さ $r dBm（正確な距離ではありません）", fontSize = 13.sp, color = TextSub)
                } else {
                    Text("近くで検出されていません", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
                }
                Spacer(Modifier.height(16.dp))
                val seen = lastSeen
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(TileBg).padding(20.dp)) {
                    Text("最後に検出した場所(スマホの位置)", fontSize = 13.sp, color = TextSub)
                    Spacer(Modifier.height(4.dp))
                    if (seen == null) {
                        Text("記録なし", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    } else {
                        Text(DateFormat.getDateTimeInstance().format(Date(seen.timeMillis)), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        if (seen.lat != null && seen.lon != null) {
                            Text("%.5f, %.5f".format(seen.lat, seen.lon) + (seen.accuracyM?.let { "（精度 約${it.toInt()}m）" } ?: ""), fontSize = 13.sp, color = TextSub)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { openMap(seen) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("地図で開く") }
                        } else {
                            Text("位置情報は取得できませんでした", fontSize = 13.sp, color = TextSub)
                        }
                    }
                }
            }
        }
    }

    private fun copyDiagnostics(top: List<AppleSample>) {
        val text = buildString {
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("aap=$aapState")
            appendLine("aapLast=$aapPacket")
            appendLine("all=$allCount apple=$appleCount airpods=$rawCount scanError=$scanError")
            appendLine("types=" + appleTypes.entries.sortedByDescending { it.value }.joinToString(",") { "%02X:%d".format(it.key, it.value) })
            appendLine("last0x07=$rawLastHex rssi=$rawLastRssi")
            top.forEach { appendLine("${it.rssi} ${it.hex}") }
        }
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("AirBattery 診断情報", text))
        Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun DiagnosticsCard() {
        var open by remember { mutableStateOf(true) }
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("診断情報", if (open) "タップで閉じる" else "タップで開く")
            }
            if (!open) return@Column
            SectionCard {
                val t = 13.sp
                Text(
                    "スマホの位置情報: " + when (locationEnabled) { true -> "オン"; false -> "オフ"; null -> "確認できません" },
                    fontSize = t, color = TextMain,
                )
                Text("AAP(直接通信): $aapState", fontSize = t, color = TextMain)
                Text("AAP 最後の受信: ${aapPacket ?: "--"}", fontSize = 11.sp, color = TextSub)
                TextButton(onClick = { aap.disconnect(); aapConnected = false; aapState = "再接続します…"; refreshConnectedDevices() }) {
                    Text("AAP を再接続")
                }
                Text("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL}", fontSize = t, color = TextMain)
                Text("権限: " + requiredPermissions.joinToString(" / ") { it.substringAfterLast('.') + "=" + (if (hasPermission(it)) "許可" else "未許可") }, fontSize = t, color = TextMain)
                Text("スキャン開始: " + (if (scanStarted) "成功" else "未開始") + (scanError?.let { "（失敗 エラーコード $it）" } ?: ""), fontSize = t, color = TextMain)
                Text("受信した信号(すべての機器): $allCount 件", fontSize = t, color = TextMain)
                Text("うち Apple の信号: $appleCount 件", fontSize = t, color = TextMain)
                Text(
                    "Apple の信号の種類(先頭バイト): " + appleTypes.entries.sortedByDescending { it.value }
                        .joinToString(", ") { "%02X=%d件".format(it.key, it.value) }.ifEmpty { "--" },
                    fontSize = t, color = TextMain,
                )
                Text("うち AirPods 形式(種類 0x07 を含む): $rawCount 件", fontSize = t, color = TextMain)
                Text("最後の信号の長さ: ${rawLastLen?.let { "$it バイト" } ?: "--"}（解析対象は ${AirPodsParser.DATA_LENGTH} バイト）", fontSize = t, color = TextMain)
                Text("最後の信号の電波の強さ: ${rawLastRssi?.let { "$it dBm" } ?: "--"}（表示対象は ${AirPodsScanner.MIN_RSSI} dBm 以上）", fontSize = t, color = TextMain)
                Text("最後の信号のデータ:", fontSize = t, color = TextMain)
                Text(rawLastHex ?: "--", fontSize = 11.sp, color = TextSub)
                Spacer(Modifier.height(12.dp))
                Text("電波の強い Apple の信号(直近15秒・上位8件):", fontSize = t, color = TextMain)
                val top = appleSamples.values.sortedByDescending { it.rssi }.take(8)
                if (top.isEmpty()) Text("--", fontSize = 11.sp, color = TextSub)
                top.forEach { Text("${it.rssi}dBm  ${it.hex}", fontSize = 11.sp, color = TextSub) }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { copyDiagnostics(top) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("診断情報をコピー") }
            }
        }
    }
}
