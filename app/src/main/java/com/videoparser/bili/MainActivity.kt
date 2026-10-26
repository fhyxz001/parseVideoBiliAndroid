package com.videoparser.bili

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset

class MainActivity : ComponentActivity() {

    private val viewModel: BiliViewModel by viewModels()

    /** 从系统分享进来的文案，Compose 侧消费后置空 */
    private var sharedText by mutableStateOf<String?>(null)

    private var pendingStorageAction: (() -> Unit)? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(this, "需要存储权限才能保存到相册", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 配置变更重建时不要重复消费同一个分享 Intent
        sharedText = if (savedInstanceState == null) sharedTextFrom(intent) else null

        setContent {
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                viewModel.toastEvents.collectLatest { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
            LaunchedEffect(Unit) {
                viewModel.copyEvents.collectLatest { text ->
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    manager.setPrimaryClip(ClipData.newPlainText("bilibili", text))
                }
            }
            LaunchedEffect(sharedText) {
                sharedText?.let { text ->
                    viewModel.onSharedText(text)
                    sharedText = null
                }
            }
            // 播放页是黑底，状态栏图标需要切成浅色
            LaunchedEffect(viewModel.showPlayer) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !viewModel.showPlayer
                controller.isAppearanceLightNavigationBars = !viewModel.showPlayer
            }

            BiliScreen(
                vm = viewModel,
                requestStorage = { action -> withStoragePermission(action) },
                requestCookie = { viewModel.openCookiePage() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedTextFrom(intent)?.let { sharedText = it }
    }

    private fun sharedTextFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }

    private fun withStoragePermission(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingStorageAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

/* ═══════════════════════ iOS 设计系统 · 颜色 ═══════════════════════ */

private val iOSBlue = Color(0xFF007AFF)
private val iOSBlueLight = Color(0xFF5AC8FA)
private val iOSGreen = Color(0xFF34C759)
private val iOSPurple = Color(0xFFAF52DE)

// B 站品牌粉，只用于品牌标记与强调，主操作仍沿用参考项目的 iOS 蓝
private val BiliPink = Color(0xFFFB7299)
private val BiliPinkDeep = Color(0xFFE45C86)

private val iOSLabel = Color(0xFF1C1C1E)
private val iOSSecondary = Color(0xFF3C3C43).copy(alpha = 0.6f)
private val iOSTertiary = Color(0xFF3C3C43).copy(alpha = 0.3f)
private val iOSSeparator = Color(0xFF3C3C43).copy(alpha = 0.15f)

// 剪辑页沿用参考项目的底色与媒体画布
private val iOSCardBg = Color(0xFFFFFFFF)
private val iOSBgBase = Color(0xFFF2F2F7)
private val MediaSurface = Color(0xFF16161C)

private val GlassWhite = Color(0xFFFFFFFF).copy(alpha = 0.82f)
private val GlassBorder = Color(0xFFFFFFFF).copy(alpha = 0.55f)
private val GlassShadow = Color(0xFF000000).copy(alpha = 0.04f)
private val DisabledBlue = Color(0xFFB0C4DE)

private val BgGradient = Brush.verticalGradient(
    listOf(
        Color(0xFFF9F9FC),
        Color(0xFFF2F2F7),
        Color(0xFFEDEDF5),
        Color(0xFFF5F5FA)
    )
)

/* ═══════════════════════ 响应式尺寸 ═══════════════════════ */

@Composable
private fun responsivePadding(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp >= 600 -> 48.dp
        widthDp >= 400 -> 24.dp
        else -> 16.dp
    }
}

@Composable
private fun cardMaxWidth(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return if (widthDp >= 600) 560.dp else Dp.Unspecified
}

/* ═══════════════════════ 主界面 ═══════════════════════ */

@Composable
fun BiliScreen(
    vm: BiliViewModel,
    requestStorage: (() -> Unit) -> Unit,
    requestCookie: () -> Unit
) {
    val pad = responsivePadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pad, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(loggedIn = vm.loggedIn, onOpenCookie = requestCookie)

            Spacer(Modifier.height(14.dp))

            InputCard(
                videoUrl = vm.input,
                onUrlChanged = vm::onInputChanged,
                onSearch = vm::parse
            )

            Spacer(Modifier.height(12.dp))

            PressButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = cardMaxWidth()),
                onClick = vm::pasteAndParse,
                background = if (!vm.isParsing) iOSBlue else DisabledBlue,
                shadowColor = if (!vm.isParsing) iOSBlue.copy(alpha = 0.3f) else Color.Transparent,
                contentColor = Color.White,
                enabled = !vm.isParsing
            ) {
                Text(
                    when {
                        vm.isParsing -> "解析中..."
                        vm.input.isNotBlank() -> "开始解析"
                        else -> "粘贴并解析"
                    },
                    color = Color.White.copy(alpha = if (!vm.isParsing) 1f else 0.7f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.25.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            QualityHint(vm, onOpenCookie = requestCookie)

            Spacer(Modifier.height(8.dp))

            Text(
                text = "支持 b23.tv 短链、BV 号与 av 号",
                color = iOSTertiary,
                fontSize = 12.sp,
                letterSpacing = 0.1.sp
            )

            Spacer(Modifier.height(16.dp))

            val data = vm.video
            if (data != null) {
                ResultSection(vm, data, requestStorage, requestCookie)
            } else {
                EmptyState(onOpenCookie = requestCookie)
            }

            Spacer(Modifier.height(20.dp))
        }

        // 解析中遮罩
        AnimatedVisibility(
            visible = vm.isParsing,
            enter = fadeIn(spring()),
            exit = fadeOut(spring())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(iOSLabel.copy(alpha = 0.35f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                GlassCardCompact {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(40.dp)
                    ) {
                        CircularProgressIndicator(
                            color = iOSBlue,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "解析中...",
                            color = iOSLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        val current = vm.video
        if (vm.showPlayer && current != null) {
            PlayerDialog(current) { vm.dismissPlayer() }
        }

        // 剪辑页：全屏覆盖，从右侧滑入
        AnimatedVisibility(
            visible = vm.showClipPage,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            ClipPage(vm, requestStorage)
        }

        // 清晰度 / Cookie 设置页：全屏覆盖，从右侧滑入
        AnimatedVisibility(
            visible = vm.showCookiePage,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            CookiePage(vm) { message -> vm.showToast(message) }
        }
    }
}

/**
 * 主界面顶部图标按钮（与品牌角标对称）：已配置 Cookie 时显示已解锁 1080P 的状态。
 */
@Composable
private fun Header(
    loggedIn: Boolean,
    onOpenCookie: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = cardMaxWidth()),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "B站视频解析",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = iOSLabel,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (loggedIn) "已登录 · 支持 1080P 高清" else "原画直链 · 一键存相册",
                fontSize = 12.sp,
                color = if (loggedIn) iOSGreen else iOSSecondary,
                letterSpacing = 0.3.sp
            )
        }

        // 右上：账号 / Cookie 设置入口
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(if (loggedIn) iOSGreen.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.7f))
                .border(
                    width = 1.dp,
                    color = if (loggedIn) iOSGreen.copy(alpha = 0.35f) else iOSSeparator,
                    shape = CircleShape
                )
                .clickable(interactionSource = null, indication = null, onClick = onOpenCookie)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (loggedIn) iOSGreen else iOSTertiary)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (loggedIn) "已登录" else "Cookie",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (loggedIn) iOSGreen else iOSSecondary
                )
            }
        }

        // 品牌角标（放在右上角按钮下方，避免遮挡）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = 34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(BiliPink, BiliPinkDeep)))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Text(
                text = "bilibili",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp
            )
        }
    }
}

