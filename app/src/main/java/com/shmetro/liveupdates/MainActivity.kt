package com.shmetro.liveupdates

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shmetro.liveupdates.data.MetroLine
import com.shmetro.liveupdates.data.MetroLines
import com.shmetro.liveupdates.data.Station
import com.shmetro.liveupdates.location.StationLocator
import com.shmetro.liveupdates.location.TrackingState
import com.shmetro.liveupdates.location.TrackingStateHolder
import com.shmetro.liveupdates.notification.LiveUpdateNotifier
import com.shmetro.liveupdates.service.MetroTrackingService
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Location
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            MiuixTheme(colors = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                MetroApp()
            }
        }
    }
}

private enum class AppTab(val label: String) {
    Live("实时"),
    Simulate("模拟乘车"),
    Settings("设置"),
}

@Composable
private fun MetroApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(AppTab.Live) }
    val trackingState by TrackingStateHolder.state.collectAsStateWithLifecycle()
    val simulationState = remember { SimulationUiState() }

    var hasLocationPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    var hasNotificationPermission by remember { mutableStateOf(hasNotificationPermission(context)) }
    var promotedNotificationsEnabled by remember { mutableStateOf(canPostPromotedNotifications(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        hasNotificationPermission = hasNotificationPermission(context)
        if (hasLocationPermission) {
            startTracking(context)
        }
    }
    val requestPermissions = {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(permissions)
    }

    // Blur/glass only works where the device supports Compose's AGSL runtime shader; older
    // GPUs fall back to a plain opaque bar (backdrop stays null, blurActive below is false).
    val backdrop: LayerBackdrop? = if (isRuntimeShaderSupported()) {
        val surfaceColor = MiuixTheme.colorScheme.surface
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }
    val blurActive = backdrop != null

    Scaffold(
        topBar = { TopAppBar(title = selectedTab.label) },
        bottomBar = {
            Box(
                modifier = if (blurActive) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.65f)),
                            ),
                        ),
                    )
                } else {
                    Modifier
                },
            ) {
                NavigationBar(color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = selectedTab == AppTab.Live,
                        onClick = { selectedTab = AppTab.Live },
                        icon = MiuixIcons.Location,
                        label = AppTab.Live.label,
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.Simulate,
                        onClick = { selectedTab = AppTab.Simulate },
                        icon = MiuixIcons.Play,
                        label = AppTab.Simulate.label,
                    )
                    NavigationBarItem(
                        selected = selectedTab == AppTab.Settings,
                        onClick = { selectedTab = AppTab.Settings },
                        icon = MiuixIcons.Settings,
                        label = AppTab.Settings.label,
                    )
                }
            }
        },
    ) { padding ->
        // Deliberately not applying the bottom inset here: content needs to extend full-height,
        // underneath the (semi-transparent) bar, for there to be anything for it to blur. Each
        // tab instead reserves that space as trailing padding inside its own scroll content.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .then(if (blurActive) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            val bottomInset = padding.calculateBottomPadding()
            when (selectedTab) {
                AppTab.Live -> LiveTrackingContent(
                    state = trackingState,
                    bottomInset = bottomInset,
                    onStartTracking = {
                        if (hasLocationPermission) startTracking(context) else requestPermissions()
                    },
                    onStopTracking = { stopTracking(context) },
                )
                AppTab.Simulate -> SimulationContent(simulationState, bottomInset = bottomInset)
                AppTab.Settings -> SettingsContent(
                    hasNotificationPermission = hasNotificationPermission,
                    promotedNotificationsEnabled = promotedNotificationsEnabled,
                    bottomInset = bottomInset,
                    onRequestNotificationPermission = requestPermissions,
                    onOpenPromotionSettings = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    },
                )
            }
        }
    }

    // Refresh the promotion-settings hint whenever the screen resumes (e.g. after coming
    // back from system settings).
    LifecycleResumeEffect(Unit) {
        hasLocationPermission = hasLocationPermission(context)
        hasNotificationPermission = hasNotificationPermission(context)
        promotedNotificationsEnabled = canPostPromotedNotifications(context)
        onPauseOrDispose { }
    }
}

@Composable
private fun LiveTrackingContent(
    state: TrackingState,
    bottomInset: Dp,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "${state.currentLine?.nameZh ?: "全部线路"} · " +
                (if (state.isTracking) "追踪中" else "未追踪"),
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        CurrentNextCard(state)

        if (BuildConfig.DEBUG) {
            DebugLocationOverrideCard()
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !state.isTracking,
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = onStartTracking,
            ) {
                Text("开始追踪")
            }
            Button(
                enabled = state.isTracking,
                onClick = onStopTracking,
            ) {
                Text("停止追踪")
            }
        }
    }
}

