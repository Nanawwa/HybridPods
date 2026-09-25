# 由于不幸丢失耳机 已不再使用咪帅耳机 故停止维护
# MiShuaiPods

为小米 HyperOS 设备提供系统级咪帅耳机控制的 Xposed 模块（LSPosed / libxposed API）。

## 功能

| 功能 | 状态 | 说明 |
|---|---|---|
| 降噪控制 | ✅ | 关闭 / 深度降噪 / 环境音 / 抗风降噪 |
| 电量显示 | ✅ | 左耳 / 右耳 / 盒子电量（含充电标志解析） |
| 快捷弹窗 | ✅ | 浮动窗口显示电量 + 降噪切换 |
| 快捷设置磁贴 | ✅ | 下拉面板一键循环切换降噪 |
| 独立连接 | ✅ | 不依赖咪帅 App，A2DP 连接后自动建立 SPP |
| 系统电量集成 | ⚠️ | 状态栏/融合设备中心电量（需真机验证） |
| 焦点岛弹窗 | ⚠️ | 未实现 |

## 协议

```
传输层: SPP 蓝牙串口 (BluetoothSocket → OutputStream)
发送帧: 00 2C 01 00 01 [模式字节]
查询帧: 00 27 01 00 01 [类型字节]
响应帧: 00 27 02 00 [载荷长度] [类型] [子类型] [数据...]

模式字节:
  00 = 抗风降噪 (Wind NR)
  01 = 深度降噪 (Deep ANC)
  02 = 环境音 (Transparency)
  03 = 降噪关 (NC Off)

查询类型:
  01 = 电量 (响应: 左耳, 右耳, 盒子)
  03 = 设备名 (响应: ASCII 字符串)
  07 = 降噪状态
```

SPP UUID: `158627bc-0547-8787-87ba-435ad8571238`

## 架构

```
com.mishuaipods/
├── hook/                          ← Xposed Hook 层
│   ├── HookEntry.kt               ← 模块入口 (extends XposedModule)
│   ├── HeadsetStateDispatcher.kt  ← com.android.bluetooth (A2DP 连接检测)
│   ├── BluetoothUpstreamHeadsetHook.kt ← 系统电量/ANC 上报
│   ├── MiBluetoothToastHook.kt    ← com.xiaomi.bluetooth
│   ├── MiLinkServiceHook.kt       ← com.milink.service (融合设备中心)
│   ├── SettingsHeadsetHook.kt     ← com.android.settings
│   └── app/MiShuaiSppHook.kt      ← com.mishuai.bt (SPI 日志)
├── pods/                          ← 协议层
│   ├── MiShuaiProtocol.kt         ← 帧构建
│   ├── MiShuaiDeviceDetector.kt   ← 设备发现 / SPP 连接
│   └── SppController.kt           ← 连接控制 / 分帧解析 / 状态广播
├── ui/                            ← Compose 界面
│   ├── MainActivity.kt / MainUI.kt
│   └── PopupActivity.kt / PopupContent.kt
└── tile/
    └── NoiseControlTile.kt       ← 快捷设置磁贴
```

## 构建

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

要求: Android SDK (compileSdk 36) / AGP 9.0.0 / Gradle 9.x（项目已配置阿里云镜像）。

## 安装

1. 安装 APK
2. LSPosed → 模块 → 启用 MiShuaiPods
3. 作用域:
   - `com.mishuai.bt`
   - `com.android.bluetooth`
   - `com.milink.service`
   - `com.xiaomi.bluetooth`
   - `com.android.settings`
4. 重启手机
5. 耳机通过 A2DP 连接后自动建立 SPP 控制通道；也可在 App 内点击"直连耳机"

## 调试

```bash
adb logcat -s MiShuaiPods
adb logcat | grep -i mishuai
```
