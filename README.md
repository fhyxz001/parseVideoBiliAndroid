# B站视频解析（Android 原生版）

基于 **Kotlin + Jetpack Compose** 的 B 站视频解析工具。粘贴 B 站分享链接、BV 号或 av 号，即可解析出**无水印 MP4 直链**、封面、标题、UP 主、时长与清晰度，并支持一键保存视频 / 封面到系统相册。支持**剪辑下载**：只下载视频中你想要的那一段，省流量省时间。

> 与参考项目 `parseVideoAndroid`（服务端解析 + 客户端展示）不同，本项目把 **B 站解析逻辑完全放在端上**：
> 不依赖任何自建服务器，不内置服务器地址，打开即可用。

## 功能

| 功能 | 说明 |
|------|------|
| 多种输入 | B 站分享文案、`b23.tv` / `bili2233.cn` 短链、网页链接、`BV` 号、`av` 号 |
| 系统分享直达 | 在 B 站 App 里点分享 → 选择本应用，自动填入并解析 |
| **Cookie 登录 / 1080P** | 配置 Cookie 后按账号权限自动获取 1080P / 1080P+ / 1080P60，大会员可到 4K / HDR |
| 视频信息 | 封面、标题、UP 主头像与昵称、时长、清晰度、分 P 数、文件大小 |
| 播放预览 | 点击封面用 ExoPlayer 全屏预览（带播放控制） |
| 下载视频 | 保存到相册 `DCIM/BiliParser`，显示百分比与已下载 / 总大小 |
| **剪辑下载** | 时间轴选起止时间，只下载所选片段（无损流复制，不转码） |
| 保存封面 | 保存到相册 `Pictures/BiliParser` |
| 复制 | 点击标题复制标题，点击 UP 主复制昵称 |
| 响应式 | 手机 / 平板自适应，浅色 iOS 风格毛玻璃卡片 |

## 配置 Cookie · 1080P 高清

游客模式只能拿到 **720P**（`platform=html5` 接口的上限）。点击首页右上角 **Cookie**（或提示条「配置 Cookie 解锁 1080P」）进入设置页，粘贴浏览器里的 Cookie 后即可按账号权限解析更高清晰度。

**拿 Cookie 最快的办法**