/**
 * 清晰度提示条：
 * - 游客：提醒配置 Cookie 可解锁 1080P（可一键打开设置）
 * - 登录：显示本次实际拿到的清晰度（解析后）
 */
@Composable
private fun QualityHint(vm: BiliViewModel, onOpenCookie: () -> Unit) {
    val loggedIn = vm.loggedIn
    val video = vm.video
    val highQuality = video != null && video.quality >= BiliParser.QUALITY_1080P

    val text: String
    val color: Color
    val clickable: Boolean
    when {
        loggedIn && highQuality -> {
            text = "当前清晰度 ${video!!.qualityLabel} · 已解锁高清"
            color = iOSGreen
            clickable = false
        }
        loggedIn && video != null -> {
            text = "当前清晰度 ${video.qualityLabel} · 该视频暂无更高画质"
            color = iOSSecondary
            clickable = false
        }
        loggedIn -> {
            text = "已登录，1080P 及以上清晰度可用"
            color = iOSGreen
            clickable = false
        }
        else -> {
            text = "未登录仅 720P，配置 Cookie 解锁 1080P ›"
            color = iOSBlue
            clickable = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = cardMaxWidth())
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .then(
                if (clickable) {
                    Modifier.clickable(interactionSource = null, indication = null, onClick = onOpenCookie)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (highQuality) "HD" else "HD",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = color,
                textAlign = TextAlign.Center
            )
        }
    }
}

/* ═══════════════════════ 输入框 ═══════════════════════ */

@Composable
private fun InputCard(
    videoUrl: String,
    onUrlChanged: (String) -> Unit,
    onSearch: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val borderColor by animateColorAsState(
        if (focused) iOSBlue else iOSSeparator,
        animationSpec = tween(250),
        label = "border"
    )
    val elevation by animateDpAsState(
        if (focused) 6.dp else 2.dp,
        animationSpec = tween(250),
        label = "elevation"
    )
    val bgColor by animateColorAsState(
        if (focused) Color.White else Color(0xFFF8F8FC),
        animationSpec = tween(250),
        label = "bg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = cardMaxWidth())
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(20.dp))
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(20.dp),
                ambientColor = GlassShadow,
                spotColor = GlassShadow
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = videoUrl,
                onValueChange = onUrlChanged,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = TextStyle(
                    color = iOSLabel,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(iOSBlue),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        onSearch()
                    }
                ),
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (videoUrl.isEmpty()) {
                            Text(
                                text = "粘贴 B 站链接或 BV 号",
                                color = iOSTertiary,
                                fontSize = 15.sp
                            )
                        }
                        inner()
                    }
                }
            )

            if (videoUrl.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(iOSTertiary.copy(alpha = 0.18f))
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { onUrlChanged("") }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", fontSize = 11.sp, color = iOSSecondary)
                }
            }
        }
    }
}

/* ═══════════════════════ 结果区 ═══════════════════════ */

