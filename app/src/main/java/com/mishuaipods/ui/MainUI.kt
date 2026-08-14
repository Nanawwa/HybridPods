package com.mishuaipods.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mishuaipods.R
import com.mishuaipods.pods.MiShuaiDeviceDetector
import com.mishuaipods.pods.MiShuaiProtocol
import com.mishuaipods.pods.NoiseControlMode
import com.mishuaipods.pods.SppController

private fun sendAction(context: Context, action: String, fill: (Intent.() -> Unit)? = null) {
    // 不设包限制：连接实例可能在系统进程里，接收端有签名权限校验
    val intent = Intent(action)
    fill?.let { intent.it() }
    context.sendBroadcast(intent)
}

@Composable
fun MainUI() {
    val context = LocalContext.current
    var connectionState by remember { mutableStateOf("disconnected") }
    var batteryLeft by remember { mutableIntStateOf(0) }
    var batteryRight by remember { mutableIntStateOf(0) }
    var batteryCase by remember { mutableIntStateOf(0) }
    var ancMode by remember { mutableStateOf(NoiseControlMode.NC_OFF) }
    var workMode by remember { mutableIntStateOf(0) }
    var deviceName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // 拉取一次当前状态
        sendAction(context, SppController.ACTION_PODS_UI_INIT)
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    SppController.ACTION_PODS_CONNECTION_STATE_CHANGED -> {
                        connectionState = intent.getStringExtra("state") ?: "disconnected"
                        deviceName = intent.getStringExtra("device_name") ?: ""
                    }
                    SppController.ACTION_PODS_BATTERY_CHANGED -> {
                        batteryLeft = intent.getIntExtra("left_battery", 0)
                        batteryRight = intent.getIntExtra("right_battery", 0)
                        batteryCase = intent.getIntExtra("case_battery", 0)
                    }
                    SppController.ACTION_PODS_ANC_CHANGED -> {
                        ancMode = NoiseControlMode.fromIndex(intent.getIntExtra("status", 0))
                    }
                    SppController.ACTION_PODS_WORK_MODE_CHANGED -> {
                        workMode = intent.getIntExtra("mode", 0)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(SppController.ACTION_PODS_CONNECTION_STATE_CHANGED)
            addAction(SppController.ACTION_PODS_BATTERY_CHANGED)
            addAction(SppController.ACTION_PODS_ANC_CHANGED)
            addAction(SppController.ACTION_PODS_WORK_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Device name
        Text(deviceName.ifEmpty { "MiShuai" }, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp))

        // Connection status card
        ConnectionStatusCard(connectionState)
        Spacer(modifier = Modifier.height(24.dp))

        // Battery section
        BatterySection(batteryLeft, batteryRight, batteryCase, connectionState)
        Spacer(modifier = Modifier.height(24.dp))

        // ANC control
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("降噪控制", fontSize = 18.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp))

                AncModeSelector(ancMode, connectionState) { mode ->
                    sendAction(context, SppController.ACTION_ANC_SELECT) {
                        putExtra("status", mode.ordinal)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { sendAction(context, SppController.ACTION_CYCLE_ANC) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("切换降噪模式") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Work mode section
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("工作模式", fontSize = 18.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = workMode == MiShuaiProtocol.WORK_MUSIC.toInt(),
                        onClick = {
                            sendAction(context, SppController.ACTION_GAME_MODE_SET) {
                                putExtra("enabled", false)
                            }
                        },
                        label = { Text("音乐模式") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = workMode == MiShuaiProtocol.WORK_GAME.toInt(),
                        onClick = {
                            sendAction(context, SppController.ACTION_GAME_MODE_SET) {
                                putExtra("enabled", true)
                            }
                        },
                        label = { Text("游戏模式") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Refresh and connect buttons
        OutlinedButton(
            onClick = { sendAction(context, SppController.ACTION_REFRESH_STATUS) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("刷新状态") }

        Spacer(modifier = Modifier.height(8.dp))

        if (connectionState == "disconnected") {
            Button(
                onClick = {
                    try {
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return@Button
                        val device = MiShuaiDeviceDetector.findPaired(adapter)
                        if (device != null) {
                            SppController.connectPod(context, device)
                        }
                    } catch (e: SecurityException) { }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("直连耳机") }
        }
    }
}

@Composable
fun ConnectionStatusCard(state: String) {
    val (statusColor, statusBg, statusText) = when (state) {
        "connected" -> Triple(Color(0xFF36D167), Color(0xFFDFFAE4), "已连接")
        "connecting" -> Triple(Color(0xFFFF9F0A), Color(0xFFFFF0D7), "连接中...")
        else -> Triple(Color(0xFFFF5A52), Color(0xFFFFE5E3), "未连接")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = statusBg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(statusColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_anc),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(statusText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = statusColor)
                if (state == "connecting") {
                    Text("正在连接耳机...", fontSize = 13.sp, color = statusColor.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
fun BatterySection(left: Int, right: Int, case: Int, state: String) {
    if (state != "connected") {
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.LightGray.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) { Text("耳机未连接", color = Color.Gray) }
        return
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("电量", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))
            BatteryRow("左耳", left)
            BatteryRow("右耳", right)
            BatteryRow("充电盒", case)
        }
    }
}

@Composable
fun BatteryRow(label: String, level: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(60.dp), fontSize = 14.sp)
        LinearProgressIndicator(
            progress = { level / 100f },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = when { level > 60 -> Color(0xFF4CAF50); level > 20 -> Color(0xFFFFC107); else -> Color(0xFFF44336) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("$level%", fontSize = 14.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun AncModeSelector(currentMode: NoiseControlMode, state: String, onModeSelected: (NoiseControlMode) -> Unit) {
    val modes = listOf(NoiseControlMode.DEEP_ANC, NoiseControlMode.TRANSPARENCY, NoiseControlMode.WIND_NR, NoiseControlMode.NC_OFF)
    val icons = listOf(R.drawable.ic_anc, R.drawable.ic_transparency, R.drawable.ic_wind, R.drawable.ic_off)
    val labels = listOf("深度降噪", "环境音", "抗风降噪", "降噪关")

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        modes.forEachIndexed { index, mode ->
            val isSelected = currentMode == mode
            val animColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, label = "c")
            val txtColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "t")

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(animColor)
                    .clickable { onModeSelected(mode) }
                    .padding(12.dp).width(72.dp)
            ) {
                Icon(painterResource(icons[index]), labels[index], tint = txtColor, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(4.dp))
                Text(labels[index], color = txtColor, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
            }
        }
    }
}
