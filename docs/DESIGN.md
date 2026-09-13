# FadeBreak 设计文档

- 版本:v0.1(评审稿)
- 目标设备:荣耀 400(MagicOS 10 / Android 16)
- 用途:个人自用开发,完成后开源分享到 GitHub(源码 + Release APK),不上架应用商店
- 兼容目标:Android 8.0+(`minSdk 26`)及以上,多厂商 ROM
- 参考产品:FadeTop(Windows,http://fadetop.com)

---

## 0. 设计原理:20-20-20 护眼原则

本应用的核心设计依据是眼科界广泛推荐的 **20-20-20 原则**(美国眼科学会 AAO 等机构推荐用于缓解数字眼疲劳):

> **每连续用眼 20 分钟,就抬头看向约 20 英尺(约 6 米)以外的物体,持续至少 20 秒。**

**为什么有效**:注视近处屏幕时,睫状肌持续收缩以维持调节;同时眨眼频率会从正常约 15 次/分降到 5~7 次/分,泪膜快速蒸发。于是出现眼干、酸胀、视物模糊、流泪、头痛等「数字眼疲劳」症状。远眺可让睫状肌放松,抬离屏幕也能自然恢复眨眼——这正是三个「20」分别对应 **间隔 / 距离 / 时长** 的原因。

> 说明:屏幕用眼不会造成不可逆的器质性损伤,20-20-20 属于**行为习惯层面的缓解建议**,非医疗诊断或治疗。

**本项目的映射**:

| 20-20-20 原则 | FadeBreak 中的实现 |
|---|---|
| 每 20 分钟 | 休息间隔 `timeoutMs`(默认 20 分钟,可调) |
| 看向 20 英尺(约 6 米)外 | 全屏渐变遮罩,迫使用眼离开近处屏幕、望向远处 |
| 持续 20 秒 | 护眼时长 `minBreakMs`(默认 20 秒),停留达到该时长才算一次有效护眼 |

换言之,本应用把一条抽象的护眼习惯变成「到点自动淡出屏幕」的强制执行:遮罩出现的瞬间眼睛自然移开屏幕,只要保持 20 秒即完成一次符合 20-20-20 的休息。

---

## 1. 目标与范围

### 1.1 目标
在 Android 上复刻 FadeTop 的核心体验:**在你连续使用手机过久、且暂时不忙时,用渐变遮罩提醒你休息**。

### 1.2 范围内(第一期 / MVP)
- 连续使用时长检测(基于屏幕点亮时长)
- “不忙时才触发”的时机判断(空闲阈值)
- 悬浮遮罩渐隐(alpha 动画)
- 遮罩 UI:背景(纯色/图片)+ 关闭提示
- 点击遮罩关闭
- 休息结算与冷却
- 基础设置:使用时长、透明度、颜色、空闲阈值、休息判定时长
- **固定间隔兜底模式**(无障碍不可用/失效时的降级提醒)
- 由无障碍服务静默常驻后台(无通知、不出现在最近任务)+ 权限引导

### 1.3 暂不在范围内(第二期)
- 数据统计面板
- 使用时段计划(以后有需要再加)

### 1.4 明确不做
- 上传任何数据到网络(纯本地)
- 监控屏幕内容 / 输入的具体字符(无障碍配置为不读取窗口内容)
- 摇晃手机关闭(验证通过但决定不做)
- 屏幕亮度调节(WRITE_SETTINGS)
- 自定义铃声

---

## 2. 与 FadeTop 的功能对照

| FadeTop(Windows) | Android 方案 | 状态 |
|---|---|---|
| 连续键鼠活动检测 | 连续使用 = 屏幕点亮时长(手机存在被动注视) | 替代 |
| 忙时不打扰 | 不做:达标即触发(频繁操作时也提醒,避免漏触发) | 不做 |
| 屏幕渐变淡出 | `TYPE_APPLICATION_OVERLAY` 覆盖层 + 定时器逐帧 alpha | 等价 |
| 时钟 / Break Trail / Health Index | 已移除(遮罩只保留背景 + 关闭提示) | 不做 |
| 任意键 / 晃鼠标关闭 | 点击遮罩关闭(带渐隐动画) | 替代 |
| 判定“是否休息够” | 遮罩可见时长 >= 护眼时长(默认 20s) | 等价 |
| 休息后约 1 分钟重试 | 秒关 → 重试间隔(默认 60s)后再提醒;护眼够则重新计时 | 等价 |
| 全屏应用不淡出 | 用户选择的应用白名单(前台时不触发) | 已实现 |
| 背景图 / 闹钟声 | 覆盖层绘制图片(centerCrop);铃声不做 | 背景图已实现 |
| 真正调暗屏幕 | 叠加半透明层(可选改系统亮度) | 替代 |
| 托盘图标状态 | 无通知,由无障碍服务静默常驻 | 不做 |

---

## 3. 术语

| 术语 | 定义 |
|---|---|
| 连续使用时长 | 屏幕点亮持续的时长(与是否触摸无关),息屏清零 |
| 休息间隔 | 连续使用达到该时长即进入待触发 |
| 护眼时长 | 遮罩可见时长 >= 该值才算一次有效护眼 |
| 重试间隔 | 秒关(护眼不够)后,过该时长可再次触发;护眼够则 `continuousMs` 清零重新计时 |
| 白名单应用 | 这些应用在前台时不触发 |

---

## 4. 总体架构

```
┌────────────────── app 进程(由无障碍服务保活)──────────────────┐
│                                                              │
│  ActivityAccessibilityService（系统绑定,常驻)                 │
│   · 持有 settingsState(StateFlow) / OverlayController /       │
│     BreakRepository / SettingsRepository / ForegroundAppChecker│
│   · 屏幕开/关、窗口事件、设置变更 → 触发求值                        │
│   · WakeupScheduler:单次定时调度 + AlarmManager 兜底唤醒         │
│   · BreakStateMachine:纯状态机                                │
│   · 无前台服务、无通知(像李跳跳那样静默常驻)                   │
│                                 │                            │
│  OverlayView (WindowManager 上的自绘 View)                    │
│   · 背景(纯色/图片)渐隐;点击关闭                              │
│                                                              │
│  SettingsActivity (Compose):开关 + 参数 + 权限引导 + 保活自检  │
└──────────────────────────────────────────────────────────────┘
```

要点:**监测逻辑运行在无障碍服务里**(系统绑定它,进程随之常驻),不再使用前台服务/通知。屏幕状态用 `PowerManager.isInteractive()` 每次求值时兜底;计时用单调时钟 `SystemClock.elapsedRealtime()`,唤醒迟到判定用 `SystemClock.uptimeMillis()`(深睡时后者暂停,以此区分"设备睡了"与"进程被冻结")。每个非事件唤醒再用 `AlarmManager.setAndAllowWhileIdle` 兜底,避免 Doze 期间 `Handler` 拿不到 CPU。`SettingsActivity` 与调试 receiver 通过 `ActivityAccessibilityService.instance` 触发预览等操作。设置页 `excludeFromRecents`,不出现在最近任务,避免被上滑强停。

### 4.1 模块划分
```
app/src/main/java/.../fadebreak/
├─ core/        Constants, BreakState
├─ data/        SettingsRepository(DataStore, 唯一真相), BreakRepository(Room), BreakEvent
├─ track/       ActivityAccessibilityService(宿主 + 求值), ForegroundAppChecker,
│               WakeupAlarmReceiver(AlarmManager 兜底回调)
├─ service/     OverlayController, BreakStateMachine(纯逻辑),
│               WakeupScheduler(唤醒策略), WakeupAlarm(Alarm 兜底)
├─ ui/overlay/  OverlayView, BackgroundResolver(背景图解码/裁剪/文件夹轮播)
├─ ui/apps/     AppListActivity(白名单)
├─ ui/image/    AdjustBackgroundActivity(图片选区)
└─ ui/settings/ SettingsActivity, Compose 页面
```

---

## 5. 权限与授权流程

| 权限 | 用途 | 授权方式 |
|---|---|---|
| 无障碍服务 | 承载监测循环(常驻;屏幕开关 + 前台应用切换判定) | 设置中手动开启(系统页) |
| `SYSTEM_ALERT_WINDOW` | 绘制悬浮遮罩 | `ACTION_MANAGE_OVERLAY_PERMISSION` 跳转 |
| `PACKAGE_USAGE_STATS` | 读取前台应用(白名单) | `ACTION_USAGE_ACCESS_SETTINGS` 跳转 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 防 Doze 杀进程 | 跳转系统对话框 |

> 不再使用前台服务/通知,因此无 `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` / `RECEIVE_BOOT_COMPLETED`。进程由无障碍服务绑定保活。

**引导页顺序**:悬浮窗 → 无障碍 → 电池优化白名单 → MagicOS 自启动手动设置(带截图说明)。

无障碍服务配置(`res/xml/accessibility_service_config.xml`)关键项:
- `accessibilityEventTypes`: `typeWindowStateChanged`(仅用于承载常驻服务;计时不依赖任何事件)
- `canRetrieveWindowContent="false"`(不读屏幕内容,降低敏感度)
- `notificationTimeout`: 0(尽量实时)
- `accessibilityFlags`: `flagDefault`

> 说明:为安静省电,不订阅触摸事件,只订阅窗口状态变化(仅用于白名单的前台判定);连续使用时长由屏幕点亮时长推导,求值改为事件驱动 + 单次定时调度,不再每秒轮询。

> 注意:Android 13+ 侧载应用默认禁止开启无障碍,需在“应用信息 → 右上角菜单 → 允许受限设置”中放开。引导页需说明。

---

## 6. 检测逻辑

### 6.1 事件采集
- AccessibilityService 只作为常驻宿主,不订阅触摸事件;仅在启用白名单时用窗口事件判断前台是否离开白名单内的 App。触发判定只依赖屏幕点亮时长,求值由事件 + 单次定时器驱动。
- 这样省掉事件唤醒开销,更接近「李跳跳」式的安静后台。

### 6.2 连续使用时长(屏幕时长模型)
手机与电脑不同,存在大量“被动注视”(看视频不操作)。因此:
- **连续使用时长 = 屏幕亮着的时长**,与是否触摸无关:每次求值时 `continuousMs += now - lastTick`(now 用单调时钟);
- 时长用**单调时钟**(`SystemClock.elapsedRealtime()`)计算,不能用 `System.currentTimeMillis()`(会因网络/调试导致系统时间跳变,把计时算错);
- 每次求值用 **`PowerManager.isInteractive()`** 兜底核对屏幕状态,不能只信 `ACTION_SCREEN_ON/OFF` 广播(后台可能收不到,导致计时卡死或在息屏期间空转);
- 唤醒迟到时用两个时钟区分原因:`elapsedRealtime` 比 `uptimeMillis` 多走 → **设备真的睡过**(等同息屏,重置本段);两钟同幅超前 → **进程被 Doze/厂商冻结但屏幕亮着**,只丢弃这段 gap、**不清零**累计时长(旧实现一律重置,导致每次冻结都把计时归零,永远到不了间隔);
- 每个非事件唤醒都用 `AlarmManager.setAndAllowWhileIdle`(ELAPSED_REALTIME_WAKEUP)镜像一次,深睡/Doze 中也能被唤醒求值(非精确,无需 `SCHEDULE_EXACT_ALARM`);
- **息屏/锁屏 → `continuousMs = 0`**;
- 依赖系统「熄屏超时」兜住“亮屏走人”:放下手机后屏幕会自动熄灭 → 清零。刷短视频、看长视频都会持续计时;听音乐(熄屏)、亮屏走人(到点熄屏)自然清零。
- 不使用音频(听音乐≠用眼)、不使用摄像头(后台相机被系统禁止)、不使用晃动。

### 6.3 触发条件
```
eligible = continuousMs >= 休息间隔
blocked  = 无法绘制遮罩(未授权悬浮窗) || whitelist.contains(foregroundPackage)
trigger  = eligible && !blocked && screenOn && state ∈ {MONITORING, ELIGIBLE}
```

### 6.4 结算与重试(对齐 FadeTop)
- `taken = visibleMs >= 护眼时长`
- 若 `taken`:真正护眼 → `continuousMs = 0`,重新计时;
- 若未 `taken`(秒关):**保留** `continuousMs`,进入「重试间隔」(默认 60s),到点后可直接再次触发;
- 两种情况都写入 `BreakEvent`。

### 6.5 唤醒兜底
不使用“固定间隔无条件提醒”。唤醒可靠性由两层保证:`Handler` 负责按时唤醒;`AlarmManager.setAndAllowWhileIdle` 在 Doze/深睡中兜底(见 6.2)。`IDLE`(等亮屏事件)时以 60s 轮询兜住丢失的 `ACTION_SCREEN_ON`。

### 6.6 应用白名单
- 权限:使用情况访问(`PACKAGE_USAGE_STATS`),设置页自检项 + 跳转授权。
- 前台判定:`ForegroundAppChecker` 增量扫描 `UsageStatsManager` 的 `ACTIVITY_RESUMED` 事件,缓存最后前台包名(避免“最近 10s 无事件→null”的漏判)。
- 触发门槛增加 `blocked = whitelist.contains(foregroundPackage)`,为真时不触发;`whitelist` 为空则不做前台查询。
- 用户可在“选择白名单应用”页勾选(Launcher 应用列表,图标懒加载)。
- **说明**:自动“全屏检测”在 Android 上不可靠(edge-to-edge 下窗口边界无区分度),故采用用户显式白名单;看视频/玩游戏时把对应 App 加入即可。

---

## 7. 状态机

```
        ┌──────────────────────────────────────────────┐
        │                                              │
        ▼                                              │
    ┌────────┐  屏幕点亮/息屏复亮      ┌────────────┐   │
    │  IDLE  │ ───────────────────────▶│ MONITORING │   │
    └────────┘                         └─────┬──────┘   │
         ▲  屏幕熄灭 → 重置                   │          │
         │                                   │ continuousMs >= 休息间隔
        │                                   ▼          │
        │                            ┌────────────┐    │
        │       又开始操作(不空闲) │ ELIGIBLE   │    │
        │      ◀────────────────────│ (待触发)   │    │
        │                            └─────┬──────┘    │
        │                       空闲 && !blocked        │
        │                                   ▼          │
        │                            ┌────────────┐    │
        │                            │BREAK_ACTIVE│    │ 渐入 fadeDuration
        │                            └─────┬──────┘    │
        │                            点击关闭(先渐隐)   │
        │                                   ▼          │
        │                            ┌────────────┐    │
        └────────────────────────────│ COOLDOWN   │────┘ 重试间隔到 → MONITORING
                                      └────────────┘
```

状态字段:`IDLE / MONITORING / ELIGIBLE / BREAK_ACTIVE / COOLDOWN`

转移动作:
| 从 | 到 | 动作 |
|---|---|---|
| IDLE | MONITORING | 屏幕亮,开始累计,`lastTick=now` |
| MONITORING | ELIGIBLE | `continuousMs >= 休息间隔` |
| ELIGIBLE | BREAK_ACTIVE | `eligible && !blocked`,记录 `showAt`,创建覆盖层(渐入) |
| BREAK_ACTIVE | COOLDOWN | 结算写库(±taken),渐隐移除;`retryUntil = now + 重试间隔`;若 `taken` 则 `continuousMs=0` 且连续打断计数清零;**连续两次未达标手动关闭则 `continuousMs=0` 重新计时并清零** |
| COOLDOWN | MONITORING | 重试间隔到,当拍即重新求值 |

任何状态遇**屏幕熄灭**:移除覆盖层(若在显示),结算为未护眼,`continuousMs=0` → IDLE。

---

## 8. 覆盖层(Overlay)设计

### 8.1 WindowManager 参数
- `type = TYPE_APPLICATION_OVERLAY`(`minSdk 26`,全版本可用,无需旧类型分支)
- `flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`
  - `FLAG_NOT_FOCUSABLE` 仍可接收触摸,但不抢按键焦点(用点击关闭)
- `format = PixelFormat.TRANSLUCENT`,`width/height = MATCH_PARENT`
- `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`(适配挖孔屏)
- **`setFitInsetsTypes(0)`(API 30+)**:不被系统栏 inset,遮罩**铺满状态栏与导航栏区域**(实测全屏覆盖,底部系统白条也不再露出)。
- 渐隐:用 `Handler` 定时器每 16ms 递增 `alphaFraction`(`0→MaxOpacity`)并 `invalidate()`(等价于 FadeTop 的 `SetLayeredWindowAttributes` + `SetTimer`,实测 5s 内 304 帧、平均间隔 16ms)。
  - **`alphaFraction` 的 setter 必须调用 `invalidate()`**;否则只有时钟 ticker 每秒重绘一次,会像 PPT。
  - 不用 `ValueAnimator`:其帧回调在本场景会被系统节流(实测仅 ~1fps)。
  - 背景图先解码并预缩放/裁剪为屏幕尺寸,渐隐期间只做整屏贴图,避免逐帧缩放掉帧。

### 8.2 渲染内容
```
┌───────────────────────────────────────────┐
│                                           │
│         (纯色 或 背景图片,按 alpha 渐隐)     │
│                                           │
│                                           │
│              点击任意处关闭                │
└───────────────────────────────────────────┘
```
- 背景为纯色(默认)或图片,整体按 alpha 渐隐。设置页「选择图片或文件夹」,弹窗二选一:
  - **选择图片**:选图后进入拖动页,手动选择要显示的区域(保存归一化焦点 `bgFocusX/bgFocusY`,0.5=居中)。
  - **选择文件夹**:保存 SAF 目录(`bgFolderUri`),每次提醒**轮流**取文件夹中的一张图片,按最初的**居中裁剪**算法自动显示(不手动调整)。
- 仅保留一行关闭提示;不再显示时钟、Break Trail、Health Index。
- 文字用系统 `Typeface`,无第三方依赖。

### 8.3 关闭方式
- **自动关闭**:遮罩显示达到「护眼时长」后自动触发淡出(视为已护眼)。
- **手动关闭**:覆盖层任意位置 `onTouchEvent`(ACTION_UP)→ 触发关闭。
- 关闭时按「渐隐时长」播放淡出动画(默认 3s,可调),动画结束后结算并移除覆盖层。

### 8.4 主题配色
- 以图标绿为锚点、低饱和度的一套 Material3 配色(`ui/theme/Theme.kt`)。
- **日间**:白底 + 鼠尾草绿主色 `#4F7A63`;状态栏/导航栏图标为深色。
- **夜间**:灰底 `#151715` + 浅雾绿主色 `#A9C9B5`;系统栏图标为浅色。
- 用 `isSystemInDarkTheme()` 自动跟随系统深浅色,无需手动切换。
- 窗口背景与系统栏明暗由 `values/`、`values-night/` 的 `Theme.FadeBreak` 提供(避免启动白闪、图标对比正确)。

---

## 9. 数据模型

### 9.1 Room:`BreakEvent`
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long (PK, autoGen) | |
| triggeredAt | Long (epoch ms) | 触发时刻 |
| dismissedAt | Long? | 关闭时刻,null=仍显示/被系统中断 |
| visibleMs | Long | 可见时长 |
| taken | Boolean | 是否算有效休息 |
| dayKey | String (yyyyMMdd) | 便于按天聚合 |

### 9.2 DataStore:设置项
| Key | 默认 | 说明 |
|---|---|---|
| timeoutMs | 1200000 | 休息间隔(连续使用多久提醒) |
| fadeMs | 3000 | 渐进时长(出现时淡入) |
| fadeOutMs | 3000 | 渐隐时长(关闭时淡出) |
| maxOpacity | 1.0 | 最大不透明度(0~1) |
| minBreakMs | 20000 | 护眼时长(停留多久算有效护眼) |
| retryMs | 60000 | 重试间隔(秒关后多久再提醒) |
| enabled | true | 是否开启检测(UI 的开关按钮) |
| whitelist | 空 | 白名单应用包名集合(前台时不触发) |
| bgImageEnabled | false | 使用背景图片 |
| bgImageUri | "" | 单张背景图的 SAF 持久化 URI |
| bgFolderUri | "" | 背景图片文件夹的 SAF 目录 URI |
| bgFolderIndex | 0 | 文件夹轮播的下一个索引 |
| bgFocusX / bgFocusY | 0.5 | 单张图片的显示区域焦点(0~1,0.5=居中) |

> UI 上仅暴露:基础(休息间隔、护眼时长、最大不透明度、背景图片)+ 高级(重试间隔、渐进时长、渐隐时长)。

### 9.3 运行期状态(内存,不持久化)
`ActivityAccessibilityService`:内存中的 `settingsState: StateFlow<BreakSettings>`(镜像 DataStore,单一来源仍是 DataStore),以及 `BreakStateMachine` 的内部状态(不持久化)。

---

## 10. 服务与生命周期

- **监测是事件驱动 + 单次定时调度**,运行在 `ActivityAccessibilityService`(系统绑定,进程常驻),不开前台服务、不发通知:
  1. 由 `WakeupScheduler`(`nextWakeAt()` 读取 `BreakStateMachine.snapshot()`)给出「下一次状态可能变化的时刻」(达到休息间隔 / 护眼时长到点 / 重试到点),只 `postDelayed` 一次;
  2. 屏幕开关、前台应用切换(窗口事件)、设置变更、遮罩关闭都会立即重新求值;
  3. 触发时 `showOverlay()`:先同步显示纯色遮罩,再异步贴图(避免解码期间熄屏把 View 挂在后面)。
   - 息屏时机器状态为 `IDLE`,下一唤醒为「事件」,以 **60s** 轮询兜底(防止漏收 `ACTION_SCREEN_ON`);深睡期间 `Handler` 不跑,由 AlarmManager 兜底。
   - 唤醒迟到按两个时钟区分:设备真睡过 → 重置本段;进程被冻结但屏幕亮着 → 丢弃 gap、保留累计(见 6.2)。
- **可靠唤醒**:`WakeupAlarm` 用 `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, …)` 镜像每个非事件唤醒,Doze/深睡中也会被唤醒求值;`WakeupAlarmReceiver` 收到后调用服务的 `onWakeAlarm()` 立即重算。非精确、无需额外权限。
- **屏幕状态**:`ACTION_SCREEN_OFF/ON` 广播 + 每次求值用 `PowerManager.isInteractive()` 兜底核对。
- **开关**:设置项 `enabled` 默认 true;UI 提供「开启检测/关闭检测」按钮,关闭即真正停止提醒(不关闭无障碍,便于再次开启)。
- **不出现在最近任务**:`SettingsActivity` 设 `android:excludeFromRecents="true"`。荣耀等 ROM 在**上滑移除任务时会强制停止应用并连带关闭其无障碍服务**,隐藏卡片可避免被误停。
- **进程被杀**:无障碍重新绑定后 `onServiceConnected` 会重新初始化监测。

---

## 11. 保活适配(多厂商,重点)

各家 ROM 对后台都很激进。文档以**荣耀 MagicOS** 为主,其余厂商思路相同但菜单不同,应用内按检测到的 ROM 显示对应指引:
1. 设置 → 应用 → 应用启动管理 → 本应用 → 关闭“自动管理”,手动勾选**允许自启动 / 关联启动 / 后台活动**
2. 设置 → 电池 → 更多电池设置 → 保持“休眠时连接”/关闭省电优化
3. 电池优化白名单:跳转 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
4. 无障碍服务保活即可(设置页不出现在最近任务,避免被上滑强停)
5. 应用内提供“保活自检”:显示各权限/白名单状态

> 其它厂商备忘:MIUI/HyperOS(“自启动/省电策略→无限制”)、ColorOS、OriginOS、One UI(加入“从不休眠的应用”)、原生 Android。开源后需在 README 汇总。

> 若系统仍杀进程,兜底:依赖无障碍服务绑定保活 + 加入电池优化白名单 / 自启动。

---

## 12. 边界与异常

| 场景 | 处理 |
|---|---|
| 未开无障碍 | 监测不会运行(无障碍服务即宿主)→ 自检项提示开启 |
| 未开悬浮窗 | 无法显示遮罩 → 引导页强制拦截 |
| 未授权悬浮窗 | 无法绘制遮罩 → 视为 blocked,不进入 BREAK_ACTIVE(避免卡死) |
| 屏幕旋转 | 覆盖层重新 `updateViewLayout` 适配新尺寸/横竖屏 |
| 息屏/锁屏期间到达触发点 | 不触发;解锁后按当前状态重新计 |
| 遮罩显示时用户强行息屏 | 视为未休息,写库并按灭屏规则重置 |
| 服务被系统杀死 | 重启后设置从 DataStore 读取,状态从 IDLE 开始;Alarm 唤醒由系统重新投递 |
| 多用户/分屏 | 自用机型单一,不特别处理 |
| 无障碍读取窗口内容 | 明确关闭,避免隐私与合规风险 |

---

## 13. 技术栈

- 语言:Kotlin
- UI:Jetpack Compose(设置页);覆盖层用经典 `View` + `Canvas` 自绘
- 存储:Room(历史)、DataStore(设置)
- 异步:Coroutines + Flow;`ActivityAccessibilityService` 内用 `Handler`/`Flow` 驱动
- 构建:Gradle(Kotlin DSL)+ Version Catalog
- `compileSdk/targetSdk = 36`,`minSdk = 26`(Android 8.0)
  - `minSdk 26` 使 `TYPE_APPLICATION_OVERLAY`、通知渠道始终可用,无需旧版兼容分支
  - `targetSdk 36` 独立于 `minSdk`,面向 Android 16 行为(与目标设备 MagicOS 10 对齐)
- 版本差异集中在 `core/Compat` 工具类(如前台服务类型已不再需要)
- 依赖尽量少,便于侧载和构建

---

## 14. 里程碑

| 阶段 | 交付 | 验收 |
|---|---|---|
| M0 | 工程骨架:权限声明、空无障碍服务 | 能安装启动,无障碍可开启 |
| M1 | 悬浮层 + 手动触发 + 点击关闭 | 点击按钮能弹出遮罩并点走 |
| M2 | 状态机 + 屏幕时长检测 + 渐隐 + AlarmManager 唤醒兜底 | 连续使用到点后自动淡出;无无障碍时也能提醒 |
| M3 | 断点事件落库(Room) | 数据正确落库 |
| M4 | 设置页 + 权限引导 + 保活自检 | 参数可调并生效 |
| M5 | 荣耀 400(MagicOS 10)真机稳定性打磨 | 连续运行 1 天不被杀 |
| M6(二期) | 图片/声音/白名单/亮度/计划 | — |

---

## 15. 已确认事项

| 项目 | 决定 |
|---|---|
| 目标设备 | 荣耀 400,MagicOS 10 / Android 16 |
| 构建版本 | `compileSdk/targetSdk = 36`,`minSdk = 26`(Android 8.0+) |
| 应用名 / 包名 | `FadeBreak` / `com.wjf.fadebreak` |
| 开源许可 | MIT |
| 默认参数 | 连续使用 **20 分钟**触发、有效休息 **20 秒**、重试 **60 秒** |
| 关闭方式 | 点击遮罩关闭(摇晃关闭、“稍后提醒”按钮移至二期) |
| 唤醒兜底 | AlarmManager `setAndAllowWhileIdle` 镜像唤醒(非“固定间隔无条件提醒”) |
| 运行模式 | 纯本地,不申请 `INTERNET`;面向开源分享,兼容多厂商 ROM |

---

## 16. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| ROM 限制无障碍服务/后台 | 检测不准/不触发 | 常驻无障碍服务 + 每次求值用 `PowerManager` 兜底;保活引导 |
| 后台被杀 | 提醒失效 | 保活引导 + 自检(电池白名单 / 自启动);不引入前台服务与通知 |
| Android 15+ FGS/BOOT 限制 | 无 FGS/BOOT,重启后需系统重新绑定无障碍 | 依赖无障碍绑定;引导用户允许自启动 |
| 判断“忙不忙”不精确 | 误触发/漏触发 | 可调阈值,接受近似 |
| 覆盖层覆盖状态栏不完全 | 已解决 | `LAYOUT_IN_SCREEN` + `setFitInsetsTypes(0)` + 挖孔适配,已全屏覆盖 |

---

## 17. 开源与发布(GitHub)

- **不分发到应用商店**:以源码 + Release APK 形式发布,规避 Google Play 对无障碍/悬浮窗的政策审查。
- **隐私即卖点**:不声明 `INTERNET` 权限,数据全部本地,README 首页显著说明。
- **LICENSE**:MIT。
- **签名**:自建 release keystore,`keystore.properties` 与 `.jks` 加入 `.gitignore`,绝不入库。
- **README**:功能截图/GIF、权限用途说明、各家 ROM 保活设置指引、已知限制、构建方式。
- **可复现构建**:固定依赖版本,提供 `./gradlew assembleRelease` 说明。
- **信任透明**:无障碍服务明示“仅用于屏幕时长计时与前台应用判定,不读取屏幕内容”,配置项 `canRetrieveWindowContent=false` 让用户可自行审计。

### 17.1 命名 / 许可 / 版权注意

- **避开同名项目**:GitHub 已有 `vipheyue/FadeTop`(Android,番茄钟 + 通知,GPL-3.0,2018 年停更)。本项目命名为 **FadeBreak** 以区别,README 标注“灵感来自 Windows 版 FadeTop”。
- **不构成衍生**:本项目为独立实现(参照 Windows FadeTop 的行为 + 自研设计),未使用 `vipheyue/FadeTop` 代码,因此**不受其 GPL-3.0 传染**,可自由选择 MIT/Apache-2.0。
- **原版版权**:Windows 版 FadeTop 的名称与图标归 fadetop.com 所有,不照搬其 logo、图标、文案等资源。
- **差异化定位**:README 可强调核心差异——“多数同类只是定时通知,本应用在你连续使用过久且暂时空闲时真正淡出屏幕”,这正是 `vipheyue/FadeTop` 所没有的。

## 18. 兼容性验证清单

| 维度 | 用例 |
|---|---|
| Android 版本 | 26 / 29 / 31 / 33 / 34 / 35 / 36 各至少一台(或模拟器) |
| ROM | MagicOS 10(主,荣耀 400)、MIUI/HyperOS、ColorOS、OriginOS、One UI、原生 |
| 权限 | 未授权逐项、授权后撤销、Android 13+ 受限设置 |
| 生命周期 | 息屏/锁屏/重启/省电模式/被系统杀后恢复 |
| 显示 | 竖屏、横屏、挖孔/曲面、深色模式 |
| 无障碍 | 服务是否常驻、屏幕状态(`isInteractive`)是否与计时一致(多机型回归) |
