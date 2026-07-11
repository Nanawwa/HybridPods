# 咪帅 (MiShuai) 蓝牙耳机通讯协议 — 完整技术文档

> 给下一个 AI 的完整上下文。抓包数据优先级 > 源码分析。
> 基于 Frida 抓包 + 咪帅 App 反编译源码 + MiShuaiPods Xposed 模块开发实践。

---

## 一、硬件与环境

| 项目 | 值 |
|---|---|
| 耳机名称 | Mi shuai Glaze Max |
| 蓝牙地址 | `20:26:05:15:00:DB` |
| 连接方式 | **Bluetooth Classic SPP** (非 BLE GATT) |
| 控制 App 包名 | `com.mishuai.bt` |
| SPP UUID | `158627bc-0547-8787-87ba-435ad8571238` (自定义，非标准 SPP) |
| 手机系统 | 小米 HyperOS 3.0 (Android 15+) |
| Xposed 框架 | LSPosed |

---

## 二、SDP 服务发现

通过 Frida 扫描配对设备的 SDP 记录发现 4 个 UUID：

| UUID | 协议 |
|---|---|
| `0000110b` | A2DP (音频流) |
| `0000110e` | AVRCP (遥控) |
| `0000111e` | HFP (免提) |
| `158627bc-0547-8787-87ba-435ad8571238` | **咪帅控制通道 (SPP)** |

扫描代码 (Frida):
```javascript
Java.perform(function() {
    var adapter = Java.use("android.bluetooth.BluetoothAdapter").getDefaultAdapter();
    var device = adapter.getRemoteDevice("20:26:05:15:00:DB");
    device.fetchUuidsWithSdp();
    setTimeout(function() {
        var uuids = device.getUuids();
        for (var i = 0; i < uuids.length; i++) {
            console.log("[UUID] " + uuids[i].toString());
        }
    }, 8000);
});
```

---

## 三、传输层

### 3.1 连接方式

标准 RFCOMM SPP，通过 `BluetoothSocket` 连接：

```java
// BluetoothHelper.java — 咪帅 App 源码
BluetoothSocket socket = device.createRfcommSocketToServiceRecord(HDR_UUID);
socket.connect();
OutputStream outputStream = socket.getOutputStream();
InputStream inputStream = socket.getInputStream();
```

- 发送：`OutputStream.write(byte[])` + `flush()`
- 接收：`InputStream.read(byte[])` 轮询（50ms 间隔）

### 3.2 数据收发模型

咪帅 App 使用独立线程轮询 InputStream：

```
线程循环 {
    if (inputStream.available() > 0) {
        byte[] data = new byte[inputStream.available()];
        int len = inputStream.read(data);
        ReceiveEarphoneDataDetect(len, data);  // 解析响应
    } else {
        r_TimeOut++;
        if (r_TimeOut % 5 == 0) sendHeartbeat();  // 每 250ms 心跳
        if (r_TimeOut >= 20) disconnect();          // 1s 无响应断开
    }
    sleep(50);
}
```

### 3.3 命令队列机制

咪帅 App 采用**串行命令队列**，每次只发一条命令，等响应后才发下一条：

**优先级（高→低）**:
1. 降噪设置 (`NoiseControl = 0x2C`)
2. EQ 设置 (`SoundEffects = 0x20`)
3. 工作模式 (`WorkMode = 0x25`)
4. 音频协议 (`AudioProtocol = 0x2B`)
5. 语言 (`BlLanguage = 0x29`)
6. 提示音音量 (`BlAlertVolume = 0x32`)
7. 触控开关 (`BlTouchSwitch = 0x33`)
8. 查找器 (`BlFinder = 0x2A`)
9. ANC 模式开关 (`BlAncModeSwitch = 0x21`)
10. 触控按键设置 (`TouchSettings = 0x22`)
11. 重启 / 清配对 / 关机
12. 查询指令 (`GetBlInfo = 0x27`)

**超时重试**:
- 计数器 `r_CommOverCnt` 每次轮询 +1
- 到 8 时重发一次
- 最多重试 3 次，超过则跳过该命令

---

## 四、协议帧格式（抓包确认）

### 4.1 帧结构

所有帧固定 6 字节头 + 可变载荷：