@Composable
private fun ResultSection(
    vm: BiliViewModel,
    data: BiliVideo,
    requestStorage: (() -> Unit) -> Unit,
    requestCookie: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = cardMaxWidth())
    ) {
        VideoCard(
            data = data,
            onPlay = vm::openPlayer,
            onCopyTitle = vm::copyTitle,
            onCopyAuthor = vm::copyAuthor,
            onSaveCover = { if (!vm.isSavingCover) requestStorage { vm.saveCover() } },
            savingCover = vm.isSavingCover
        )

        Spacer(Modifier.height(10.dp))

        val meta = buildList {
            if (data.durationSec > 0) add(BiliParser.formatDuration(data.durationSec))
            if (data.qualityLabel.isNotBlank()) add(data.qualityLabel)
            add(if (data.pageCount > 1) "P1 / 共${data.pageCount}P" else "单P")
            if (vm.fileSize > 0) add(formatFileSize(vm.fileSize))
        }
        Text(
            text = meta.joinToString("  ·  "),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = iOSSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        // 游客态：提示可升级
        if (!data.loggedIn && data.quality <= BiliParser.QUALITY_GUEST_MAX && !vm.loggedIn) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .background(iOSGreen.copy(alpha = 0.12f))
                    .clickable(interactionSource = null, indication = null, onClick = requestCookie)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "配置 Cookie 可获取 1080P ›",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = iOSGreen
                )
            }
        }
        // 已登录但本视频仍只有低清：说明是视频本身 / 大会员限制
        if (data.loggedIn && data.quality <= BiliParser.QUALITY_GUEST_MAX) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "该视频最高只有 ${data.qualityLabel}，1080P 需要 UP 主上传高清或大会员权限",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = iOSTertiary,
                lineHeight = 16.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = cardMaxWidth()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PressButton(
                modifier = Modifier.weight(1f),
                height = 50.dp,
                onClick = { if (!vm.isDownloading) requestStorage { vm.download() } },
                background = if (!vm.isDownloading) iOSGreen else DisabledBlue,
                shadowColor = if (!vm.isDownloading) iOSGreen.copy(alpha = 0.3f) else Color.Transparent,
                contentColor = Color.White,
                enabled = !vm.isDownloading
            ) {
                Text(
                    text = if (vm.isDownloading) "下载中..." else "下载视频",
                    color = Color.White.copy(alpha = if (!vm.isDownloading) 1f else 0.7f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.25.sp
                )
            }

            PressButton(
                modifier = Modifier.weight(1f),
                height = 50.dp,
                onClick = vm::openClipPage,
                background = Color(0xFFE9F2FF),
                shadowColor = Color.Transparent,
                contentColor = iOSBlue
            ) {
                Text(
                    text = "剪辑下载",
                    color = iOSBlue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.25.sp
                )
            }
        }

        AnimatedVisibility(
            visible = vm.isDownloading,
            enter = expandVertically(spring()) + fadeIn(),
            exit = shrinkVertically(spring()) + fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                ProgressSection(
                    percent = vm.downloadPercent,
                    downloadedBytes = vm.downloadedBytes,
                    totalBytes = vm.downloadTotalBytes,
                    knownFileSize = vm.fileSize
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    data: BiliVideo,
    onPlay: () -> Unit,
    onCopyTitle: () -> Unit,
    onCopyAuthor: () -> Unit,
    onSaveCover: () -> Unit,
    savingCover: Boolean
) {
    GlassCard {
        Column {
            if (data.coverUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFE9E9F0))
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onPlay
                        )
                ) {
                    AsyncImage(
                        model = data.coverUrl,
                        contentDescription = "视频封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // 底部渐变，保证时长徽标可读
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))
                                )
                            )
                    )

                    // 播放按钮
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "▶",
                            color = Color.White,
                            fontSize = 20.sp,
                            modifier = Modifier.offset(x = 1.dp)
                        )
                    }

                    // 保存封面（独立点击区域，不触发播放）
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                onClick = onSaveCover
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (savingCover) "保存中" else "存封面",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (data.durationSec > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = BiliParser.formatDuration(data.durationSec),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Text(
                text = data.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = iOSLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp,
                letterSpacing = (-0.25).sp,
                modifier = Modifier.clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onCopyTitle
                )
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (data.author.avatar.isNotBlank()) {
                    AsyncImage(
                        model = data.author.avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE9E9F0))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(iOSBlue, iOSPurple))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = data.author.name.firstOrNull()?.toString() ?: "B",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "@${data.author.name}",
                    color = iOSSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onCopyAuthor
                    )
                )

                Spacer(Modifier.weight(1f))

                Text(
                    text = data.bvid,
                    color = iOSTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/* ═══════════════════════ 下载进度 ═══════════════════════ */

@Composable
private fun ProgressSection(
    percent: Int,
    downloadedBytes: Long,
    totalBytes: Long,
    knownFileSize: Long
) {
    val actualTotal = if (totalBytes > 0) totalBytes else knownFileSize
    val hasTotal = actualTotal > 0
    val displayPercent = if (hasTotal) {
        if (percent >= 0) percent else (downloadedBytes * 100 / actualTotal).toInt().coerceIn(0, 100)
    } else {
        -1
    }

    GlassCard {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "正在下载视频...",
                    color = iOSLabel,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    letterSpacing = (-0.2).sp
                )
                Text(
                    text = if (displayPercent >= 0) "$displayPercent%" else "",
                    color = iOSBlue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            if (downloadedBytes > 0 || hasTotal) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (hasTotal) {
                        "${formatFileSize(downloadedBytes)} / ${formatFileSize(actualTotal)}"
                    } else {
                        "已下载 ${formatFileSize(downloadedBytes)}"
                    },
                    color = iOSSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (displayPercent >= 0) 8.dp else 20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE5E5EA))
            ) {
                if (displayPercent >= 0) {
                    val animatedPercent by animateFloatAsState(
                        displayPercent / 100f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                        label = "progress"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedPercent)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.horizontalGradient(listOf(iOSBlue, iOSBlueLight)))
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(16.dp),
                        color = iOSBlue,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

/* ═══════════════════════ 空状态 ═══════════════════════ */

@Composable
private fun EmptyState(onOpenCookie: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = cardMaxWidth())
            .padding(top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                BiliPink.copy(alpha = 0.10f),
                                iOSPurple.copy(alpha = 0.12f)
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            listOf(BiliPink.copy(alpha = 0.22f), iOSPurple.copy(alpha = 0.18f))
                        ),
                        shape = CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(BiliPink.copy(alpha = 0.20f), BiliPinkDeep.copy(alpha = 0.12f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("📺", fontSize = 32.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "粘贴 B 站视频链接",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = iOSLabel,
            letterSpacing = (-0.3).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "解析出无水印直链，支持保存到相册",
            fontSize = 14.sp,
            color = iOSSecondary,
            letterSpacing = (-0.1).sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "也可以从 B 站 App 直接分享过来",
            fontSize = 13.sp,
            color = iOSTertiary
        )
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(iOSBlue.copy(alpha = 0.10f))
                .border(1.dp, iOSBlue.copy(alpha = 0.20f), RoundedCornerShape(50))
                .clickable(interactionSource = null, indication = null, onClick = onOpenCookie)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "配置 Cookie 解锁 1080P",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = iOSBlue
            )
        }
    }
}

/* ═══════════════════════ 按钮 ═══════════════════════ */

@Composable
private fun PressButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    background: Color,
    shadowColor: Color,
    contentColor: Color,
    enabled: Boolean = true,
    height: Dp = 54.dp,
    content: @Composable () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Box(
        modifier = modifier
            .height(height)
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .shadow(
                elevation = if (enabled) 6.dp else 0.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {
                            pressed = true
                            onClick()
                        }
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }

    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(150)
            pressed = false
        }
    }
}

/* ═══════════════════════ 播放器 ═══════════════════════ */

