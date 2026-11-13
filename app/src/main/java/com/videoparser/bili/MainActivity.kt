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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlin.math.roundToLong
import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType

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
                requestStorage = { action -> withStoragePermission(action) }
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
    requestStorage: (() -> Unit) -> Unit
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
            Header()

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

            Text(
                text = "支持 b23.tv 短链、BV 号与 av 号",
                color = iOSTertiary,
                fontSize = 12.sp,
                letterSpacing = 0.1.sp
            )

            Spacer(Modifier.height(16.dp))

            val data = vm.video
            if (data != null) {
                ResultSection(vm, data, requestStorage)
            } else {
                EmptyState()
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
            ClipPage(vm)
        }
    }
}

/** 主界面顶部标题与品牌角标 */
@Composable
private fun Header() {
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
                text = "原画直链 · 一键存相册",
                fontSize = 12.sp,
                color = iOSSecondary,
                letterSpacing = 0.3.sp
            )
        }

        // 品牌角标
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
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
    requestStorage: (() -> Unit) -> Unit
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
private fun EmptyState() {
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
private fun ClipPage(vm: BiliViewModel) {
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
                ClipBottomBar(vm)
            }
        }
    }
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
    // 快速截取设置：开始时间（时/分/秒）+ 截取时长（时/分/秒）+ 片段信息
    ClipQuickSettings(vm)
    Spacer(Modifier.height(14.dp))
    Text(
        "提示：为保证画面可独立解码，起点会自动对齐到最近关键帧，实际剪出的区间可能略有偏差。",
        fontSize = 11.sp,
        lineHeight = 17.sp,
        color = iOSSecondary.copy(alpha = 0.75f),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/**
 * 快速截取设置区（核心操作区）：
 * 开始时间与截取时长均为 时 / 分 / 秒 三个输入框（默认留空）+ 片段信息。
 */
@Composable
private fun ClipQuickSettings(vm: BiliViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(iOSCardBg)
            .border(1.dp, iOSSeparator, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("开始时间", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = iOSSecondary)
            Text(
                "视频时长 ${formatClipTime(vm.clipDurationMs)}",
                fontSize = 11.sp,
                color = iOSSecondary.copy(alpha = 0.75f)
            )
        }
        Spacer(Modifier.height(10.dp))

        // 时 / 分 / 秒 三个输入框，默认留空，输入即联动结束时间
        ClipTimeFields(
            valueMs = vm.clipStartMs,
            onValueChanged = vm::setClipStartKeepingDuration,
            hint = { entered ->
                if (entered != vm.clipStartMs) {
                    "超出视频范围，将从 ${formatClipTime(vm.clipStartMs)} 开始截取"
                } else null
            }
        )
        Spacer(Modifier.height(16.dp))

        Text("截取时长", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = iOSSecondary)
        Spacer(Modifier.height(9.dp))
        ClipTimeFields(
            valueMs = (vm.clipEndMs - vm.clipStartMs).coerceAtLeast(0L),
            onValueChanged = vm::setClipDurationKeepingStart,
            hint = { entered ->
                val actualMs = (vm.clipEndMs - vm.clipStartMs).coerceAtLeast(0L)
                if (entered != actualMs) {
                    "超出视频剩余时长，实际截取 ${formatClipTime(actualMs)}"
                } else null
            }
        )
        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth().height(1.dp).background(iOSSeparator))
        Spacer(Modifier.height(12.dp))

        // 片段信息：截取范围 + 实际时长
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("片段范围", fontSize = 11.sp, color = iOSSecondary)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${formatClipTime(vm.clipStartMs)} — ${formatClipTime(vm.clipEndMs)}",
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
}

/**
 * 时 / 分 / 秒 三个独立输入框，默认留空（占位 0）。
 * 输入时实时回调；失去焦点后回填实际生效的时间（超出范围会自动收敛）。
 */
