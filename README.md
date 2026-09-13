# FadeBreak

> **基于 20-20-20 原则的 Android 护眼提醒。盯屏太久,整屏缓缓淡入一张图片,提醒你抬头远眺。**
> An Android break reminder based on the 20-20-20 rule: when you stare too long, a full-screen image fades in to nudge your eyes away.

[中文](#中文) | [English](#english)

---

## 中文

**FadeBreak** 是一款 Android 护眼提醒,灵感来自 Windows 的 [FadeTop](http://fadetop.com)。它不弹通知,而是在你连续用机过久时把整块屏幕缓缓淡入成半透明图片——眼睛被迫离开屏幕,停满 20 秒即完成一次休息。

**设计依据** [20-20-20 原则](https://www.aao.org/eye-health/tips-prevention/computer-usage):每用眼 20 分钟,看 20 英尺(约 6 米)外、持续 20 秒。

| 20-20-20 | FadeBreak |
|---|---|
| 每 20 分钟 | 休息间隔(默认 20 分钟) |
| 看向约 6 米外 | 全屏渐变图片 |
| 持续 20 秒 | 护眼时长(默认 20 秒) |

**特性**
- 到点渐变图片,不打断操作;满 20 秒自动淡出并重新计时
- 提前关闭则默认 60 秒后重试
- 白名单:看视频 / 玩游戏时前台不提醒
- 背景可自定义(纯色 / 图片 / 文件夹轮播),支持预览
- 静默常驻无通知;保活自检按 ROM 给出指引
- 纯本地:无 `INTERNET` 权限,无障碍不读取屏幕内容

**安装**:在 [Releases](../../releases) 下载 APK 侧载,按应用内引导开启悬浮窗、无障碍、电池优化白名单。Android 13+ 需先允许「受限设置」。

**构建**:JDK 17 + Android SDK 36,`./gradlew assembleRelease`。

**兼容性**:仅 Android 8.0+(API 26),targetSdk 36;iOS 不支持。主要在荣耀 400 / MagicOS 10 验证,其它 ROM 保活见 [`docs/DESIGN.md`](docs/DESIGN.md)。

**限制**:「忙不忙」为近似判断;部分 ROM 会杀后台,需加白名单 / 自启动。

**许可**:[MIT](LICENSE)。灵感来自 FadeTop,为独立实现、无隶属关系;20-20-20 参考 AAO 科普,非医疗建议。

> 截图待补充。

---

## English

**FadeBreak** is an Android break reminder inspired by [FadeTop](http://fadetop.com). No notifications — when you've used your phone too long, a full-screen image fades in, forcing your eyes off the screen; stay 20 seconds and the break counts.

**Based on the [20-20-20 rule](https://www.aao.org/eye-health/tips-prevention/computer-usage)**: every 20 minutes, look 20 feet away for 20 seconds.

| 20-20-20 | FadeBreak |
|---|---|
| Every 20 minutes | Break interval (default 20 min) |
| Look ~20 feet away | Full-screen fading image |
| For 20 seconds | Minimum break (default 20 s) |

**Features**
- Timed fade-in image; auto-settles after 20 s and restarts
- Dismissed too fast? Retries after 60 s
- Whitelist: no reminder while video/game apps are foreground
- Custom background (solid color / image / folder slideshow), with preview
- Silent and resident, no notification; keep-alive self-check with ROM hints
- Fully local: no `INTERNET` permission; accessibility never reads screen content

**Install**: grab the APK from [Releases](../../releases) and sideload; follow the in-app guide (overlay permission, accessibility, battery whitelist). Android 13+ requires allowing "restricted settings".

**Build**: JDK 17 + Android SDK 36; `./gradlew assembleRelease`.

**Compatibility**: Android 8.0+ (API 26), targetSdk 36; iOS not supported. Verified mainly on Honor 400 / MagicOS 10; other ROM keep-alive notes in [`docs/DESIGN.md`](docs/DESIGN.md).

**Limitations**: "busy" detection is approximate; some ROMs kill background apps — whitelist / auto-start as guided.

**License**: [MIT](LICENSE). Inspired by FadeTop, an independent implementation, not affiliated; 20-20-20 references AAO public material, not medical advice.

> Screenshots TBD.