@Composable
private fun PlayerDialog(video: BiliVideo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember(video.videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(video.videoUrl))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onDismiss
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

/* ═══════════════════════ Cookie 设置页 ═══════════════════════ */

/**
 * 全屏 Cookie 设置页。
 *
 * 说明列表直接告诉用户「去哪里拿 Cookie」，粘贴框支持整段 Cookie 头，
 * 保存后自动清洗（去掉说明文字 / 引号 / 换行）并补齐设备指纹 buvid3。
 */
@Composable
private fun CookiePage(vm: BiliViewModel, onToast: (String) -> Unit) {
    BackHandler(enabled = vm.showCookiePage) { vm.closeCookiePage() }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgGradient)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = cardMaxWidth())
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(iOSCardBg)
                        .border(1.dp, iOSSeparator, CircleShape)
                        .clickable(interactionSource = null, indication = null, onClick = vm::closeCookiePage),
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 18.sp, color = iOSLabel, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "清晰度 / Cookie",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = iOSLabel,
                        letterSpacing = (-0.4).sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "配置登录 Cookie 后即可解析 1080P / 4K",
                        fontSize = 12.sp,
                        color = iOSSecondary
                    )
                }
                StatusPill(loggedIn = vm.loggedIn)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                CookieHowToCard()

                Spacer(Modifier.height(12.dp))

                // 已保存状态
                if (vm.hasSavedCookie) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(iOSCardBg)
                            .border(1.dp, iOSSeparator, RoundedCornerShape(20.dp))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(iOSGreen)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "当前已保存的 Cookie",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = iOSLabel
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            vm.cookieMask.ifBlank { "—" },
                            fontSize = 12.sp,
                            color = iOSSecondary,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFFF3B30).copy(alpha = 0.08f))
                                    .border(1.dp, Color(0xFFFF3B30).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .clickable(interactionSource = null, indication = null, onClick = vm::clearCookie),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "退出登录 / 清除",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFFF3B30)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(iOSBgBase)
                                    .border(1.dp, iOSSeparator, RoundedCornerShape(12.dp))
                                    .clickable(interactionSource = null, indication = null) {
                                        vm.pasteCookieFromClipboard()?.let { text ->
                                            vm.onCookieInputChanged(text)
                                            focusManager.clearFocus()
                                        } ?: onToast("剪贴板里没有内容")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "从剪贴板粘贴",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = iOSLabel
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 输入框
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(iOSCardBg)
                        .border(1.dp, iOSSeparator, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        "粘贴 Cookie",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = iOSLabel
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "整段粘贴即可：SESSDATA=xxx; bili_jct=yyy; buvid3=zzz",
                        fontSize = 11.sp,
                        color = iOSTertiary,
                        lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    BasicTextField(
                        value = vm.cookieInput,
                        onValueChange = vm::onCookieInputChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(iOSBgBase)
                            .border(1.dp, iOSSeparator, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        textStyle = TextStyle(
                            color = iOSLabel,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        cursorBrush = SolidColor(iOSBlue),
                        decorationBox = { inner ->
                            Box {
                                if (vm.cookieInput.isEmpty()) {
                                    Text(
                                        "在这里粘贴 B 站 Cookie…",
                                        color = iOSTertiary,
                                        fontSize = 12.sp
                                    )
                                }
                                inner()
                            }
                        }
                    )

                    vm.cookieError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠ $error",
                            fontSize = 12.sp,
                            color = Color(0xFFFF3B30),
                            lineHeight = 17.sp
                        )
                    }
                    vm.cookieStatusMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "✓ $message",
                            fontSize = 12.sp,
                            color = iOSGreen,
                            lineHeight = 17.sp
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PressButton(
                            modifier = Modifier.weight(1f),
                            height = 46.dp,
                            onClick = vm::saveCookie,
                            background = iOSBlue,
                            shadowColor = iOSBlue.copy(alpha = 0.3f),
                            contentColor = Color.White
                        ) {
                            Text(
                                "保存并应用",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(iOSBgBase)
                                .border(1.dp, iOSSeparator, RoundedCornerShape(16.dp))
                                .clickable(interactionSource = null, indication = null) {
                                    val text = vm.pasteCookieFromClipboard()
                                    if (text == null) {
                                        onToast("剪贴板里没有内容")
                                    } else {
                                        vm.onCookieInputChanged(text)
                                        focusManager.clearFocus()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "粘贴",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = iOSLabel
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StatusPill(loggedIn: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (loggedIn) iOSGreen.copy(alpha = 0.14f) else iOSTertiary.copy(alpha = 0.16f)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = if (loggedIn) "已登录" else "游客",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (loggedIn) iOSGreen else iOSSecondary
        )
    }
}

/** 「怎么拿 Cookie」步骤说明 */
@Composable
private fun CookieHowToCard() {
    val steps = listOf(
        "手机浏览器打开 bilibili.com 并登录" to "推荐用浏览器登录，网页版 Cookie 有效期更稳定",
        "复制时不要漏掉 SESSDATA" to "SESSDATA 是登录凭证；bili_jct、buvid3 有就一起带上",
        "回到这里粘贴并保存" to "保存后重新解析，1080P / 1080P+ / 4K 按账号权限自动选择"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(iOSCardBg)
            .border(1.dp, iOSSeparator, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔐", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "如何获取 Cookie",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = iOSLabel
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "用当前手机登录时更稳妥：电脑 Chrome 复制 → 发到手机 → 粘贴到这里",
            fontSize = 11.sp,
            color = iOSSecondary,
            lineHeight = 16.sp
        )
        steps.forEachIndexed { index, (title, detail) ->
            Spacer(Modifier.height(14.dp))
            Row {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(iOSBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${index + 1}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = iOSBlue
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = iOSLabel)
                    Spacer(Modifier.height(2.dp))
                    Text(detail, fontSize = 11.sp, color = iOSSecondary, lineHeight = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(iOSBgBase)
                .padding(12.dp)
        ) {
            Text(
                text = "「开发者工具」获取方式：电脑 Chrome 登录 bilibili.com → F12 → Network → " +
                    "刷新页面 → 点任意 bilibili.com 请求 → Request Headers 里的 Cookie 一行整段复制。\n" +
                    "Cookie 只保存在本机应用私有目录，不会上传；清楚 1080P 需登录，4K / HDR 需大会员。",
                fontSize = 11.sp,
                color = iOSSecondary,
                lineHeight = 17.sp
            )
        }
    }
}

/* ═══════════════════════ 通用 ═══════════════════════ */

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = cardMaxWidth())
            .clip(RoundedCornerShape(24.dp))
            .background(GlassWhite)
            .border(0.5.dp, GlassBorder, RoundedCornerShape(24.dp))
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = GlassShadow,
                spotColor = GlassShadow
            )
            .padding(20.dp)
    ) {
        content()
    }
}

@Composable
private fun GlassCardCompact(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassWhite)
            .border(0.5.dp, GlassBorder, RoundedCornerShape(20.dp))
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
    ) {
        content()
    }
}

