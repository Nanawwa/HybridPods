
<div align="center">

# HybridPods

**为 HyperOS 设备提供系统级 OPPO & 咪帅耳机控制**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS-orange?style=flat-square)](https://hyperos.mi.com)

**简体中文** | **[English](README_EN.md)**

</div>

基于 [OppoPods](https://github.com/1812z/OppoPods) 二次开发，在保留原有 OPPO 耳机支持的基础上，新增咪帅（MiShuai）蓝牙耳机的系统级集成。

## 支持设备

### OPPO / OnePlus 系列
OPPO Enco X3、Enco Free4、Enco Air5、Enco Air2 Pro 等

### 咪帅（MiShuai）系列
Glaze Max（型号32）、M3、M30、M88、MP10、MP12、M2、M3a、R3、MP16、R3c

模块通过设备名称自动识别耳机类型，无需手动配置。

## 功能

### 已实现

| 功能 | OPPO | 咪帅 | 说明 |
|------|:----:|:----:|------|
| 电量显示 | ✅ | ✅ | 左耳、右耳、充电盒 |
| 降噪切换 | ✅ | ✅ | 深度降噪 / 通透 / 关闭 |
| 通知栏电量 | ✅ | ✅ | 系统通知栏常驻显示 |
| 超级岛 | ✅ | ✅ | 焦点岛弹窗显示电量 |
| 融合设备中心 | ✅ | ✅ | 系统蓝牙设置集成 |
| 自适应降噪 | ✅ | - | OPPO 专有 |
| 空间音频 | ✅ | - | OPPO 专有 |
| 游戏模式 | ✅ | - | OPPO 通过 SPP 控制；咪帅为本地设置 |

### 待实现

- **抗风降噪** — 咪帅协议支持（0x00），但尚未在降噪切换循环中加入
- **大师调音（EQ）** — 咪帅支持 EQ 设置（协议 0x20），需对照源码完成三档音效映射
- **小米原生控制台** — 电量显示正常，降噪切换尚需适配
- **触控按键设置** — 查询已通，UI 待接入
- **游戏模式（咪帅）** — 本地编解码切换，不走 SPP，需反编译咪帅 App 分析实现

## 系统要求

- 小米设备，运行 **HyperOS**（Android 15+）
- **LSPosed** API 版本 >= 101
- 超级岛功能仅支持 HyperOS 3

## 安装

1. 安装 APK
2. 在 LSPosed 中启用模块，勾选推荐作用域：
   - `com.android.bluetooth`
   - `com.milink.service`
   - `com.xiaomi.bluetooth`
3. 软件右上角一键重启作用域
4. 通过蓝牙连接你的耳机

## 技术架构

```
OppoPods（原始）          HybridPods（本项目）
├── RfcommController     ├── RfcommController        (OPPO)
│   └── OPPO RFCOMM      ├── MiShuaiRfcommController  (咪帅 SPP)
├── Packets.kt           ├── Packets.kt               (OPPO AA 帧)
│                        ├── MiShuaiPackets.kt         (咪帅 00 帧)
│                        ├── MiShuaiParser.kt          (咪帅响应解析)
│                        ├── DeviceType.kt             (自动识别)
├── HeadsetStateDisp.    ├── HeadsetStateDisp.         (双协议分发)
├── BTUpstreamHook       ├── BTUpstreamHook            (双控制器状态)
└── MiLinkServiceHook    └── MiLinkServiceHook         (双控制器状态)
```

### 协议对比

| 方面 | OPPO | 咪帅 |
|------|------|------|
| 帧头 | `0xAA` | `0x00` |
| SPP UUID | `0000079A-D102-11E1-9B23-...` | `158627bc-0547-8787-87ba-...` |
| 电量查询 | Cmd 0x0106 | Query 0x01 |
| 降噪控制 | Cmd 0x0404 | Type 0x2C |
| 游戏模式 | Cmd 0x0403（SPP） | 本地设置（无 SPP） |

## 致谢

- [OppoPods](https://github.com/1812z/OppoPods) by 1812z — 原始项目
- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 原始灵感
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件
- 咪帅协议文档基于 Frida 抓包 + 咪帅 App 反编译

## 许可证

GPL-3.0
