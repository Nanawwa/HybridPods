package com.mishuaipods.tile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mishuaipods.pods.NoiseControlMode
import com.mishuaipods.pods.SppController

/**
 * 快速设置磁贴。连接实例在系统进程里，这里订阅状态广播刷新显示。
 */
class NoiseControlTile : TileService() {
    private var connected = false
    private var ancMode: NoiseControlMode = NoiseControlMode.NC_OFF

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SppController.ACTION_PODS_CONNECTION_STATE_CHANGED -> {
                    connected = intent.getStringExtra("state") == "connected"
                    updateTile()
                }
                SppController.ACTION_PODS_ANC_CHANGED -> {
                    ancMode = NoiseControlMode.fromIndex(intent.getIntExtra("status", 0))
                    updateTile()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            statusReceiver,
            IntentFilter().apply {
                addAction(SppController.ACTION_PODS_CONNECTION_STATE_CHANGED)
                addAction(SppController.ACTION_PODS_ANC_CHANGED)
            },
            Context.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        // 请求刷新状态
        sendBroadcast(Intent(SppController.ACTION_PODS_UI_INIT))
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        sendBroadcast(Intent(SppController.ACTION_CYCLE_ANC))
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return

        tile.label = when (ancMode) {
            NoiseControlMode.DEEP_ANC -> "深度降噪"
            NoiseControlMode.TRANSPARENCY -> "环境音"
            NoiseControlMode.WIND_NR -> "抗风降噪"
            NoiseControlMode.NC_OFF -> "降噪关"
        }
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = if (connected) "已连接" else "未连接"
        }
        tile.updateTile()
    }
}
