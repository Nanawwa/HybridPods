package com.mishuaipods.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun PopupContent() {
    val context = LocalContext.current
    var connectionState by remember { mutableStateOf("disconnected") }
    var batteryLeft by remember { mutableIntStateOf(0) }
    var batteryRight by remember { mutableIntStateOf(0) }
    var batteryCase by remember { mutableIntStateOf(0) }
    var ancMode by remember { mutableStateOf(NoiseControlMode.NC_OFF) }
    var workMode by remember { mutableIntStateOf(0) }
    var deviceName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
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
        onDispose {
            context.unregisterReceiver(receiver)
            sendAction(context, SppController.ACTION_PODS_UI_CLOSED)
        }
    }

    Card(shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(deviceName.ifEmpty { "MiShuai" }, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(8.dp))
            ConnectionStatusCard(connectionState)

            if (connectionState == "connected") {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BatteryItem("左耳", batteryLeft)
                    BatteryItem("右耳", batteryRight)
                    BatteryItem("充电盒", batteryCase)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("降噪: ${ancMode.label}", fontSize = 14.sp)
                Text("模式: ${if (workMode == MiShuaiProtocol.WORK_GAME.toInt()) "游戏" else "音乐"}", fontSize = 14.sp)

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NoiseControlMode.entries.forEach { mode ->
                        FilterChip(
                            selected = ancMode == mode,
                            onClick = {
                                sendAction(context, SppController.ACTION_ANC_SELECT) {
                                    putExtra("status", mode.ordinal)
                                }
                            },
                            label = { Text(mode.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BatteryItem(label: String, level: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text("$level%", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