```
[帧头1] [帧头2] [方向] 00 01 [载荷...]
```

- **帧头1**: `0x00` (固定)
- **帧头2**: 命令类型标识
- **方向**: `0x01` = 发送/查询，`0x02` = 响应

### 4.2 控制帧（降噪等设置）

```
发送: 00 [类型] 01 00 01 [值]
响应: 00 [类型] 02 00 [长度] [类型] [子类型] [数据...]
```

帧头第二字节含义：

| 字节值 | 功能 | 源码常量 |
|---|---|---|
| `0x2C` (44) | 降噪控制 | `NoiseControl` |
| `0x20` (32) | EQ 设置 | `SoundEffects` |
| `0x25` (37) | 工作模式 | `WorkMode` |
| `0x2B` (43) | 音频协议 | `AudioProtocol` |
| `0x29` (41) | 语言 | `BlLanguage` |
| `0x32` (50) | 提示音音量 | `BlAlertVolume` |
| `0x33` (51) | 触控开关 | `BlTouchSwitch` |
| `0x2A` (42) | 查找器 | `BlFinder` |
| `0x21` (33) | ANC 模式开关 | `BlAncModeSwitch` |
| `0x22` (34) | 触控按键设置 | `TouchSettings` |
| `0x23` (35) | 关机 | `BlPowerOff` |
| `0x24` (36) | 恢复出厂 | `BlReset` |
| `0x30` (47) | 清除配对 | `ClearPair` |

### 4.3 查询帧

```
发送: 00 27 01 00 01 [查询类型]
响应: 00 27 02 00 [载荷长度] [类型] [子类型] [数据...]
```

查询类型：

| 类型 | 功能 |
|---|---|
| `0x01` | 电量 |
| `0x02` | 固件版本 |
| `0x03` | 设备名 |
| `0x04` | EQ 模式 |
| `0x05` | 触控按键设置 |
| `0x07` | ANC 开关状态 |
| `0x08` | 工作模式 |
| `0x0A` | 语言 |
| `0x0B` | 音频协议 |
| `0x0C` | 降噪详细模式 |
| `0x13` | 提示音音量 |
| `0x14` | 查找器状态 |
| `0x16` | 触控开关状态 |
| `0xFF` | 心跳 / 全量查询 |

### 4.4 全量轮询链路

构造函数发送 `00 27 01 00 01 ff` 触发全量上报，之后按固定顺序逐项查询：

```
1 → 2 → 3 → 4 → 5 → 8 → 10 → 12 → 19 → 22 → 11 → 7 → 20
```

对应：电量 → 版本 → 名称 → EQ → 触控 → 工作模式 → 语言 → 降噪 → 触控音量 → 触控开关 → 音频协议 → ANC开关 → 查找器

---

## 五、已确认的响应数据解析

### 5.1 电量 (type=0x01)

```
抓包数据: 00 27 02 00 05 01 03 64 64 60
解析:
  [7] = 0x64 (100) → 左耳电量 100%
  [8] = 0x64 (100) → 右耳电量 100%
  [9] = 0x60 (96)  → 盒子电量 96%
```

注意：源码中电量字节的**最高位 (bit 7)** 表示充电状态：
- `& 0x80` = 是否在充电
- `& 0x7F` = 实际电量百分比

MiShuaiPods 中简化为直接取 `& 0xFF` 作为电量值。

### 5.2 设备名 (type=0x03)

```
抓包数据: 00 27 02 00 13 03 11 4D 69 73 68 75 61 69 20 47 6C 61 7A 65 20 4D 61 78
解析 (ASCII): "Mi shuai Glaze Max"
  [7..] = ASCII 字符
```

### 5.3 降噪状态 (type=0x04)

```
抓包数据: 00 27 02 00 04 04 02 00 00
解析:
  [7] = 0x00 → 当前模式 = Wind NR (抗风降噪)
```

### 5.4 降噪详细状态 (type=0x0C)

与 type=0x04 类似，返回更详细的降噪信息。

### 5.5 触控按键设置 (type=0x05)

```
32 字节长载荷，包含左右耳的单击/双击/三击/长按功能映射：
  [9]  = 左耳单击
  [12] = 右耳单击
  [15] = 左耳双击
  [18] = 右耳双击
  [21] = 左耳三击
  [24] = 右耳三击
  [27] = 左耳长按
  [30] = 右耳长按
```

