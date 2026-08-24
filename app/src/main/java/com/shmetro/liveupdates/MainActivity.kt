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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shmetro.liveupdates.data.MetroLines
import com.shmetro.liveupdates.location.StationLocator
import com.shmetro.liveupdates.location.TrackingState
import com.shmetro.liveupdates.location.TrackingStateHolder
import com.shmetro.liveupdates.service.MetroTrackingService
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            MiuixTheme(colors = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                MetroScreen()
            }
        }
    }
}

@Composable
private fun MetroScreen() {
    val context = LocalContext.current
    val state by TrackingStateHolder.state.collectAsStateWithLifecycle()

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

    Scaffold(
        topBar = {
            TopAppBar(title = stringResource(R.string.app_name))
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
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

            if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionHintCard(
                    text = "需要通知权限才能显示实时信息卡片",
                    actionLabel = "授权",
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS),
                        )
                    },
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !promotedNotificationsEnabled) {
                PermissionHintCard(
                    text = "需要在系统设置中为本应用开启「实时更新」才能显示灵动的 Live Update 卡片，否则会退化为普通通知",
                    actionLabel = "去设置",
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !state.isTracking,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = {
                        val permissions = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        if (hasLocationPermission) {
                            startTracking(context)
                        } else {
                            permissionLauncher.launch(permissions)
                        }
                    },
                ) {
                    Text("开始追踪")
                }
                Button(
                    enabled = state.isTracking,
                    onClick = { stopTracking(context) },
                ) {
                    Text("停止追踪")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "支持 1-13、16、17 号线及浦江线，通过手机定位自动识别最近的线路与站点；" +
                    "14、15、18 等更新的线路暂未收录。站点坐标为近似值，越靠郊区的站点误差可能越大。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
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
    val intent = Intent(context, MetroTrackingService::class.java).setAction(
        com.shmetro.liveupdates.notification.LiveUpdateNotifier.ACTION_STOP,
    )
    context.startService(intent)
}