@Composable
private fun ClipTimeFields(
    valueMs: Long,
    onValueChanged: (Long) -> Unit,
    hint: (enteredMs: Long) -> String? = { null }
) {
    var hoursText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var secondsText by remember { mutableStateOf("") }
    var hoursFocused by remember { mutableStateOf(false) }
    var minutesFocused by remember { mutableStateOf(false) }
    var secondsFocused by remember { mutableStateOf(false) }
    val anyFocused = hoursFocused || minutesFocused || secondsFocused

    fun enteredMs(): Long {
        val h = hoursText.toLongOrNull() ?: 0L
        val m = minutesText.toLongOrNull() ?: 0L
        val s = secondsText.toLongOrNull() ?: 0L
        return (h * 3600L + m * 60L + s) * 1000L
    }

    fun applyEntered() {
        onValueChanged(enteredMs())
    }

    // 外部时间变化（首次探测/重置/超范围收敛）且当前没有输入焦点时，回填到输入框
    LaunchedEffect(valueMs, anyFocused) {
        if (!anyFocused) {
            val totalSec = valueMs.coerceAtLeast(0L) / 1000
            if (totalSec == 0L) {
                hoursText = ""
                minutesText = ""
                secondsText = ""
            } else {
                hoursText = (totalSec / 3600).toString()
                minutesText = ((totalSec % 3600) / 60).toString()
                secondsText = (totalSec % 60).toString()
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ClipTimeField(
            value = hoursText,
            placeholder = "0",
            unit = "时",
            modifier = Modifier.weight(1f),
            onFocusChanged = { hoursFocused = it },
            onValueChange = { raw ->
                hoursText = normalizeTimePart(raw, maxValue = 99, maxDigits = 2)
                applyEntered()
            }
        )
        ClipTimeField(
            value = minutesText,
            placeholder = "0",
            unit = "分",
            modifier = Modifier.weight(1f),
            onFocusChanged = { minutesFocused = it },
            onValueChange = { raw ->
                minutesText = normalizeTimePart(raw, maxValue = 59, maxDigits = 2)
                applyEntered()
            }
        )
        ClipTimeField(
            value = secondsText,
            placeholder = "0",
            unit = "秒",
            modifier = Modifier.weight(1f),
            onFocusChanged = { secondsFocused = it },
            onValueChange = { raw ->
                secondsText = normalizeTimePart(raw, maxValue = 59, maxDigits = 2)
                applyEntered()
            }
        )
    }

    val warning = if (anyFocused) hint(enteredMs()) else null
    if (warning != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            warning,
            fontSize = 11.sp,
            color = Color(0xFFFF9500)
        )
    }
}

/** 单个时间输入框：数字键盘 + 居中大号数字 + 单位标签 */
@Composable
private fun ClipTimeField(
    value: String,
    placeholder: String,
    unit: String,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iOSBgBase)
                .border(
                    width = if (focused) 1.5.dp else 1.dp,
                    color = if (focused) iOSBlue else iOSSeparator,
                    shape = RoundedCornerShape(14.dp)
                )
                // 输入框外的留白区域也能点击聚焦，避免必须精准点中数字
                .clickable(interactionSource = null, indication = null) {
                    focusRequester.requestFocus()
                },
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChanged(it.isFocused)
                    },
                textStyle = TextStyle(
                    color = iOSLabel,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                ),
                singleLine = true,
                cursorBrush = SolidColor(iOSBlue),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                color = iOSTertiary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(unit, fontSize = 11.sp, color = iOSSecondary)
    }
}

/** 时间片段输入规范化：仅保留数字，限制位数与最大值 */
private fun normalizeTimePart(raw: String, maxValue: Int, maxDigits: Int): String {
    val digits = raw.filter { it.isDigit() }.take(maxDigits)
    if (digits.isEmpty()) return ""
    return digits.toInt().coerceAtMost(maxValue).toString()
}

/** 底部固定操作栏：截取摘要 + 主按钮 / 处理中进度 / 完成状态 */
@Composable
private fun ClipBottomBar(vm: BiliViewModel) {
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
                    onClick = vm::startClipDownload,
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

/** 时长展示：整秒省略小数 */
private fun formatSecondsShort(seconds: Double): String {
    val rounded = (seconds * 10).roundToLong() / 10.0
    return if (kotlin.math.abs(rounded - rounded.toLong()) < 0.05) {
        "${rounded.toLong()} 秒"
    } else {
        String.format(Locale.US, "%.1f 秒", rounded)
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