按键功能值：

| 值 | 功能 |
|---|---|
| 0 | 无效果 |
| 1 | 接听电话 |
| 2 | 语音助手 |
| 3 | 上一曲 |
| 4 | 下一曲 |
| 5 | 音量+ |
| 6 | 音量- |
| 7 | 播放/暂停 |
| 8 | 游戏模式 |
| 9 | 降噪切换 |

---

## 六、降噪模式映射

### 6.1 协议层

| 字节 | 模式 | 英文 | 源码常量 |
|---|---|---|---|
| `0x00` | 抗风降噪 | Wind NR | `WindNoise` |
| `0x01` | 深度降噪 | Deep ANC | `ReduceNoise` |
| `0x02` | 环境音 | Transparency | `TransNoise` |
| `0x03` | 降噪关 | NC Off | `NormalNoise` |

**重要**: 0x00 和 0x03 的含义在实测中被确认与源码常量名一致：
- `0x00` = 抗风降噪 (不是"降噪关")
- `0x03` = 降噪关 (不是"抗风降噪")

### 6.2 HyperOS 映射 (MiShuaiPods)

MiShuaiPods 将协议模式映射为 HyperOS 的 ANC 状态值：

| 协议模式 | HyperOS ANC 状态 |
|---|---|
| 0x00 (Wind NR) | 1 (Off) |
| 0x01 (Deep ANC) | 2 (ANC) |
| 0x02 (Transparency) | 3 (Transparency) |
| 0x03 (NC Off) | 1 (Off) |

---

## 七、游戏模式

**抓包结论**: 切换游戏模式时**没有捕获到 SPP 写操作**。

源码中虽然存在 `WorkMode = 0x25` 的设置帧头，但在实际抓包中从未被触发。游戏模式是**纯本地设置**，可能修改的是蓝牙编解码器参数，不通过 SPP 发送指令给耳机。

---

## 八、多型号支持

咪帅 App 通过 `SpiManagerFactory` 根据设备型号编号创建不同的 Spi 实现类：

| 型号编号 | 实现类 | 耳机型号 |
|---|---|---|
| 32 | `Spi` | 默认 (如 Glaze Max) |
| 33 | `SpiM3` | M3 |
| 34 | `SpiM30` | M30 |
| 35 | `SpiM88` | M88 |
| 36, 43 | `SpiMP10` | MP10 |
| 37 | `SpiMP12` | MP12 |
| 38 | `SpiM2` | M2 |
| 39 | `SpiM3a` | M3a |
| 40 | `SpiR3` | R3 |
| 41 | `SpiMP16` | MP16 |
| 42 | `SpiR3c` | R3c |

每个型号的查询链路（`setNextGetCom`）和响应解析（`ContinueGetEarphoneInfo`）可能有差异，但基础帧格式一致。

---

## 九、Frida 抓包方法

### 9.1 抓发送指令

```javascript
Java.perform(function(){
    var OS = Java.use("java.io.OutputStream");
    OS.write.overloads.forEach(function(over){
        over.implementation = function(){
            var d = arguments[0];
            if(d && d.length>=2 && d[0]==0x00 && d[1]==0x2C){
                var hex="";for(var i=0;i<d.length;i++)hex+=("0"+(d[i]&0xFF).toString(16)).slice(-2)+" ";
                console.log("[CMD] "+hex.trim());
            }
            return over.apply(this,arguments);
        };
    });
});
```

### 9.2 抓响应数据

```javascript
Java.perform(function(){
    var SpiA = Java.use("com.mishuai.bt.Headphone.Spi.SpiAbstract");
    SpiA.ReceiveEarphoneDataDetect.overloads.forEach(function(over){
        over.implementation = function(){
            var args=[];
            for(var i=0;i<arguments.length;i++){
                var a=arguments[i];
                if(typeof a==="number") args.push(a);
            }
            console.log("[RSP] "+args.join(","));
            return over.apply(this,arguments);
        };
    });
    var Spi = Java.use("com.mishuai.bt.Headphone.Spi.Spi");
    Spi.SendSetBlData.overloads.forEach(function(over){
        over.implementation = function(){
            var d=arguments[0];var hex="";
            if(d)for(var i=0;i<d.length;i++)hex+=("0"+(d[i]&0xFF).toString(16)).slice(-2)+" ";
            console.log("[SEND] "+hex.trim());
            return over.apply(this,arguments);
        };
    });
});
```

