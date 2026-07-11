
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
Glaze Max、M2、M3、M3a、M8、M30、M88、MP10、MP12、MP16、R3、R3c

模块通过设备名称自动识别耳机类型，无需手动配置。

## 功能

| 功能 | OPPO | 咪帅 | 说明 |
|------|:----:|:----:|------|
| 电量显示 | ✅ | ✅ | 左耳、右耳、充电盒 |
| 降噪切换 | ✅ | ✅ | OPPO: 降噪/自适应/通透/关闭 |
|  |  |  | 咪帅: 降噪/抗风降噪/通透/关闭 |
| 大师调音 | ✅ | ✅ | OPPO: 至臻原音/高清解析/纯享人声/澎湃低音/丹拿特调 |
|  |  |  | 咪帅: HiFi/流行/摇滚/自适应 |
| 通知栏电量 | ✅ | ✅ | 系统通知栏常驻显示 |
| 超级岛 | ✅ | ✅ | 焦点岛弹窗显示电量 |
| 融合设备中心 | ✅ | ✅ | 系统蓝牙设置集成 |
| 双设备连接 | ✅ | - | OPPO 专有 |
| 自适应降噪 | ✅ | - | OPPO 专有 |
| 空间音频 | ✅ | - | OPPO 专有 |

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
├── RfcommController     ├── RfcommController        (OPPO RFCOMM)
│                        ├── MiShuaiRfcommController  (咪帅 SPP)
├── Packets.kt           ├── Packets.kt               (OPPO AA 帧)
│                        ├── MiShuaiPackets.kt         (咪帅 00 帧)
│                        ├── MiShuaiParser.kt          (咪帅响应解析)
│                        ├── DeviceType.kt             (自动识别)
├── HeadsetStateDisp.    ├── HeadsetStateDisp.         (双协议分发)
├── BTUpstreamHook       ├── BTUpstreamHook            (双控制器状态)
└── MiLinkServiceHook    └── MiLinkServiceHook         (双控制器状态)
```

## 致谢

- [OppoPods](https://github.com/1812z/OppoPods) by 1812z — 基础项目
- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 原始灵感
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件
- 咪帅协议文档基于 Frida 抓包 + 咪帅 App 反编译分析

## 许可证

GPL-3.0
