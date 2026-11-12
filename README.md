# StudyCloud Android

电视端客户端。孩子看电视，看够设定时长就弹题；答对规定数量并达到正确率，奖励可继续观看的时长。

## 相关仓库

| 仓库 | 内容 |
|---|---|
| [studycloud-admin-api](https://github.com/qq151279853/studycloud-admin-api) | 后端 API · **架构文档也在那里** |
| [studycloud-admin-ui](https://github.com/qq151279853/studycloud-admin-ui) | 家长管理后台 |
| [studycloud](https://github.com/qq151279853/studycloud) | **本仓库** · Android 客户端 |

另见 [清洁室开发约定](./CLEANROOM.md)——**必读**。

## 合规材料

本产品处理未成年人的学习数据，法律文本与披露清单在 [`docs/合规/`](./docs/合规/)：

| 文件 | 用途 |
|---|---|
| [隐私政策](./docs/合规/隐私政策.md) | 对用户公示，首启需同意 |
| [儿童个人信息保护规则](./docs/合规/儿童个人信息保护规则.md) | 单独成文，监护人单独同意 |
| [权限与第三方 SDK 清单](./docs/合规/权限与第三方SDK清单.md) | 提审材料，**与 manifest 逐条对应** |
| [用户服务协议](./docs/合规/用户服务协议.md) | 注册时同意 |
| [开源软件声明](./docs/合规/开源软件声明.md) | 许可证署名义务 |
| [上线合规检查清单](./docs/合规/上线合规检查清单.md) | **发布门禁** |

> **改权限或改依赖，必须同步 `docs/合规/`。** 应用商店会比对实际 APK 与提审清单，
> 对不上直接驳回。各文本均为草案，未经法务审阅。

## 模块

| 模块 | 内容 | 状态 |
|---|---|---|
| `core-gate` | **时长账本与弹题状态机**、同步计划器。纯 Kotlin / JVM，不依赖 Android 框架 | 已实现，19 项测试通过 |
| `app` | 登录页、答题页、管控前台服务、凭据存储、API 客户端、本地库、同步 Worker | 已接入构建，`assembleDebug` 可出 APK |

## 为什么核心逻辑是纯 JVM 模块

账本与状态机是整个产品里最容易出错、也最该被测试锁住的部分（重复结算、休眠唤醒扣光余额、改系统时间作弊）。
把它们放在不依赖 Android 框架的模块里，就能在 CI 上用普通 JVM 测试跑《开发文档》6.11 的 T1–T12，
不需要模拟器。

## 构建与测试

```bash
cd apps/android
./gradlew :core-gate:test      # 账本与状态机，19 项，纯 JVM
./gradlew :app:assembleDebug   # 产出 app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` **必须用正斜杠**（`C:/Users/...`）：
.properties 文件会把单反斜杠当转义符吃掉，报「filename, directory name, or volume label syntax is incorrect」。

debug 构建的 API 地址在 `app/build.gradle.kts` 的 `buildConfigField` 里，
默认 `http://10.0.2.2:5080`（模拟器访问宿主机）；真机改成局域网 IP。

本机首次构建需要下载 Kotlin 编译器构件。若 Kotlin daemon 启动卡住，
`gradle.properties` 里已设 `kotlin.compiler.execution.strategy=in-process` 绕开。

## 三条不能破的实现原则

1. **余额的权威在设备，不在服务器。** 服务端只下发规则和题库；断网时孩子既不能被永久锁死，也不能永久解锁。
2. **计时用单调时钟**（`SystemClock.elapsedRealtime()`），不用墙上时钟——孩子会去改系统时间。
   单次 tick 超过 120 秒直接丢弃，否则休眠唤醒会一次扣光余额。
3. **奖励结算必须幂等。** 每组任务有唯一 `roundId`，重复提交不重复加时。

## 运行时结构

```
LoginActivity   登录并绑定设备，启动 SyncWorker 与 GateService
    ↓
GateService     前台服务，每秒驱动 GateEngine
    ├─ 计时：亮屏 + 受控应用前台时扣减余额
    ├─ 余额归零 → 暂停播放、请求音频焦点、拉起答题页
    └─ 事件入本地队列，由 SyncWorker 批量上报
    ↓
QuizActivity    答题页，纯遥控器可完成；主观题进待批队列
```

## 尚未完成

- **真机验证（三款电视 + 两款平板）** —— 这是 2.9 验收标准的硬要求
- 厂商后台限制、开机自启、遥控器待机清后台的实机确认
- 间隔重复（SM-2）抽题调度：接口位置已留在 `QuestionPicker`
- **首启隐私弹窗与「关于」页** —— 法规要求，见[合规检查清单](./docs/合规/上线合规检查清单.md) §1

代码全部编译通过、lint 零错误，但**管控行为的正确性只能在真实电视上确认**：
悬浮窗、使用情况访问、音频焦点在各厂商固件上的表现差异很大。