### 9.3 启动方式

```bash
# 推送 frida-server 到手机
adb push frida-server /data/local/tmp/fs
adb push frida-inject /data/local/tmp/fi
adb shell chmod 755 /data/local/tmp/fs /data/local/tmp/fi

# 启动 frida-server
adb shell
su -c /data/local/tmp/fs &

# 注入 hook
PID=$(pidof com.mishuai.bt)
su -c /data/local/tmp/fi -p $PID -s /data/local/tmp/hook.js
```

---

## 十、MiShuaiPods Xposed 模块实现

### 10.1 架构

```
com.mishuaipods/
├── pods/                          ← 协议层（独立于咪帅App）
│   ├── MiShuaiProtocol.kt         ← 帧构建 (00 2C / 00 27)
│   ├── MiShuaiParser.kt           ← 响应解析
│   ├── SppController.kt           ← 独立 SPP 连接 + 收发
│   ├── MiShuaiDeviceDetector.kt   ← 设备发现 + SDP 连接
│   ├── NoiseControlMode.kt        ← 降噪模式枚举
│   ├── DeviceCapabilities.kt      ← 设备能力
│   ├── DeviceInfo.kt              ← 设备信息
│   └── SpiGeneration.kt           ← Spi 版本
├── hook/                          ← Xposed Hook 层
│   ├── HookEntry.kt               ← 模块入口
│   ├── app/MiShuaiSppHook.kt      ← Hook com.mishuai.bt 的 Spi 类
│   ├── milink/MiLinkServiceHook.kt ← Hook HyperOS 融合设备中心
│   ├── HeadsetStateDispatcher.kt  ← Hook com.android.bluetooth
│   ├── FocusIslandHook.kt         ← Hook 焦点岛
│   ├── SettingsHeadsetHook.kt     ← Hook 系统设置页
│   └── BluetoothUpstreamHeadsetHook.kt ← 蓝牙上游 Hook
├── ui/                            ← 界面层
├── service/ControlService.kt      ← 前台服务 + 通知栏
└── tile/NoiseControlTile.kt       ← 快捷设置磁贴
```

### 10.2 独立 SPP 连接

`SppController` 完全绕过咪帅 App，直接与耳机通信：

```kotlin
// MiShuaiDeviceDetector.kt
fun connectSpp(device: BluetoothDevice): BluetoothSocket? {
    // Step 1: SDP 发现 UUID
    device.fetchUuidsWithSdp()
    Thread.sleep(2000)
    val uuids = device.uuids
    // 尝试非音频 Profile 的 UUID

    // Step 2: 降级到已知 UUID
    val SPP_UUID = UUID.fromString("158627bc-0547-8787-87ba-435ad8571238")
    val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
    socket.connect()
    return socket
}
```

### 10.3 帧构建

```kotlin
// MiShuaiProtocol.kt
object MiShuaiProtocol {
    private val CMD_TEMPLATE = byteArrayOf(0x00, 0x2C, 0x01, 0x00, 0x01, 0x00)
    private val QUERY_TEMPLATE = byteArrayOf(0x00, 0x27, 0x01, 0x00, 0x01, 0x00)

    fun buildCommand(mode: Byte): ByteArray = CMD_TEMPLATE.clone().also { it[5] = mode }
    fun buildQuery(type: Byte): ByteArray = QUERY_TEMPLATE.clone().also { it[5] = type }
}
```

### 10.4 响应解析

```kotlin
// SppController.kt
private fun handleResponse(data: ByteArray) {
    if (data.size < 7) return
    if (data[0] != 0x00.toByte() || data[1] != 0x27.toByte()) return
    if (data[2] != 0x02.toByte()) return

    val type = data[5].toInt() and 0xFF
    when (type) {
        0x01 -> parseBatteryResponse(data)   // 电量
        0x03 -> parseNameResponse(data)      // 设备名
        0x04 -> parseNoiseStatusResponse(data) // 降噪状态
        0x07 -> parseVolumeResponse(data)    // 音量
    }
}

private fun parseBatteryResponse(data: ByteArray) {
    val left = (data[7].toInt() and 0xFF).coerceIn(0, 100)
    val right = (data[8].toInt() and 0xFF).coerceIn(0, 100)
    val case = (data[9].toInt() and 0xFF).coerceIn(0, 100)
    currentBattery = BatteryResult(left, right, case)
}
```

