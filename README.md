# LyricCar 歌词（lyriccar）

车机上全屏显示歌词的 Android App，**纯 Android 原生实现（Kotlin + Android Framework Views）**。

**它不接管音频**：声音照旧由车机自带的蓝牙播放器出，本 App 只是坐在旁边看系统媒体会话（MediaSession），把歌词一句句采集下来、存起来、显示出来。

**它不连外网**：歌词全靠本地听出来，一个外部网络请求都不发。唯一会用到网络的是「局域网传输」—— 那时它在车上起一个很小的 HTTP 服务，让同一个 Wi-Fi 下的手机连上来取歌词库（面板关掉就停）。

> 💬 **QQ 粉丝群：829323767**
>
> 最新的测试安装包会先发在群里，欢迎拿自己的车机装上试、把问题反馈回来
> （反馈时带上「设置 → 诊断日志」导出的日志，见[给远方的人测](#给远方的人测诊断日志)）。

## 它解决什么问题

车机的蓝牙音乐界面只有三行：**标题 / 歌手 / 专辑**。想让屏幕上滚歌词，就得「借」一行来写歌词 —— 播放器每换一句就更新一次媒体信息，车机看起来就是在滚动歌词。网易云、QQ 音乐、哔哩音乐的「车载歌词 / 蓝牙歌词」都是这个原理。

借哪一行各家不一样，所以本 App **三行都支持，而且能自己认出来**：

| 借的那一行              | 谁这么干           | 代价                                 |
| ----------------------- | ------------------ | ------------------------------------ |
| **歌手行** / **专辑行** | 车机自带蓝牙播放器 | 无（歌名还在）                       |
| **歌曲名行**            | 网易云、QQ 音乐    | 歌名没了，车机界面上那一行被歌词顶掉 |

这套玩法的接收端就是本 App：

```
播放器（网易云 / QQ 音乐 / 车机自带蓝牙播放器）
   把歌词写进 MediaMetadata 的 歌手行 / 专辑行 / 歌曲名行
        ↓ 系统媒体会话（MediaSession）
本 App 读 MediaMetadata → 盯着那一行，变一次记一句 + 记下当时的播放位置
        ↓
歌词库（本地 JSON）→ 全屏同步滚动显示
```

- **第一遍听**：边听边攒歌词（界面上能看到已采集多少句）；
- **听到一半切走 / 退出 App 也不要紧**：下次放这首歌，采到的部分按播放位置照滚，没采到的边放边补；
- **一首放完**：整首歌词齐了，标记为「完整」；
- **第二遍起**：直接从本地歌词库同步显示；
- **换车机**：导出 JSON 带过去导入；两台车在同一 Wi-Fi 下也可以直接局域网传输。

## 功能清单

- **蓝牙 MediaBrowser 优先**：本车机的蓝牙栈提供 `A2dpMediaBrowserService`，不用通知使用权就能读实时歌词字段和播放位置。
- 读取系统媒体会话：标题 / 歌手 / 专辑 / 时长 / 播放位置 / 播放状态 / 来源 App / 封面。
- 通知监听回退：MediaBrowser 不可用时，自动退回原来的 NotificationListenerService 方案。
- **连接通道可选**：设置 → 连接通道，在「自动 / 蓝牙 / 系统会话」之间手动切。有的车机用「媒体中心」放蓝牙音乐，系统会话里只有播放进度、没有歌名，切到「蓝牙」就直接问蓝牙栈要；反过来也能只看系统会话。手动选「蓝牙」而车机没有蓝牙媒体浏览服务时，空界面会直接说明并给一个「改回自动通道」按钮。
- 歌词采集：支持歌手行、专辑行、歌曲名行，以及「自动判断」。
- 自动判断歌曲名行：标题在变、播放位置没有明显回退 → 判定歌词写在歌曲名行。
- 按歌名优先匹配旧稿；歌名重名时按时长挑选；再用歌词文本验证匹配，错了能退回。
- 半份稿子也能回放，同时继续补词；重听 / 单曲循环自动补开头。
- 播放控制：上一首 / 播放暂停 / 下一首 / 拖动进度；点歌词某一句跳转到那句。
- 歌词面板：当前句高亮、自动居中滚动、长句**按宽度换行**（不截断、不压扁）、手动上下滑动。
- 歌词库：查看 / 预览 / 删除单首 / 清空全部；显示句数、完整性、来源。
- 导出到系统「下载」目录；从下载目录导入；局域网 HTTP 传输（下载 / 上传 / 诊断日志）。
- 诊断日志：自动落盘、256 KB 滚动、环境自查、导出 / 复制 / 清空 / 手机来取。
- 设置：歌词写在（自动 / 歌手行 / 专辑行 / 歌曲名行）、连接通道（自动 / 蓝牙 / 系统会话）、界面常亮、深色 / 浅色 / **跟随系统**外观。
- 歌词样式：字号（小 / 标准 / 大 / 特大）、行距（紧凑 / 标准 / 宽松）、左对齐 / 居中、当前句颜色（主题色 / 暖黄 / 粉 / 绿 / 橙 / 高对比），设置里带真实歌词控件预览，改完立刻生效。
- 自定义背景：设置 → 背景，可以从系统文件管理器 / 图库选一张图片，用可拖动的「屏幕比例遮罩」框选要用的区域；也可以用纯色，或一键恢复默认背景。图片按屏幕大小压好存在应用私有目录，重启后仍然生效。
- 左栏透明 / 半透明：图片背景下，左边控制栏可以整块透明（背景图直接露出来，只留中间那条分栏条），也可以铺一层半透明底，设置 → 背景 → 左栏底色。
- 左右分栏可拖动：中间的分割线用手指左右拖就能改两栏宽度，松手即存，双击恢复默认比例。
- 竖屏 / 分屏也能用：窗口竖着（竖屏车机、或「平行世界」里被挤成竖条）时自动换成「上边整块歌词 + 下边有一条控制栏」的排法，封面不占地方，分栏条照样能拖。
- 左栏标题固定显示歌名：不跟着歌词一句句跳；半截插进来听的歌不会把当时那句歌词当成歌名（从头放一遍才记）。
- 歌词库自动清理：每次启动把「不到 5 句」或「只覆盖 10 秒以内」的记录删掉 —— 列表里滑过去、没完整听的歌不用占地方；自己导入的不动。
- 外观跟随系统：设置 → 外观选「跟随系统」，车机切白天 / 夜间模式时 App 自己换浅色 / 深色。比亚迪的框架会吞掉非白名单 App 的 uimode 变更，所以这里监听车机的 `ui_night_mode` 设置，切了立刻重建界面。
- **悬浮歌词（桌面歌词）**：设置 → 悬浮歌词，把当前几句歌词浮在导航 / 桌面上面，LyricCar 自己在前台时自动隐藏。可调显示行数（1 / 2 / 3 / 5）、字号、当前句颜色、底衬（无 / 淡 / 深）、**文字黑边描边（无 / 细 / 粗）**、**固定窗口宽度（30%~95%）**；拖动换位置、松手记住，可锁定后点击穿透到下面的导航，点一下回到 LyricCar。前台服务维持，App 退到后台歌词照常滚动。
- 关于面板：版本号、开发者、GitHub 地址、版权声明，链接可一键打开 / 复制。
- 不锁横屏；除悬浮歌词的「显示在其他应用上层」外不申请多余权限（只在打开悬浮歌词时申请，Android 10+ 导出不需要存储权限）。

## 工程结构

```
app/src/main/java/com/homeweb/lyriccar/
├── LyricCarApp.kt                    Application：歌词库 / 设置 / 引擎 / 会话读取器单例
├── MediaSessionListenerService.kt    通知监听服务（只为拿读取 MediaSession 的权限）
├── core/
│   └── LyricCaptureEngine.kt         歌词采集 / 回放 / 名字匹配 / 补词的核心状态机
├── data/
│   ├── AppSettings.kt                设置（settings.json）
│   ├── Diagnostics.kt                诊断日志（diagnostics.log，256 KB 滚动）
│   └── LyricStore.kt                 歌词库（lyrics.json，原子写 + 防抖）
├── media/
│   ├── MediaSourceCoordinator.kt     蓝牙 MediaBrowser 优先 + 通知监听回退
│   ├── BluetoothBrowserSource.kt     连接蓝牙 A2dpMediaBrowserService（无需通知权限）
│   └── MediaSessionRepository.kt     读 MediaSession、外推位置、封面、播放控制、诊断
├── overlay/
│   ├── LyricOverlayService.kt        悬浮歌词的前台服务（窗口 / 拖动 / 隐藏规则 / 通知）
│   └── OverlayBootReceiver.kt        开机后按上次开关恢复悬浮歌词
├── model/
│   ├── Models.kt                     MediaInfo / LyricLine / StoredSong / 歌曲 key / 通道
│   ├── LyricDisplay.kt               歌词样式档位（字号 / 行距 / 对齐 / 颜色）
│   ├── OverlayStyle.kt               悬浮歌词样式（行数 / 字号 / 颜色 / 底衬 / 位置）
│   └── BackgroundStyle.kt            背景模式 / 纯色预设
├── transfer/
│   └── DownloadsRepository.kt        系统「下载」目录导出 / 导入（MediaStore / 旧存储）
├── lan/
│   └── LyricLanServer.kt             局域网 HTTP 服务（页面 / JSON / 日志 / multipart 上传）
└── ui/
    ├── MainActivity.kt               全屏宿主 Activity（横 / 竖屏都行）
    ├── MainScreen.kt                 左「正在播放」+ 右歌词的主界面（竖屏时改成上歌词 / 下控制栏）
    ├── LyricsView.kt                 自绘歌词列表（换行 / 高亮 / 居中滚动 / 点击跳转）
    ├── LyricWrap.kt                  按实际渲染宽度把长句拆成多行
    ├── OverlayLyricsView.kt          悬浮窗里只画 N 句的轻量视图
    ├── OverlaySheet.kt               悬浮歌词设置面板（开关 / 行数 / 字号 / 颜色 / 底衬）
    ├── LyricStyle.kt                 把设置里的样式解析成颜色和倍数
    ├── BackgroundCropper.kt          背景图片读取 / 裁剪遮罩 / 保存
    ├── SplitHandle.kt                可拖动的分栏条（左右 / 上下，双击复位）
    ├── Sheets.kt                     歌词库 / 导入 / 设置 / 关于 / 诊断 / 局域网面板
    └── UiKit.kt                      深色 + 浅色两套配色、主题解析、dp、圆角背景、按钮等 UI 小工具
app/src/main/res/                     主题、颜色、应用名、启动图标
```

没有第三方运行时依赖：媒体会话、JSON、HTTP、UI 全部使用 Android 平台自带 API。Gradle 侧只有 Android Gradle Plugin 和 Kotlin 插件。

## 数据文件与升级兼容

歌词库、设置、日志都在应用私有目录（不需要任何权限）：

```
<应用私有目录>/files/carlyric/
├── lyrics.json         歌词库
├── settings.json       设置（含悬浮歌词开关 / 样式 / 位置）
├── background.jpg      自定义背景图片（按屏幕大小压好；恢复默认时删除）
├── diagnostics.log     诊断日志（超过 256 KB 滚成 diagnostics.log.1）
└── diagnostics.log.1   上一份日志
```

`lyrics.json` 的格式与旧 Flutter 版完全一致（`app/version/songs/lines/t/text` 等字段），所以：

- **applicationId 仍是 `com.homeweb.lyriccar`**；
- **签名仍是同一份 `lyriccar-release.jks`**；
- **通知监听组件名仍是 `com.homeweb.lyriccar.MediaSessionListenerService`**。

因此新原生版可以**直接覆盖安装**旧 Flutter 版，已采集的歌词库、设置和通知使用权都会继续有效，不用重新授权。

## 构建

环境要求：JDK 17+、Android SDK（compileSdk 36 / build-tools 36.0.0）。`local.properties` 里写 `sdk.dir=...`，或者设置 `ANDROID_HOME`。

```bash
# 本机 Android Studio 自带 JBR（没有单独装 JDK 时）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug      # app/build/outputs/apk/debug/LyricCar v2.6.2-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/LyricCar v2.6.2.apk
```

APK 文件名按 `LyricCar v{版本号}` 生成，版本号在 `app/build.gradle.kts` 顶部的 `appVersionName` 里改一处，「关于」面板显示的是同一个值。

本工程没有 native 库，生成的 APK 约 0.8 MB；直接安装 `LyricCar v2.6.2.apk` 即可。文件名带空格，命令行安装要加引号：

```bash
adb install -r "app/build/outputs/apk/release/LyricCar v2.6.2.apk"
```

发布签名口令放在 `app/key.properties`（已被 `.gitignore` 排除，模板见 `app/key.properties.example`）。没配这个文件时 release 会退回 debug 签名，只够本地跑通构建，**不要**拿那种包当正式包。

⚠️ **`app/lyriccar-release.jks` 和口令一定要备份。** 丢了就再也签不出「同一个 App」，以后升级只能让用户先卸载 —— 车机上攒的歌词库会一起没，所以卸载前先「歌词库 → 导出」。

## 安装与首次使用

1. 装到车机上（横屏、竖屏都能用）。
2. 先直接放歌试试：
   - 本车机的蓝牙栈提供 `A2dpMediaBrowserService`，App 会自动接上它，**不需要通知使用权**；
   - 如果你的车型没有这个服务，界面会按提示要求授予**通知使用权**（Android 允许普通 App 读取别人媒体会话的入口）。
3. 在播放器里打开「车载歌词 / 蓝牙歌词」。
4. 放一首歌：
   - 左侧能看到系统读到的标题 / 歌手 / 专辑原值和来源 App；
   - 右侧第一次是「采集中」，放完之后下一次就是完整歌词同步滚动。
5. 点歌词任意一行可以跳转到那句（通过 MediaController 转发给播放器）。
6. 想换车机 / 备份时，进「歌词库」用「导出到下载」或「局域网传输」。
7. 左右两栏中间那条线可以直接手指左右拖动，改两栏宽度；松手自动记住，双击那条线恢复默认 40%。
8. 白天屏幕反光看不清，去「设置 → 外观」切成「浅色」；想让它跟着车机的白天 / 夜间模式自己换，选「跟随系统」。
9. 歌词字太小 / 想居中 / 想换个颜色，去「设置 → 歌词样式」：字号、行距、左对齐 / 居中、当前句颜色都能调，面板里就是用真实歌词控件做的预览，改完立刻生效。
10. 想换背景，去「设置 → 背景」：点「从图库选择图片」，在系统文件管理器 / 图库里选图，再用可拖动的遮罩选框挑区域，点「使用这张」即可；也可以直接点纯色色块，或者「恢复默认背景」。选图片时「左栏底色」决定左边控制栏是透明还是半透明。
11. 想一边导航一边看歌词，去「设置 → 悬浮歌词」打开开关（第一次要先给「显示在其他应用上层」权限），回到桌面或打开高德就能看到；行数 / 字号 / 颜色 / 底衬都能在面板里调，面板里有实时预览。

如果一直采不到歌词，去「设置 → 歌词写在」把手动档切成「专辑行 / 歌手行 / 歌曲名行」试试 —— 左侧面板把三行原始内容显示出来，就是给这个排查用的。

## 不需要通知使用权的蓝牙路径

很多 BYD 车机把「通知使用权」设置页整个删掉了（点进去只弹「多媒体系统不支持该操作」），
普通用户没法自己授权。为此 v2.1 增加了蓝牙自带的 MediaBrowser 路径：

```
手机 ──蓝牙 A2DP──> 车机蓝牙栈
                      └─ com.android.bluetooth/.a2dpsink.mbs.A2dpMediaBrowserService
                           ↑ 普通 App 可以直接连接（服务 exported，无权限保护）
                      本 App → MediaBrowser.getSessionToken() → MediaController
                           → TITLE / ARTIST / ALBUM / 播放位置
```

这条路径：

- **不需要通知使用权**，也不需要 ADB；
- 拿到的是和系统媒体会话同一份蓝牙元数据，歌词写在歌曲名行 / 歌手行都能采；
- 播放 / 暂停 / 上一首 / 下一首、seek 都转发给同一个 MediaController；
- 蓝牙断开、服务重启、App 重启会自动重连；
- 只覆盖**蓝牙播放**；车机自带酷狗 / U 盘 / 收音机等仍可能走通知监听回退。

如果某些车型没有这个服务，或服务连接失败，会**自动回退**到原来的通知监听方案；
界面只有在两条路都不可用时才显示通知使用权 / ADB 引导。

⚠️ 这个 MediaBrowserService 是车机厂商自带的系统服务，不是 Android 公共 API。
不同 BYD 车型、不同蓝牙芯片（MTK / 高通）、不同 OTA 版本可能没有、改名，甚至换个蓝牙包名。
所以 App 启动时会把候选服务都找出来再逐个试：

1. 两个已知组件名（高通 `a2dpsink.mbs.A2dpMediaBrowserService`、AOSP `avrcpcontroller.BluetoothMediaBrowserService`）；
2. 蓝牙相关包（`com.android.bluetooth` / `com.byd.bluetooth` / `com.byd.bt` / `com.qualcomm.bluetooth`）里名字带 `MediaBrowser` 的导出服务；
3. 其他声明了 `android.media.browse.MediaBrowserService` intent 的蓝牙相关服务。

全都没连上就回退通知监听，不会崩溃。诊断日志里能看到「候选 N 个 / 当前候选 / 失败轮次」，换了车机把日志发回来就能判断是服务不存在还是连不上。

### 连接通道（设置 → 连接通道）

有的车机（实测：高通 trinket、Android 10）拿系统自带的「媒体中心」当播放器：它报「正在播放」和进度，
但 MediaSession 里的歌名 / 歌手是空的 —— 歌其实来自蓝牙，元数据在蓝牙栈那边。这时可以手动切通道：

| 通道 | 行为 |
| --- | --- |
| **自动**（默认） | 蓝牙通道有真歌名就用蓝牙，否则读系统媒体会话 |
| **蓝牙** | 只看蓝牙通道 —— 给「媒体中心放蓝牙音乐、系统会话没歌名」的车机用 |
| **系统会话** | 只看播放器自己报的媒体信息（媒体中心 / 网易云 / QQ 音乐） |

手动选「蓝牙」而车机确实没有蓝牙媒体浏览服务时，空界面会直接说明原因并给「改回自动通道」按钮；
主界面左下角状态行也会标出「蓝牙通道 / 蓝牙通道等待连接 / 蓝牙通道不可用」。
环境自查里多了一行「数据通道」，分享日志时一眼就能看出走的是哪条路。

**播放控制的限制**：蓝牙 A2DP Sink 的 `PlaybackState` 通常只声明
`PLAY / PAUSE / SKIP_TO_NEXT` 等动作，不包含 `SEEK_TO`。所以在这类车机上
拖动进度条可能不会跳转，上一首也可能不可用；这是车机蓝牙栈的限制，
原来的通知监听方案同样如此。歌词显示、采集、播放/暂停不受影响。

## 悬浮歌词（桌面歌词）

设置 → 悬浮歌词，把当前几句歌词浮在**导航 / 桌面**上面 —— 高德在底下指路，歌词在上面滚动，两个同时用。

- **开关**：第一次打开要先给「显示在其他应用上层」（`SYSTEM_ALERT_WINDOW`）。本车机（BYD）有这一页：`com.byd.systemsettings/.permission.view.DrawOverlayDetailsActivity`，面板里的「去开启悬浮窗权限」会直接打开它；实在找不到页面时用 adb：`adb shell appops set com.homeweb.lyriccar SYSTEM_ALERT_WINDOW allow`
- **样式**：显示行数（1 / 2 / 3 / 5）、字号（小 / 标准 / 大 / 特大）、当前句颜色（主题色 / 暖黄 / 粉 / 绿 / 橙 / 高对比）、底衬（无 / 淡 / 深）、文字描边（无 / 细 / 粗）；面板里有实时预览。底衬选「无」时建议加「细 / 粗」黑边，浅色地图上也清楚。
- **宽度固定**：窗口宽度是手动设的百分比（30%~95%），不跟歌词长短变；长句在窗口里换行。以前「一句长一句短」会把窗口左右拽，现在不会了。窗口位置按左上角比例存，改宽度、切歌都不会跳。
- **位置**：手指拖动悬浮窗，松手自动记住（按屏幕比例存，换分辨率也能还原）；「回到屏幕上方」一键复位。
- **锁定**：锁定后悬浮窗不再接收点击，直接穿透到下面的导航；要挪位置先回设置里关掉。
- **点一下**悬浮窗回到 LyricCar；LyricCar 自己在界面里时悬浮窗会自动隐藏，不挡设置面板。
- 由前台服务维持（通知栏有一条「悬浮歌词」），App 退到后台、打开导航时歌词继续滚动；没有歌词（未在播放 / 还没采到）时自动隐藏。

### 比亚迪车机要注意

- **「智能加速」会清后台**：BYD 的 `RapidModeService` 在回到桌面时会 force-stop 上一个第三方 App，悬浮歌词会一起消失。把 LyricCar 加进它的**加速白名单**（允许后台运行）才会一直浮着；设置面板里有「打开加速白名单」按钮。
- **开机广播被跳过**：实测 BYD 的 `BroadcastQueue` 直接跳过非白名单 App 的 `BOOT_COMPLETED`（日志 `ssc_skip ... ignored`），所以开机后不会自动恢复。打开一次 LyricCar，悬浮歌词就会按上次的开关回来。
- 其他 ROM 没有这两个限制，`OverlayBootReceiver` 会正常开机拉起。

## 实机验证

已在 BYD 车机（`alps k65v1_64_bsp`，Android 9，1280×720）上实际验证：

- 用同一签名覆盖安装旧 Flutter 版成功，旧歌词库、设置、通知使用权全部保留；
- **撤销通知使用权后**，蓝牙 MediaBrowser 仍能连续读到实时歌词字段、播放位置，并正常采集 / 回放 / 滚动；
- 通过 MediaBrowser 的 MediaController 实测播放 / 暂停可用；
- 重新授予通知使用权后，MediaBrowser 仍是首选来源，没有重复采集；
- 主界面、设置面板、歌词库列表 / 单曲预览、诊断日志面板正常；
- 实放一首蓝牙歌曲时自动识别「歌词写在歌曲名行」，连续采集并同步滚动高亮；
- 导出歌词库 / 诊断日志到系统「下载」目录成功；
- 局域网 HTTP 服务在真机上能返回首页、歌词库 JSON 和诊断日志，关掉面板后端口停止监听；
- 自定义图片背景 + 左栏整块透明：背景图直接铺到左边，控制栏只剩分栏条；「左栏底色」切半透明 / 透明立刻生效，重启后保留；
- 置为竖屏（`adb shell wm size 720x1280`）后自动换成「上歌词 / 下控制栏」：封面、专辑行收起，标题一行放得下，分栏条能上下拖动；
- 歌词库启动清理：真机上 167 首里清掉 5 首「1~3 句、覆盖 0~7 秒」的记录，2 句的纯音乐记录也一并清掉，完整稿和导入稿不动；
- 左栏标题在「歌词写在歌曲名行」的播放器上固定显示歌名（不再跟着歌词跳），歌名从「歌手行 = 歌曲名 - 歌手」里切出来，歌手行同步只显示歌手；
- 连接通道实测：设置里切「蓝牙」后日志记下 `设置：通道=蓝牙`、状态行显示「蓝牙通道」、播放与采集不中断；切「系统会话」（本车未授予通知使用权）时立刻提示「先开一个系统权限」，切回「自动」后歌词立即恢复；通道选择在覆盖安装 / 重启后保留；
- 蓝牙服务发现：本车候选 1 个（`com.android.bluetooth/.a2dpsink.mbs.A2dpMediaBrowserService`），诊断日志列出候选、当前候选和失败轮次；
- 长句换行：把窗口缩到 360×640（`wm size 360x640`）后，`Whatever happens don't let go of my hand` 自动拆成两行显示（两行都完整，没有截断）；设置面板的预览里长句同样换行；
- 外观跟随系统：选「跟随系统」后，`adb shell cmd uimode night yes/no` 切换车机昼夜模式，App 立刻重建为深色 / 浅色（日志 `车机昼夜模式变了：夜间/白天`）—— BYD 会跳过非白名单 App 的 uimode 变更，所以改成了监听 `ui_night_mode`；
- 悬浮歌词：授权后打开开关，回到桌面 / 高德都能看到歌词浮在上面（3 行 / 5 行、字号、暖黄、深底衬逐个切换生效，面板预览同步）；授权页实测能从面板直接打开（BYD `DrawOverlayDetailsActivity`），在页面上打开开关后回到 App 会自动把悬浮歌词开起来；
- 描边 + 固定宽度：底衬「无」+ 描边「粗」压在浅色高德地图上清晰可读；窗口宽度拖到 44% 后长句在窗口内换行，窗口本身不再随歌词长短左右跳；设置里的宽度滑杆和描边 chips 生效、面板预览同步；
- 悬浮窗拖动后松手记住位置，杀掉 App 重开仍在原位置；锁定后点击穿透到导航；单击悬浮窗回到 LyricCar；LyricCar 在前台时悬浮窗隐藏；
- BYD 限制实测：不在「加速白名单」时按 Home 会被 `RapidModeService` 强杀（日志 `kill3rdApp`），加入白名单后正常；开机不会自动恢复（日志 `ssc_skip ... ignored`），打开一次 App 即可。

## 用 adb 打开「通知使用权」

有的车机 ROM（实测 BYD）把「通知使用权」设置页整个删掉了，点按钮只弹「多媒体系统不支持该操作」。这时用 adb：

```bash
# Android 7.0 及以上
adb shell cmd notification allow_listener \
  com.homeweb.lyriccar/com.homeweb.lyriccar.MediaSessionListenerService

# Android 6.0（没有 cmd notification，只能直接写系统设置项）
adb shell settings put secure enabled_notification_listeners \
  com.homeweb.lyriccar/com.homeweb.lyriccar.MediaSessionListenerService
```

确认：

```bash
adb shell settings get secure enabled_notification_listeners
# 输出里应有 com.homeweb.lyriccar/com.homeweb.lyriccar.MediaSessionListenerService
```

撤销：

```bash
adb shell cmd notification disallow_listener \
  com.homeweb.lyriccar/com.homeweb.lyriccar.MediaSessionListenerService
```

`settings put secure` 会覆盖整条设置；如果车上还有别的通知监听器，要把旧的也带上，多个用冒号分隔。

## 给远方的人测（诊断日志）

这套方案能不能在别的车机上用，只有到了那台车上才知道 —— 而帮忙测试的人未必有电脑、也未必会接 adb。所以 App 自带一份诊断日志，让他点两下就能把「现场」发回来。

最新的测试安装包和日志都发到 **QQ 粉丝群 829323767** 里，群里直接讨论。

App 里所有 `[LyricCar]` 日志同时进 logcat 和 `diagnostics.log`。启动 / 回到前台会跑一次环境自查：

```
──── 环境自查 ────
App 版本 2.6.2
✓ 通知使用权：已授予
监听组件：com.homeweb.lyriccar/com.homeweb.lyriccar.MediaSessionListenerService
──── 系统与会话 ────
【系统】Android 9（SDK 28）
【机型】...
【可读会话】4 个
--- 会话 1：com.android.bluetooth ---
  PlaybackState：state=3 position=164581 speed=1.0 actions=54
  元数据 7 项：
    android.media.metadata.ARTIST = 遇见 - 蛋堡
    ...
```

**让他怎么做（照抄给他）：**

1. 装上 App，按界面提示把该开的权限开了（左侧面板一直显示「等待音乐播放」或「先开一个系统权限」就是还没开）；
2. 用车的蓝牙放一首歌，**放半分钟**（让日志里留下播放器真正发出来的东西）；
3. 打开「设置 → 诊断日志」，点「导出到下载」；
4. 把下载目录里的 `lyriccar-log-*.txt` 发回来。

手机和车机在同一个 Wi-Fi 时，第 3 步也可以点「用手机来取」，用手机浏览器打开面板上的地址（页面里第三段就是「下载诊断日志」），文件直接存进手机。

拿到日志先看：

| 日志里的东西              | 说明什么                    | 怎么办                                 |
| ------------------------- | --------------------------- | -------------------------------------- |
| `✗ 通知使用权：未授予`    | 读不到任何别的 App          | 去系统设置里授，或跑 adb               |
| `【可读会话】0 个`        | 没有播放器发布 MediaSession | 这套方案在这台车上用不了               |
| 只有 0~1 个元数据键       | 播放器没开「车载歌词」      | 打开播放器的车载歌词                   |
| `TITLE` 每两秒变一次      | 歌词写在歌曲名行            | 「自动判断」能认，或手动选「歌曲名行」 |
| `ARTIST` 每两秒变一次     | 歌词写在歌手行              | 手动选「歌手行」                       |
| `position` 不动但歌词在变 | 协议栈停止上报位置          | 已自动兜底（只回放、不采集）           |

导出路径：设置 → 诊断日志 → 导出到下载；或打开局域网面板，用手机浏览器取走。

## 支持的最低 Android 版本

`minSdk = 23`（Android 6.0），`targetSdk = 36`。代码里对 Android 10 的 MediaStore 调用做了版本分支；6.0 上走旧的公共「下载」目录 + 运行时存储权限。

## 与旧 Flutter 版的关系

- 功能与数据结构保持一一对应，核心采集规则是从原版逐条移植的；
- UI 用 Android 原生 View 重写（自绘歌词列表 + Dialog 面板），不再是 Flutter Widget；
- 不再包含 Flutter 引擎、Dart 运行时、`flutter` 插件和 `pubspec.yaml`；
- 包名、签名、监听组件名、歌词文件路径都保持不变，可无缝覆盖升级。