1. 电脑 Chrome 登录 [bilibili.com](https://www.bilibili.com/)，F12 → Network → 刷新页面
2. 点任意 `bilibili.com` 请求 → Request Headers → 复制整行 `Cookie:`
3. 发到手机（微信 / 文件传输助手），在设置页粘贴 → **保存并应用**

**支持的粘贴格式**（自动清洗，夹带说明文字、换行、引号、JSON 都能识别）

```
SESSDATA=xxx; bili_jct=yyy; buvid3=zzz; DedeUserID=12345
{"SESSDATA": "xxx", "bili_jct": "yyy"}
```

**实现要点**

- `SESSDATA` 是登录凭证；缺少 `buvid3` 时 App 会自动生成并保存一个设备指纹，保证 Cookie 可被服务端校验
- 解析参数从 `platform=html5` 切换为 `platform=pc&fnval=1&high_quality=1&fourk=1`
- 服务端不会因 `qn` 过高报错，而是**静默回落到账号 / 视频可达的最高档**：App 先请求比上次更高的档位，返回低于请求档位即视为上限并停止，通常 1~2 次请求确定画质
- 记住上次实际拿到的清晰度，下次解析直接从该档位起步
- CDN 直链统一带 `Referer` / `Origin` / `User-Agent`（下载、剪辑 Range 请求、预览、缩略图全部覆盖），否则 upos 会返回 403
- Cookie 仅存于应用私有 `SharedPreferences`，不上传任何服务器；「退出登录 / 清除」随时删除
- 1080P 及以上需要**登录**；4K / HDR / 杜比需要**大会员**；视频本身没上传高清时无法凭空得到

## 解析原理

只使用 B 站公开 Web 接口，游客模式无需登录；配置 Cookie 后请求会自动携带：

```
输入（分享文案 / 链接 / BV / av）
  └─ 提取 URL 或视频 ID
       ├─ b23.tv 短链：跟随 302 拿到 www.bilibili.com/video/BVxxxx
       └─ av 号：交给 view 接口用 aid 查询，返回体里带 bvid
  └─ GET api.bilibili.com/x/web-interface/view
        → 标题、封面、UP 主、时长、分 P、cid
  └─ GET api.bilibili.com/x/player/playurl
       ├─ 游客：platform=html5&qn=64                          → 最高 720P
       └─ 登录：platform=pc&fnval=1&qn=112/80/116...          → 按权限取最高
        → durl[0].url（MP4 直链，带时效签名）；仅返回 DASH 时退化为视频流地址
  └─ 展示 / 播放 / 下载（带 Referer、Origin、UA）
```

代码位置：`app/src/main/java/com/videoparser/bili/BiliParser.kt`、`BiliCookie.kt`、`BiliCookieStore.kt`。

移植自 [parse-video-py](https://github.com/wujunwei928/parse-video-py) 的 `BiliBili` 解析器（MIT），并做了端上适配：

- 图片地址 `http://`、`//` 统一升级为 `https://`
- `av` 号通过 `view?aid=` 自动换取 `bvid`
- 常见接口错误码转成中文提示（稿件不可见、充电专属、风控、登录失效等）

## 剪辑下载

解析成功后点击 **剪辑下载**，在时间轴上选好起止时间即可只下载所选片段，交互与参考项目 `parseVideoAndroid` 一致：

- **时间轴缩略图**：自动抽取 16 个关键帧拼成时间轴
- **双滑块选区**：拖动两端把手、点击时间轴微调、双指缩放/平移、双击复位
- **关键帧对齐**：起点自动吸附到最近关键帧，并提示实际剪出的区间
- **首尾帧预览**：实时显示起点/终点画面，点击可保存该帧图片
- **快捷时长**：20 / 60 / 120 秒，或自定义秒数；开始时间支持精确输入
- **预计大小**：按所选时长估算下载体积
- **省流量**：HTTP Range 只下载所选时间段的字节（20 秒片段通常只有几 MB）
- **无损输出**：流复制不转码，画质无损失
- **可取消**：剪辑过程中可随时取消

技术细节（面向开发者）：

- `Mp4Boxes.kt`：解析 `moov` 中的采样表（stts/stss/stsc/stsz/stco/ctts），建立"时间 → 字节偏移"索引
- `Mp4Clipper.kt`：起点吸附关键帧，裁剪采样表，重建 `ftyp + moov + mdat`（faststart），重定位 chunk 偏移
- `VideoClipper.kt`：先探测头部/尾部 moov；服务器不支持 Range、分片 MP4 或解析失败时，自动降级为"全量下载 + 系统 `MediaExtractor`/`MediaMuxer` 剪切"
- B 站 CDN 的 mp4 一般支持 Range；直链带时效，解析后请尽快操作

## 技术栈

- Kotlin 2.0.21 / AGP 8.5.2 / Gradle 8.9
- Jetpack Compose（BOM 2024.09.03）+ Material3
- OkHttp 4.12（网络）、Coil 2.7（图片）
- Media3 ExoPlayer 1.4.1（预览播放）
- minSdk 24 / targetSdk 34

## 项目结构

```
app/src/main/java/com/videoparser/bili/
├── MainActivity.kt      # Compose UI：主界面、卡片、按钮、进度、播放器、Cookie 设置页、剪辑页
├── BiliViewModel.kt     # 状态管理：解析、下载、剪辑、缩略图、复制、Cookie 登录态
├── BiliParser.kt        # B 站解析：链接识别、view/playurl 接口、清晰度策略、JSON 映射
├── BiliCookie.kt        # Cookie 清洗 / 登录态 / 设备指纹 / 清晰度偏好（纯 Kotlin）
├── BiliCookieStore.kt   # Cookie 与清晰度偏好的 SharedPreferences 持久化
├── VideoDownloader.kt   # 相册保存：视频 / 封面、Range 下载、CDN 请求头、进度
├── VideoClipper.kt      # 剪辑编排：moov 探测、Range 片段下载、降级剪切
├── Mp4Boxes.kt          # MP4 元数据解析（纯 Kotlin）
└── Mp4Clipper.kt        # 剪辑核心（纯 Kotlin）：容器重建、box 写入
app/src/test/java/com/videoparser/bili/
├── BiliParserTest.kt        # 链接识别、JSON 映射、错误文案、时长格式
├── BiliCookieTest.kt        # Cookie 清洗 / 设备指纹 / 脱敏 / 清晰度策略 / playurl 映射
├── Mp4ClipTest.kt           # MP4 解析与剪辑数学
└── VideoDownloaderTest.kt   # 文件名清洗
```

## 构建

用 Android Studio 打开项目根目录，等待 Gradle 同步后直接 Run；或命令行：

```bash
# 需要 JDK 17+、Android SDK 34
gradle :app:assembleDebug
# 或
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

单元测试（纯 JVM，不需要设备）：

```bash
gradle :app:testDebugUnitTest
```

默认的单元测试不会访问网络。想跑真实的 B 站接口集成测试：

```bash
# macOS / Linux
BILI_NETWORK_TEST=1 ./gradlew :app:testDebugUnitTest --tests "com.videoparser.bili.BiliParserNetworkTest"

# Windows PowerShell
$env:BILI_NETWORK_TEST='1'; .\gradlew.bat :app:testDebugUnitTest --tests "com.videoparser.bili.BiliParserNetworkTest"
```

> 国内网络下载 Gradle 发行包可能很慢。若 `gradlew` 卡在下载，可把
> `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 换成
> `https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`（或直接用 Android Studio 自带的 Gradle）。

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 请求 B 站接口、下载视频与封面 |
| `ACCESS_NETWORK_STATE` | 网络状态 |
| `WRITE_EXTERNAL_STORAGE`（maxSdkVersion=28） | Android 9 及以下写入相册 |

Android 10+ 通过 `MediaStore` 写入，不需要存储权限。

## 已知限制

- 未配置 Cookie 只能拿到 **720P**（`platform=html5` 接口的上限）；配置 Cookie 后可到 1080P / 1080P+ / 1080P60，4K / HDR / 杜比需要 **大会员账号**，番剧等付费内容仍可能受限
- 清晰度上限同时受视频本身限制：UP 主只上传了 720P 时，登录也无法得到 1080P
- 登录状态的稳定性取决于所粘贴 Cookie 与设备指纹是否配套；如出现「登录状态无效」提示，重新登录并粘贴新的 Cookie
- 只解析 **P1**；多 P 视频会在信息栏显示「P1 / 共 N P」，剪辑同样只针对 P1
- 番剧、付费课程、充电专属、互动视频通常没有 `durl`，会给出明确错误提示
- 直链带时效签名（约 2 小时），解析后请尽快播放 / 下载
- 剪辑起点按关键帧对齐，起点可能有几秒偏差（无损流复制的固有特性）
- 服务器不支持 Range 时会降级为全量下载再剪切，此时不会省流量
- 无下载历史、无断点续传、暂不支持深色模式
- 界面文案为硬编码中文

## 免责声明

本项目仅用于个人学习与技术研究。请勿用于商业用途或大规模抓取；下载的内容版权归原作者及平台所有，请遵守 B 站用户协议与相关法律法规。Cookie 属于个人登录凭证，仅保存在本机，请勿分享给他人。