### 10.5 Hook 层

Hook 咪帅 App 的关键类来监听通信：

```kotlin
// MiShuaiSppHook.kt
// Hook 响应接收
module.hook(clazz.getDeclaredMethod("ReceiveEarphoneDataDetect", IntArray::class.java))
    .intercept { chain ->
        // 拦截并记录响应数据
        chain.proceed()
    }

// Hook 命令发送
module.hook(clazz.getDeclaredMethod("SendSetBlData", ByteArray::class.java))
    .intercept { chain ->
        // 拦截并记录发送数据
        chain.proceed()
    }
```

### 10.6 电量轮询

SppController 每 30 秒自动查询一次电量：

```kotlin
private fun startBatteryPolling() {
    batteryPollJob = CoroutineScope(Dispatchers.IO).launch {
        while (isConnected) {
            delay(30_000L)
            sendPacketSafe(MiShuaiProtocol.buildQuery(MiShuaiProtocol.QUERY_BATTERY))
        }
    }
}
```

---

## 十一、与 OppoPods 的对比

| 方面 | OppoPods | MiShuaiPods |
|---|---|---|
| 目标耳机 | OPPO/OnePlus | 咪帅 |
| 协议格式 | RFCOMM (AA 帧) | SPP (00 2C / 00 27 帧) |
| Hook 范围 | 4 个系统包 | com.mishuai.bt + 4 个系统包 |
| 降噪模式 | 4 种 (关闭/降噪/自适应/通透) | 4 种 (关闭/降噪/通透/风噪) |
| 电量查询 | Cmd 0x0106 | Query 0x01 |
| 游戏模式 | 有 (Cmd 0x0403) | 无 (本地设置) |
| Quick Settings | 有 | 有 |
| 独立模式 | 有 (AppRfcommController) | 有 (SppController) |
| 焦点岛 | 有 | 需真机测试 |

---

## 十二、Xposed 作用域

MiShuaiPods 需要 hook 5 个包：

| 包名 | 用途 |
|---|---|
| `com.mishuai.bt` | 核心协议 (Spi/SpiAbstract) |
| `com.android.bluetooth` | A2DP 连接检测 |
| `com.milink.service` | HyperOS 融合设备中心 |
| `com.xiaomi.bluetooth` | 焦点岛 + 通知 |
| `com.android.settings` | 系统设置页 |

---

## 十三、已知问题与待验证

| 优先级 | 事项 | 说明 |
|---|---|---|
| P0 | 融合设备中心验证 | hook 系统蓝牙类名需真机确认 |
| P0 | 焦点岛弹窗验证 | Focus Island API 需真机测试 |
| P0 | 独立模式验证 | SppController 连接测试 |
| P1 | 游戏模式 | 反编译 APK 搜索实现（可能是本地编解码切换） |
| P1 | 通知栏图标优化 | 使用系统小米耳机图标资源 |
| P2 | 多设备支持 | 当前只支持单个耳机设备 |

---

## 十四、关键发现清单

1. **传输层是 SPP**，不是 BLE GATT — 这是最关键的发现
2. **自定义 UUID** `158627bc-0547-8787-87ba-435ad8571238` 是控制通道
3. **帧格式固定 6 字节头**：`[帧头1] [帧头2] [方向] 00 01 [载荷]`
4. **方向字节**：`01` = 发送/查询，`02` = 响应
5. **帧头 0x2C** = 控制（降噪等），**0x27** = 查询/设置
6. **游戏模式不走 SPP**，是本地编解码切换
7. **命令串行执行**，有超时重试机制（8次轮询超时重发，3次重发失败跳过）
8. 多型号耳机使用不同 Spi 实现类，但协议框架一致
9. 电量字节最高位是充电状态，低 7 位是百分比
10. 心跳间隔约 250ms（每 5 次 50ms 轮询），断连阈值约 1s（20 次轮询）