/* ═══════════════════════ 剪辑下载 UI ═════════════════════════════════ */

/** 剪辑页：全屏页面（返回键或左上角返回，避免误触关闭） */
@Composable
private fun ClipPage(vm: BiliViewModel, requestStorage: (() -> Unit) -> Unit) {
    BackHandler(enabled = vm.showClipPage) { vm.dismissClipPage() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgGradient)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = cardMaxWidth())
                .align(Alignment.TopCenter)
        ) {
            // 页头：返回 + 标题（固定）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(iOSCardBg)
                        .border(1.dp, iOSSeparator, CircleShape)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = vm::dismissClipPage
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 18.sp, color = iOSLabel, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "快速截取",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = iOSLabel,
                        letterSpacing = (-0.4).sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "选好起点与时长，一键剪出片段",
                        fontSize = 12.sp,
                        color = iOSSecondary
                    )
                }
            }

            // 内容（可滚动）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp)
            ) {
                when {
                    vm.clipProbing -> ClipProbingView()
                    vm.clipDurationMs <= 0 -> ClipUnavailableView()
                    else -> ClipEditor(vm)
                }
            }

            // 底部固定操作栏
            if (!vm.clipProbing && vm.clipDurationMs > 0) {
                ClipBottomBar(vm, requestStorage)
            }
        }

        if (vm.showFrameSaveDialog) {
            FrameSaveDialog(vm, requestStorage)
        }
    }
}

@Composable
private fun FrameSaveDialog(vm: BiliViewModel, requestStorage: (() -> Unit) -> Unit) {
    val side = if (vm.frameSaveIsStart) "首帧" else "尾帧"
    AlertDialog(
        onDismissRequest = vm::dismissFrameSaveDialog,
        title = { Text("保存${side}图片", fontWeight = FontWeight.SemiBold) },
        text = { Text("是否将该画面保存到本地相册？") },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.dismissFrameSaveDialog()
                    requestStorage { vm.confirmSaveFrame() }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissFrameSaveDialog) { Text("取消") }
        }
    )
}

@Composable
private fun ClipProbingView() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(iOSCardBg)
            .border(1.dp, iOSSeparator, RoundedCornerShape(20.dp))
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = iOSBlue,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("正在获取视频信息", color = iOSLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("马上就好，请稍候", color = iOSSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun ClipUnavailableView() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(iOSCardBg)
            .border(1.dp, iOSSeparator, RoundedCornerShape(20.dp))
            .padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("😢", fontSize = 30.sp)
        Spacer(Modifier.height(12.dp))
        Text("该视频暂不支持剪辑", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = iOSLabel)
        Spacer(Modifier.height(6.dp))
        Text(
            "无法获取视频时长，可尝试直接下载完整视频",
            fontSize = 12.sp,
            color = iOSSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ClipEditor(vm: BiliViewModel) {
    ClipFramePreviews(vm)
    Spacer(Modifier.height(14.dp))
    ClipQuickSettings(vm)
    Spacer(Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("片段时间轴", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = iOSSecondary)
        Text("拖动两端手柄调整范围", fontSize = 11.sp, color = iOSSecondary.copy(alpha = 0.75f))
    }
    Spacer(Modifier.height(8.dp))
    ClipTimelineStrip(
        durationMs = vm.clipDurationMs,
        startMs = vm.clipStartMs,
        endMs = vm.clipEndMs,
        thumbs = vm.clipThumbs,
        enabled = !vm.isClipping,
        onStartHandleDrag = { t -> vm.setClipStartKeepingDuration(t) },
        onEndHandleDrag = { t -> vm.setClipEndManual(t) }
    )
}

/** 快速截取设置区：开始时间 + 快捷时长 + 片段信息 */
@Composable
private fun ClipQuickSettings(vm: BiliViewModel) {
    var showStartInput by remember { mutableStateOf(false) }
    var showCustomInput by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(iOSCardBg)
            .border(1.dp, iOSSeparator, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(iOSBgBase)
                .clickable(interactionSource = null, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showStartInput = true
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("开始时间", fontSize = 11.sp, color = iOSSecondary)
                Spacer(Modifier.height(3.dp))
                Text(
                    formatClipSeconds(vm.clipStartMs),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = iOSLabel,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = (-0.5).sp
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(iOSBlue.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("精确输入", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = iOSBlue)
                Spacer(Modifier.width(2.dp))
                Text("›", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = iOSBlue)
            }
        }
        Spacer(Modifier.height(16.dp))

        Text("截取时长", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = iOSSecondary)
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BiliViewModel.CLIP_PRESET_SECONDS.forEach { sec ->
                DurationChip(
                    label = "${sec}秒",
                    selected = !vm.clipCustomDuration && vm.clipPresetSec == sec
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.applyClipPreset(sec)
                }
            }
            DurationChip(label = "自定义", selected = vm.clipCustomDuration) {
                showCustomInput = true
            }
        }
        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth().height(1.dp).background(iOSSeparator))
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("片段范围", fontSize = 11.sp, color = iOSSecondary)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${formatClipSeconds(vm.clipStartMs)} — ${formatClipSeconds(vm.clipEndMs)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = iOSLabel,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(iOSBlue.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "共 ${formatSecondsShort(vm.clipSelectedSeconds)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = iOSBlue
                )
            }
        }
    }

    if (showStartInput) {
        ClipInputDialog(
            title = "输入开始时间",
            label = "秒 / 分:秒 / 时:分:秒",
            hint = "例如 32.5 或 1:05",
            initialText = secondsInputText(vm.clipStartMs),
            keyboardType = KeyboardType.Ascii,
            parse = { raw -> parseClipSeconds(raw) },
            onApply = { sec ->
                val ms = (sec * 1000).roundToLong()
                vm.setClipStartKeepingDuration(ms)
            },
            onDismiss = { showStartInput = false }
        )
    }
    if (showCustomInput) {
        ClipInputDialog(
            title = "自定义截取时长",
            label = "截取秒数",
            hint = "例如 12.5",
            initialText = String.format(Locale.US, "%.1f", vm.clipSelectedSeconds),
            keyboardType = KeyboardType.Decimal,
            parse = { raw -> parseClipSeconds(raw)?.takeIf { it > 0 } },
            onApply = { sec -> vm.applyClipCustomDuration(sec) },
            onDismiss = { showCustomInput = false }
        )
    }
}

/** 快捷时长药丸 */
@Composable
private fun DurationChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) iOSBlue else iOSBgBase)
            .then(
                if (selected) {
                    Modifier.shadow(
                        6.dp,
                        RoundedCornerShape(50),
                        ambientColor = iOSBlue.copy(alpha = 0.45f),
                        spotColor = iOSBlue.copy(alpha = 0.45f)
                    )
                } else {
                    Modifier.border(1.dp, iOSSeparator, RoundedCornerShape(50))
                }
            )
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else iOSLabel
        )
    }
}

