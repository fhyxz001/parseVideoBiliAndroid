# B站视频解析（Android 原生版）

基于 **Kotlin + Jetpack Compose** 的 B 站视频解析工具。粘贴 B 站分享链接、BV 号或 av 号，即可解析出**无水印 MP4 直链**、封面、标题、UP 主、时长与清晰度，并支持一键保存视频 / 封面到系统相册。支持**剪辑下载**：只下载视频中你想要的那一段，省流量省时间。

> 与参考项目 `parseVideoAndroid`（服务端解析 + 客户端展示）不同，本项目把 **B 站解析逻辑完全放在端上**：
> 不依赖任何自建服务器，不内置服务器地址，打开即可用。

## 功能

| 功能 | 说明 |
|------|------|
| 多种输入 | B 站分享文案、`b23.tv` / `bili2233.cn` 短链、网页链接、`BV` 号、`av` 号 |
| 系统分享直达 | 在 B 站 App 里点分享 → 选择本应用，自动填入并解析 |
| 视频信息 | 封面、标题、UP 主头像与昵称、时长、清晰度、分 P 数、文件大小 |
| 播放预览 | 点击封面用 ExoPlayer 全屏预览（带播放控制） |
| 下载视频 | 保存到相册 `DCIM/BiliParser`，显示百分比与已下载 / 总大小 |
| **剪辑下载** | 时间轴选起止时间，只下载所选片段（无损流复制，不转码） |
| 保存封面 | 保存到相册 `Pictures/BiliParser` |
| 复制 | 点击标题复制标题，点击 UP 主复制昵称 |
| 响应式 | 手机 / 平板自适应，浅色 iOS 风格毛玻璃卡片 |

## 解析原理

只使用 B 站公开 Web 接口，**不携带 Cookie、无需登录**：

```
输入（分享文案 / 链接 / BV / av）
  └─ 提取 URL 或视频 ID
       ├─ b23.tv 短链：跟随 302 拿到 www.bilibili.com/video/BVxxxx
       └─ av 号：交给 view 接口用 aid 查询，返回体里带 bvid
  └─ GET api.bilibili.com/x/web-interface/view
        → 标题、封面、UP 主、时长、分 P、cid
  └─ GET api.bilibili.com/x/player/playurl?...&platform=html5
        → durl[0].url（MP4 直链，带时效签名）
  └─ 展示 / 播放 / 下载
```

代码位置：`app/src/main/java/com/videoparser/bili/BiliParser.kt`。

移植自 [parse-video-py](https://github.com/wujunwei928/parse-video-py) 的 `BiliBili` 解析器（MIT），并做了端上适配：

- 图片地址 `http://`、`//` 统一升级为 `https://`
- `av` 号通过 `view?aid=` 自动换取 `bvid`
- 常见接口错误码转成中文提示（稿件不可见、充电专属、风控等）

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
├── MainActivity.kt      # Compose UI：主界面、卡片、按钮、进度、播放器、剪辑页
├── BiliViewModel.kt     # 状态管理：解析、下载、剪辑、缩略图、复制
├── BiliParser.kt        # B 站解析：链接识别、view/playurl 接口、JSON 映射
├── VideoDownloader.kt   # 相册保存：视频 / 封面、Range 下载、进度
├── VideoClipper.kt      # 剪辑编排：moov 探测、Range 片段下载、降级剪切
├── Mp4Boxes.kt          # MP4 元数据解析（纯 Kotlin）
└── Mp4Clipper.kt        # 剪辑核心（纯 Kotlin）：容器重建、box 写入
app/src/test/java/com/videoparser/bili/
├── BiliParserTest.kt        # 链接识别、JSON 映射、错误文案、时长格式
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

- 未登录只能拿到 **720P**（`platform=html5` 接口的上限）；1080P+ / 4K 需要大会员 Cookie，本项目不做登录
- 只解析 **P1**；多 P 视频会在信息栏显示「P1 / 共 N P」，剪辑同样只针对 P1
- 番剧、付费课程、充电专属、互动视频通常没有 `durl`，会给出明确错误提示
- 直链带时效签名（约 2 小时），解析后请尽快播放 / 下载
- 剪辑起点按关键帧对齐，起点可能有几秒偏差（无损流复制的固有特性）
- 服务器不支持 Range 时会降级为全量下载再剪切，此时不会省流量
- 无下载历史、无断点续传、暂不支持深色模式
- 界面文案为硬编码中文

## 免责声明

本项目仅用于个人学习与技术研究。请勿用于商业用途或大规模抓取；下载的内容版权归原作者及平台所有，请遵守 B 站用户协议与相关法律法规。
