# 权限与第三方 SDK 清单

| 项 | 内容 |
|---|---|
| 应用名称 | StudyCloud |
| 应用包名 | `com.studycloud.tv` |
| 版本 | 0.1.0 |
| 最低系统版本 | Android 6.0（API 23） |
| 目标系统版本 | Android 15（API 35） |
| 更新日期 | 2026-11-12 |

> 本清单由 `app/src/main/AndroidManifest.xml` 与 `app/build.gradle.kts` 逐条核对生成。
> **改动权限或依赖后必须同步本文件**，应用商店会比对实际 APK 与提审清单。

---

## 1. 权限清单

### 1.1 必需权限

缺失会导致核心功能不可用。

| 权限 | 用途 | 申请时机 |
|---|---|---|
| `INTERNET` | 登录、同步题库与规则、上报学习记录 | 安装时授予 |
| `ACCESS_NETWORK_STATE` | 判断网络可用性，离线时转本地缓存，避免无谓重试 | 安装时授予 |
| `FOREGROUND_SERVICE`<br>`FOREGROUND_SERVICE_SPECIAL_USE` | 计时服务需在孩子看电视期间持续运行；设为前台服务是系统要求，否则会被后台清理 | 安装时授予 |
| `POST_NOTIFICATIONS` | 显示前台服务的常驻通知（系统强制要求前台服务可见），以及「剩余时长不足」提醒 | 首次启动计时服务时弹窗 |
| `RECEIVE_BOOT_COMPLETED` | 开机后自动恢复计时，防止重启绕过管控 | 安装时授予 |

**`FOREGROUND_SERVICE_SPECIAL_USE` 的用途说明**：manifest 中已声明
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "parental_control"`。提审时需提交的理由是：
家长设置的屏幕时间管控需要在孩子观看期间连续计时，无法用 `WorkManager` 的
周期任务替代——周期任务最小间隔 15 分钟，而管控精度要求到秒。

### 1.2 可选权限

**拒绝不影响启动**，仅导致管控能力降级。App 必须在拒绝后继续可用。

| 权限 | 用途 | 拒绝后的降级行为 |
|---|---|---|
| `SYSTEM_ALERT_WINDOW`<br>（显示在其他应用上层） | 时长用尽时在播放画面上覆盖答题提示 | 改为跳转到 App 内答题页。部分电视固件会把跳转视为切换应用，孩子可按返回键回到播放画面，**管控强度下降** |
| `PACKAGE_USAGE_STATS`<br>（使用情况访问） | 判断当前前台是不是视频应用，**只有在看视频时才扣减时长**——孩子在看课本 App 不应被计时 | 降级为「只要亮屏就计时」，计时偏严 |

### 1.3 关于「使用情况访问」的特别说明

这项权限敏感度高，单独说明边界：

- **读取什么**：系统返回的应用使用统计中，**最近一次使用的应用包名**
  （`GateService.currentForegroundPackage()`）。
- **怎么用**：仅在内存中与「受控应用」规则比对，决定本秒是否扣减时长。
- **不做什么**：**不上传、不落盘、不记入学习事件**。上报给服务器的事件只有
  `usage`（时长秒数）、`roundPassed`、`extraAdded`、`dayRollover`、`clockDrift`
  五种，**字段中不含任何应用包名**（见 `LocalStore.enqueueGateEvent()`）。
- **不生成**设备上其他应用的使用报表，不向家长展示孩子用了哪些 App。

### 1.4 不申请的权限

明确不申请，用于对照审核中的「最小必要」要求：

定位、摄像头、麦克风、通讯录、短信、通话记录、日历、
读写外部存储、读取手机状态（IMEI/IMSI）、读取已安装应用列表。

**设备标识不使用 IMEI、ANDROID_ID、MAC 或 OAID**，而是 App 首次启动时
随机生成的 UUID（`devices.device_key`），卸载重装即变更，无法跨应用关联。

---

## 2. 第三方 SDK 清单

本应用**不集成任何广告、统计分析、推送、崩溃上报、社交登录 SDK**。
以下全部为基础开发框架，均不独立收集个人信息、不向其提供方回传数据。

| SDK | 版本 | 提供方 | 用途 | 收集个人信息 | 许可证 |
|---|---|---|---|---|---|
| AndroidX Core KTX | 1.13.1 | Google | Android 基础 API 扩展 | 否 | Apache-2.0 |
| AndroidX AppCompat | 1.7.0 | Google | 界面兼容层 | 否 | Apache-2.0 |
| AndroidX Activity KTX | 1.9.3 | Google | 页面生命周期 | 否 | Apache-2.0 |
| AndroidX ConstraintLayout | 2.1.4 | Google | 界面布局 | 否 | Apache-2.0 |
| AndroidX Security Crypto | 1.1.0-alpha06 | Google | **本地加密存储登录凭据**（`EncryptedSharedPreferences`） | 否，仅本地加密 | Apache-2.0 |
| AndroidX WorkManager | 2.9.1 | Google | 后台同步任务调度 | 否 | Apache-2.0 |
| Material Components | 1.12.0 | Google | 界面组件 | 否 | Apache-2.0 |
| OkHttp | 4.12.0 | Square | HTTP 客户端，**仅与本产品自有服务器通信** | 否 | Apache-2.0 |
| Kotlin Coroutines | 1.9.0 | JetBrains | 异步编程 | 否 | Apache-2.0 |

### 2.1 数据接收方

本应用的网络请求**只发往一个地址**：家长在登录页填写的服务器地址
（本产品自有的 API 服务）。无任何其他域名的出站请求。

**唯一的例外**是家长在管理后台主动开启「AI 自动批改」后，
**由服务器**（非本 App）将题目与孩子的答案发送到家长自己配置的第三方服务。
该功能默认关闭，详见[隐私政策](./隐私政策.md)第 4 节。

---

## 3. 自启动与关联启动

| 行为 | 是否存在 | 说明 |
|---|---|---|
| 开机自启动 | **是** | 用于恢复计时，防止重启绕过管控；由家长在首次配置时知悉并同意 |
| 关联启动其他应用 | 否 | 不拉起任何第三方应用 |
| 被其他应用唤醒 | 否 | 未导出任何可供外部调用的组件（`LoginActivity` 的 `exported` 仅用于桌面图标） |
| 后台常驻 | **是** | 前台服务，有常驻通知，用户可见可关闭 |

---

## 4. 数据存储位置

| 数据 | 位置 | 加密 |
|---|---|---|
| 登录凭据（refresh token、设备 ID） | `EncryptedSharedPreferences` | **是**，AES-256，密钥在系统 Keystore |
| 用户密码 | **不存储** | 登录后只保留 refresh token |
| 题库与规则缓存 | App 私有目录 | 否，内容非个人信息 |
| 待上报事件队列 | App 私有目录 | 否 |

`android:allowBackup="false"`，不参与系统自动备份，避免凭据随备份外流。