/** 时间/时长输入弹窗 */
@Composable
private fun ClipInputDialog(
    title: String,
    label: String,
    hint: String,
    initialText: String,
    keyboardType: KeyboardType,
    parse: (String) -> Double?,
    onApply: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }

    fun confirm() {
        val value = parse(text)
        if (value == null) {
            error = "请输入有效的时间"
        } else {
            onApply(value)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text(label) },
                    placeholder = { Text(hint) },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = Color(0xFFFF3B30))
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 底部固定操作栏：截取摘要 + 主按钮 / 处理中进度 / 完成状态 */
@Composable
private fun ClipBottomBar(vm: BiliViewModel, requestStorage: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val resultName = vm.clipResultFileName

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8F8FC))
            .drawBehind {
                drawLine(
                    color = iOSSeparator,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 12.dp)
    ) {
        when {
            resultName != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(iOSGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "剪辑完成，已保存到相册",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = iOSLabel
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            resultName,
                            fontSize = 11.sp,
                            color = iOSSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(iOSBgBase)
                            .border(1.dp, iOSSeparator, RoundedCornerShape(14.dp))
                            .clickable(interactionSource = null, indication = null) {
                                openClipResult(context, resultName)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "打开相册",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = iOSLabel
                        )
                    }
                    PressButton(
                        modifier = Modifier.weight(1f),
                        onClick = vm::dismissClipResult,
                        background = iOSBlue,
                        shadowColor = iOSBlue.copy(alpha = 0.3f),
                        contentColor = Color.White,
                        height = 48.dp
                    ) {
                        Text(
                            "完成",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            vm.isClipping -> ClipProgressArea(
                phaseText = vm.clipPhaseText,
                percent = vm.clipPercent,
                onCancel = vm::cancelClip
            )

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("已选择", fontSize = 12.sp, color = iOSSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${formatClipTime(vm.clipStartMs)}–${formatClipTime(vm.clipEndMs)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = iOSLabel,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(iOSGreen.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            formatSecondsShort(vm.clipSelectedSeconds),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = iOSGreen
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val estimatedBytes = if (vm.fileSize > 0 && vm.clipDurationMs > 0) {
                    vm.fileSize * (vm.clipEndMs - vm.clipStartMs).coerceAtLeast(0L) / vm.clipDurationMs
                } else {
                    0L
                }
                PressButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = cardMaxWidth()),
                    onClick = {
                        requestStorage { vm.startClipDownload() }
                    },
                    background = iOSGreen,
                    shadowColor = iOSGreen.copy(alpha = 0.3f),
                    contentColor = Color.White
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✂", fontSize = 14.sp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (estimatedBytes > 0) {
                                "剪辑并下载（约 ${formatFileSize(estimatedBytes)}）"
                            } else {
                                "剪辑并下载"
                            },
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.25.sp
                        )
                    }
                }
            }
        }
    }
}

/** 打开刚剪辑好的视频：优先定位到相册中的该文件 */
private fun openClipResult(context: Context, fileName: String) {
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val uri = runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME}=?",
            arrayOf(fileName),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }
    }.getOrNull()
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri ?: collection, "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

/** 时间轴时间显示：mm:ss.d */
private fun formatClipSeconds(ms: Long): String {
    val totalTenths = (ms.coerceAtLeast(0L) + 50) / 100
    val totalSec = totalTenths / 10
    val tenth = totalTenths % 10
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, tenth)
    } else {
        String.format(Locale.US, "%02d:%02d.%d", m, s, tenth)
    }
}

/** 时长展示：整秒省略小数 */
private fun formatSecondsShort(seconds: Double): String {
    val rounded = (seconds * 10).roundToLong() / 10.0
    return if (kotlin.math.abs(rounded - rounded.toLong()) < 0.05) {
        "${rounded.toLong()} 秒"
    } else {
        String.format(Locale.US, "%.1f 秒", rounded)
    }
}

/** 时间轴最大缩放倍率 */
private fun maxZoomFor(durationMs: Long): Float = (durationMs / 15_000f).coerceIn(20f, 120f)

/**
 * 双滑块时间轴：缩略图条 + 选区高亮 + 可拖拽把手。
 * 拖动开始手柄保持当前时长；拖动结束手柄重算时长；双指缩放/平移；点击就近移动手柄。
 */