@Composable
private fun CurrentNextCard(state: TrackingState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        val current = state.currentStation
        val next = state.nextStation
        if (current == null || next == null) {
            Text(
                text = if (state.isTracking) "正在定位…" else "尚未开始追踪",
                color = MiuixTheme.colorScheme.onSurface,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            ) {
                Text(
                    text = "当前站 ${current.nameZh}  →  下一站 ${next.nameZh}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = state.segmentProgressPercent / 100f,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (state.arriving) {
                Text(
                    text = "${next.nameZh}站到了",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            } else {
                state.distanceToNextMeters?.let { distance ->
                    Text(
                        text = "距下一站约 ${distance} 米 · 行驶方向：${if (state.directionForward) "顺行" else "逆行"}",
                        style = MiuixTheme.textStyles.paragraph,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (state.offLine) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "当前位置距该线路较远，以上仅为最近站点的粗略估计",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * Debug-only affordance for testing station matching without real GPS or a location-mocking
 * app: feeds a hand-entered lat/lng straight into the same [StationLocator]/[TrackingStateHolder]
 * path the tracking service uses, so a fake location app or Xposed-style hook — which only
 * intercepts calls inside this app's own process and never reaches the FusedLocationProviderClient
 * running in Google Play Services — isn't needed at all.
 */
@Composable
private fun DebugLocationOverrideCard() {
    val latState = rememberTextFieldState()
    val lngState = rememberTextFieldState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "调试：手动设置位置",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                state = latState,
                label = "纬度",
                modifier = Modifier.weight(1f),
            )
            TextField(
                state = lngState,
                label = "经度",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val lat = latState.text.toString().toDoubleOrNull()
                val lng = lngState.text.toString().toDoubleOrNull()
                if (lat != null && lng != null) {
                    val match = StationLocator.locateBest(MetroLines.all, lat, lng)
                    if (match != null) {
                        TrackingStateHolder.update(match.line, match.result)
                    }
                }
            },
        ) {
            Text("应用")
        }
    }
}

/** Hoisted at [MetroApp] level so progress survives switching away from the 模拟 tab and back. */
private class SimulationUiState {
    var selectedLineIndex by mutableIntStateOf(0)
    var stationIndex by mutableIntStateOf(0)
    var autoDirectionForward by mutableStateOf(true)
    var autoMode by mutableStateOf(false)
    var running by mutableStateOf(false)
}

private const val SIMULATION_STEP_INTERVAL_MILLIS = 3_000L

@Composable
private fun SimulationContent(state: SimulationUiState, bottomInset: Dp) {
    val context = LocalContext.current
    val line = MetroLines.all[state.selectedLineIndex]
    val stations = line.stations

    fun resetToStart() {
        state.stationIndex = if (state.autoDirectionForward) 0 else stations.lastIndex
        state.running = false
    }

    // Re-launches whenever `running` flips; flipping it back to false cancels this coroutine.
    LaunchedEffect(state.running) {
        if (!state.running) return@LaunchedEffect
        while (true) {
            delay(SIMULATION_STEP_INTERVAL_MILLIS)
            val next = state.stationIndex + if (state.autoDirectionForward) 1 else -1
            if (next !in stations.indices) {
                state.running = false
                break
            }
            state.stationIndex = next
        }
    }

    // Mirrors the real Live Update notification, built from the same simulated station data
    // shown on screen, so 模拟 can preview it without needing a real ride. Posted under its own
    // ID (LiveUpdateNotifier.SIMULATION_NOTIFICATION_ID) so it never collides with a real one,
    // and cleared once the user leaves this tab since it's only a preview, not a real service.
    LaunchedEffect(state.selectedLineIndex, state.stationIndex, state.autoDirectionForward) {
        postSimulationNotification(context, line, stations, state.stationIndex, state.autoDirectionForward)
    }
    DisposableEffect(Unit) {
        onDispose { NotificationManagerCompat.from(context).cancel(LiveUpdateNotifier.SIMULATION_NOTIFICATION_ID) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "在不联网、不定位的情况下模拟一趟行程，用于演示报站效果",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        OverlayDropdownPreference(
            items = MetroLines.all.map { it.nameZh },
            selectedIndex = state.selectedLineIndex,
            title = "选择线路",
            enabled = !state.running,
            onSelectedIndexChange = { index ->
                state.selectedLineIndex = index
                state.stationIndex = 0
                state.running = false
            },
        )

        SimulationStatusCard(line = line, stationIndex = state.stationIndex, forward = state.autoDirectionForward)

        SwitchPreference(
            checked = state.autoMode,
            onCheckedChange = {
                state.autoMode = it
                state.running = false
            },
            title = "自动报站",
            summary = "每 ${SIMULATION_STEP_INTERVAL_MILLIS / 1000} 秒自动前进到下一站；关闭则手动选择",
        )

        if (state.autoMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "→ ${stations.last().nameZh}",
                    enabled = !state.running,
                    colors = if (state.autoDirectionForward) {
                        ButtonDefaults.textButtonColorsPrimary()
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                    onClick = { state.autoDirectionForward = true; resetToStart() },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "→ ${stations.first().nameZh}",
                    enabled = !state.running,
                    colors = if (!state.autoDirectionForward) {
                        ButtonDefaults.textButtonColorsPrimary()
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                    onClick = { state.autoDirectionForward = false; resetToStart() },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val hasNext = (state.stationIndex + if (state.autoDirectionForward) 1 else -1) in stations.indices
                Button(
                    enabled = state.running || hasNext,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = { state.running = !state.running },
                ) {
                    Text(if (state.running) "暂停" else "开始模拟")
                }
                Button(onClick = { resetToStart() }) {
                    Text("重置")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = state.stationIndex > 0,
                    onClick = { state.stationIndex-- },
                ) {
                    Text("上一站")
                }
                Button(
                    enabled = state.stationIndex < stations.lastIndex,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = { state.stationIndex++ },
                ) {
                    Text("下一站")
                }
                Button(onClick = { state.stationIndex = 0 }) {
                    Text("重置")
                }
            }
        }
    }
}

@Composable
private fun SimulationStatusCard(line: MetroLine, stationIndex: Int, forward: Boolean) {
    val stations = line.stations
    val current = stations[stationIndex]
    val nextIndex = stationIndex + if (forward) 1 else -1
    val next = stations.getOrNull(nextIndex)

    Card(modifier = Modifier.fillMaxWidth()) {
        if (next == null) {
            Text(
                text = "已到达终点：${current.nameZh}",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            ) {
                Text(
                    text = "当前站 ${current.nameZh}  →  下一站 ${next.nameZh}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = stationIndex.toFloat() / (stations.size - 1),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${line.nameZh} · 第 ${stationIndex + 1} / ${stations.size} 站",
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun SettingsContent(
    hasNotificationPermission: Boolean,
    promotedNotificationsEnabled: Boolean,
    bottomInset: Dp,
    onRequestNotificationPermission: () -> Unit,
    onOpenPromotionSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionHintCard(
                text = "需要通知权限才能显示实时信息卡片",
                actionLabel = "授权",
                onClick = onRequestNotificationPermission,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !promotedNotificationsEnabled) {
            PermissionHintCard(
                text = "需要在系统设置中为本应用开启「实时更新」才能显示灵动的 Live Update 卡片，否则会退化为普通通知",
                actionLabel = "去设置",
                onClick = onOpenPromotionSettings,
            )
        }

        SmallTitle(text = "关于")
        BasicComponent(
            title = "支持的线路",
            summary = "1-13、16、17 号线及浦江线，通过手机定位自动识别最近的线路与站点；" +
                "14、15、18 等更新的线路暂未收录。站点坐标为近似值，越靠郊区的站点误差可能越大。",
        )
        BasicComponent(
            title = "版本",
            summary = BuildConfig.VERSION_NAME,
        )
    }
}

@Composable
private fun PermissionHintCard(text: String, actionLabel: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.paragraph,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = actionLabel,
                onClick = onClick,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun postSimulationNotification(
    context: Context,
    line: MetroLine,
    stations: List<Station>,
    stationIndex: Int,
    forward: Boolean,
) {
    if (!hasNotificationPermission(context)) return
    LiveUpdateNotifier.ensureChannel(context)
    val current = stations[stationIndex]
    val nextIndex = stationIndex + if (forward) 1 else -1
    val next = stations.getOrNull(nextIndex)
    val simulatedState = TrackingState(
        isTracking = true,
        currentLine = line,
        currentStation = current,
        nextStation = next ?: current,
        directionForward = forward,
        segmentProgressPercent = 0,
        distanceToNextMeters = null,
        offLine = false,
        arriving = next == null,
    )
    val notification = LiveUpdateNotifier.build(context, simulatedState, showStopAction = false)
    NotificationManagerCompat.from(context).notify(LiveUpdateNotifier.SIMULATION_NOTIFICATION_ID, notification)
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

private fun canPostPromotedNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return true
    val manager = context.getSystemService<NotificationManager>() ?: return false
    return manager.canPostPromotedNotifications()
}

private fun startTracking(context: Context) {
    val intent = Intent(context, MetroTrackingService::class.java)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopTracking(context: Context) {
    val intent = Intent(context, MetroTrackingService::class.java).setAction(LiveUpdateNotifier.ACTION_STOP)
    context.startService(intent)
}