@Composable
private fun ClipTimelineStrip(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    thumbs: List<ImageBitmap?>,
    enabled: Boolean,
    onStartHandleDrag: (Long) -> Unit,
    onEndHandleDrag: (Long) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentStartMs by rememberUpdatedState(startMs)
    val currentEndMs by rememberUpdatedState(endMs)
    val currentOnStartHandleDrag by rememberUpdatedState(onStartHandleDrag)
    val currentOnEndHandleDrag by rememberUpdatedState(onEndHandleDrag)
    var activeHandle by remember { mutableStateOf<Int?>(null) }
    var zoom by remember(durationMs) { mutableFloatStateOf(1f) }
    var scrollX by remember(durationMs) { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
    ) {
        val stripWpx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val handleW = 14.dp
        val handleWpx = with(density) { handleW.toPx() }
        val handleTouchW = 40.dp
        val handleTouchWpx = with(density) { handleTouchW.toPx() }
        val edgeMarginPx = with(density) { 20.dp.toPx() }
        val maxZoom = maxZoomFor(durationMs)

        fun autoZoomForNarrowSelection(
            selStartMs: Long,
            selEndMs: Long,
            anchorMs: Long,
            anchorScreenX: Float
        ) {
            if (durationMs <= 0L) return
            val selFrac = (selEndMs - selStartMs).toFloat() / durationMs
            if (selFrac <= 0f || selFrac * zoom >= 0.4f) return
            val target = (0.55f / selFrac).coerceIn(zoom, maxZoom)
            if (target <= zoom * 1.001f) return
            zoom = target
            scrollX = (contentOfMs(anchorMs, stripWpx, target, durationMs) - anchorScreenX)
                .coerceIn(0f, (stripWpx * target - stripWpx).coerceAtLeast(0f))
        }

        val startContentPx = contentOfMs(startMs, stripWpx, zoom, durationMs)
        val endContentPx = contentOfMs(endMs, stripWpx, zoom, durationMs)
        val selLeftScreen = startContentPx - scrollX
        val selWidthScreen = (endContentPx - startContentPx).coerceAtLeast(handleWpx)
        val selRightScreen = selLeftScreen + selWidthScreen

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(MediaSurface)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .then(
                    if (enabled) {
                        Modifier
                            .pointerInput(durationMs) {
                                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                    if (gestureZoom != 1f) {
                                        val newZoom = (zoom * gestureZoom).coerceIn(1f, maxZoom)
                                        val newContentW = stripWpx * newZoom
                                        val tAtCentroid =
                                            (centroid.x + scrollX) / (stripWpx * zoom) * durationMs
                                        scrollX =
                                            (tAtCentroid / durationMs * newContentW - centroid.x)
                                                .coerceIn(
                                                    0f,
                                                    (newContentW - stripWpx).coerceAtLeast(0f)
                                                )
                                        zoom = newZoom
                                    } else if (zoom > 1.01f && pan.x != 0f) {
                                        scrollX = (scrollX - pan.x).coerceIn(
                                            0f,
                                            (stripWpx * zoom - stripWpx).coerceAtLeast(0f)
                                        )
                                    }
                                }
                            }
                            .pointerInput(durationMs) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        zoom = 1f
                                        scrollX = 0f
                                    },
                                    onTap = { offset ->
                                        val t = msOfContent(
                                            offset.x + scrollX,
                                            stripWpx,
                                            zoom,
                                            durationMs
                                        )
                                        val distStart = kotlin.math.abs(t - currentStartMs)
                                        val distEnd = kotlin.math.abs(currentEndMs - t)
                                        if (distStart <= distEnd) {
                                            currentOnStartHandleDrag(t)
                                            autoZoomForNarrowSelection(t, currentEndMs, t, offset.x)
                                        } else {
                                            currentOnEndHandleDrag(t)
                                            autoZoomForNarrowSelection(currentStartMs, t, t, offset.x)
                                        }
                                    }
                                )
                            }
                    } else {
                        Modifier
                    }
                )
        ) {
            // 缩略图
            Row(modifier = Modifier.fillMaxSize()) {
                if (thumbs.isEmpty()) {
                    PulsingPlaceholder(modifier = Modifier.fillMaxSize())
                } else {
                    thumbs.forEach { thumb ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(Modifier.fillMaxSize().background(Color(0xFF2C2C2E)))
                            }
                        }
                    }
                }
            }

            // 选区内轻微提亮
            Box(
                Modifier
                    .fillMaxHeight()
                    .absoluteOffset { IntOffset(selLeftScreen.roundToInt(), 0) }
                    .width(with(density) { selWidthScreen.coerceAtLeast(0f).toDp() })
                    .background(iOSBlue.copy(alpha = 0.18f))
            )

            // 选区外压暗
            if (selLeftScreen > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(with(density) { selLeftScreen.coerceAtMost(stripWpx).toDp() })
                        .background(Color.Black.copy(alpha = 0.5f))
                )
            }
            if (selRightScreen < stripWpx) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                        .width(
                            with(density) {
                                (stripWpx - selRightScreen).coerceAtLeast(0f).toDp()
                            }
                        )
                        .background(Color.Black.copy(alpha = 0.5f))
                )
            }

            // 选区边框
            Box(
                Modifier
                    .fillMaxHeight()
                    .absoluteOffset { IntOffset(selLeftScreen.roundToInt(), 0) }
                    .width(with(density) { selWidthScreen.toDp() })
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp))
            )

            // 左把手
            ClipHandle(
                modifier = Modifier
                    .fillMaxHeight()
                    .absoluteOffset {
                        IntOffset((selLeftScreen - handleTouchWpx / 2).roundToInt(), 0)
                    }
                    .width(handleTouchW),
                visualWidth = handleW,
                enabled = enabled,
                active = activeHandle == 0,
                onDragStart = {
                    activeHandle = 0
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = { activeHandle = null },
                onDrag = { dragAmount ->
                    val newT = msOfContent(
                        contentOfMs(currentStartMs, stripWpx, zoom, durationMs) + dragAmount,
                        stripWpx,
                        zoom,
                        durationMs
                    )
                    currentOnStartHandleDrag(newT)
                    autoZoomForNarrowSelection(
                        selStartMs = newT,
                        selEndMs = currentEndMs,
                        anchorMs = newT,
                        anchorScreenX = contentOfMs(newT, stripWpx, zoom, durationMs) - scrollX
                    )
                    panViewportTo(
                        contentX = contentOfMs(newT, stripWpx, zoom, durationMs),
                        handleScreenX = contentOfMs(newT, stripWpx, zoom, durationMs) - scrollX,
                        stripWpx = stripWpx,
                        zoom = zoom,
                        edgeMarginPx = edgeMarginPx
                    ) { scrollX = it }
                }
            )

            // 右把手
            ClipHandle(
                modifier = Modifier
                    .fillMaxHeight()
                    .absoluteOffset {
                        IntOffset((selRightScreen - handleTouchWpx / 2).roundToInt(), 0)
                    }
                    .width(handleTouchW),
                visualWidth = handleW,
                enabled = enabled,
                active = activeHandle == 1,
                onDragStart = {
                    activeHandle = 1
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = { activeHandle = null },
                onDrag = { dragAmount ->
                    val newT = msOfContent(
                        contentOfMs(currentEndMs, stripWpx, zoom, durationMs) + dragAmount,
                        stripWpx,
                        zoom,
                        durationMs
                    )
                    currentOnEndHandleDrag(newT)
                    autoZoomForNarrowSelection(
                        selStartMs = currentStartMs,
                        selEndMs = newT,
                        anchorMs = newT,
                        anchorScreenX = contentOfMs(newT, stripWpx, zoom, durationMs) - scrollX
                    )
                    panViewportTo(
                        contentX = contentOfMs(newT, stripWpx, zoom, durationMs),
                        handleScreenX = contentOfMs(newT, stripWpx, zoom, durationMs) - scrollX,
                        stripWpx = stripWpx,
                        zoom = zoom,
                        edgeMarginPx = edgeMarginPx
                    ) { scrollX = it }
                }
            )

            if (zoom > 1.01f) {
                Text(
                    "可视 " + formatClipTime((durationMs / zoom).roundToLong()),
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun contentOfMs(ms: Long, stripWpx: Float, zoom: Float, durationMs: Long): Float =
    ms * stripWpx * zoom / durationMs.coerceAtLeast(1L)

private fun msOfContent(px: Float, stripWpx: Float, zoom: Float, durationMs: Long): Long =
    (px / (stripWpx * zoom) * durationMs).roundToLong().coerceIn(0L, durationMs)

private inline fun panViewportTo(
    contentX: Float,
    handleScreenX: Float,
    stripWpx: Float,
    zoom: Float,
    edgeMarginPx: Float,
    setScroll: (Float) -> Unit
) {
    val maxScroll = (stripWpx * zoom - stripWpx).coerceAtLeast(0f)
    if (handleScreenX > stripWpx - edgeMarginPx) {
        setScroll((contentX - (stripWpx - edgeMarginPx)).coerceIn(0f, maxScroll))
    } else if (handleScreenX < edgeMarginPx) {
        setScroll((contentX - edgeMarginPx).coerceIn(0f, maxScroll))
    }
}

private fun parseClipTimeInput(raw: String): Long? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    return if (s.contains(':')) {
        val nums = s.split(':').map { it.trim().toDoubleOrNull() ?: return null }
        val seconds = when (nums.size) {
            2 -> nums[0] * 60 + nums[1]
            3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            else -> return null
        }
        if (seconds < 0) null else (seconds * 1000).roundToLong()
    } else {
        s.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).roundToLong() }
    }
}

private fun parseClipSeconds(raw: String): Double? =
    parseClipTimeInput(raw)?.let { it / 1000.0 }

private fun secondsInputText(ms: Long): String =
    String.format(Locale.US, "%.1f", ms / 1000.0)

@Composable
private fun ClipFramePreviews(vm: BiliViewModel) {
    LaunchedEffect(vm.clipStartMs) {
        vm.requestClipFrame(isStart = true, timeMs = vm.clipStartMs)
    }
    LaunchedEffect(vm.clipEndMs) {
        vm.requestClipFrame(isStart = false, timeMs = vm.clipEndMs)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ClipFrameCard(
            modifier = Modifier.weight(1f),
            label = "开始",
            labelColor = iOSBlue,
            timeMs = vm.clipStartMs,
            frame = vm.clipStartFrame,
            onClick = { vm.onClipFrameClicked(isStart = true) }
        )
        ClipFrameCard(
            modifier = Modifier.weight(1f),
            label = "结束",
            labelColor = iOSGreen,
            timeMs = vm.clipEndMs,
            frame = vm.clipEndFrame,
            onClick = { vm.onClipFrameClicked(isStart = false) }
        )
    }
}

@Composable
private fun ClipFrameCard(
    modifier: Modifier = Modifier,
    label: String,
    labelColor: Color,
    timeMs: Long,
    frame: ImageBitmap?,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MediaSurface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PulsingPlaceholder(modifier = Modifier.fillMaxSize())
            CircularProgressIndicator(
                color = iOSBlue,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(40.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                    )
                )
        )
        Text(
            formatClipTime(timeMs),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 8.dp)
        )
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(labelColor.copy(alpha = 0.92f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun PulsingPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thumbPulse")
    val alpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "thumbPulseAlpha"
    )
    Box(
        modifier
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF232329), Color(0xFF33333C), Color(0xFF232329))
                )
            )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
private fun ClipHandle(
    modifier: Modifier,
    visualWidth: Dp,
    enabled: Boolean,
    active: Boolean,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Float) -> Unit
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val scale by animateFloatAsState(
        if (active) 1.18f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "handleScale"
    )
    Box(
        modifier = modifier.then(
            if (enabled) {
                Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { currentOnDragStart() },
                        onDragEnd = { currentOnDragEnd() },
                        onDragCancel = { currentOnDragEnd() }
                    ) { change, dragAmount ->
                        change.consume()
                        currentOnDrag(dragAmount)
                    }
                }
            } else {
                Modifier
            }
        ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(visualWidth)
                .shadow(
                    elevation = if (active) 8.dp else 4.dp,
                    shape = RoundedCornerShape(5.dp),
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                    spotColor = Color.Black.copy(alpha = 0.4f)
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 2.dp, height = 20.dp)
                    .background(
                        Color(0xFF1C1C1E).copy(alpha = 0.4f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
private fun ClipProgressArea(
    phaseText: String,
    percent: Int,
    onCancel: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                phaseText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = iOSLabel
            )
            Text(
                "$percent%",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = iOSBlue,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFFE5E5EA))
        ) {
            val animated by animateFloatAsState(
                percent / 100f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                label = "clipProg"
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(listOf(iOSBlue, iOSBlueLight)))
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFFF3B30).copy(alpha = 0.1f))
                .border(
                    1.dp,
                    Color(0xFFFF3B30).copy(alpha = 0.35f),
                    RoundedCornerShape(50)
                )
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onCancel
                )
                .padding(horizontal = 24.dp, vertical = 9.dp)
        ) {
            Text(
                "取消剪辑",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF3B30)
            )
        }
    }
}

/** mm:ss / h:mm:ss 展示 */
private fun formatClipTime(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val value = bytes.toDouble()
    return when {
        value >= 1024.0 * 1024 * 1024 ->
            String.format(Locale.US, "%.2f GB", value / (1024 * 1024 * 1024))
        value >= 1024.0 * 1024 ->
            String.format(Locale.US, "%.1f MB", value / (1024 * 1024))
        value >= 1024.0 ->
            String.format(Locale.US, "%.0f KB", value / 1024)
        else -> "$bytes B"
    }
}
