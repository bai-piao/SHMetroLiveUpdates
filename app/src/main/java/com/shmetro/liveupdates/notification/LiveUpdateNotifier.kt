package com.shmetro.liveupdates.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.shmetro.liveupdates.MainActivity
import com.shmetro.liveupdates.R
import com.shmetro.liveupdates.data.MetroLine
import com.shmetro.liveupdates.data.Station
import com.shmetro.liveupdates.location.TrackingState
import com.shmetro.liveupdates.service.MetroTrackingService

/**
 * Builds and posts the ongoing "current station / next station" notification.
 *
 * On Android 16 (API 36, "Baklava") and above this requests promotion to a system Live
 * Update via [NotificationCompat.Builder.setRequestPromotedOngoing] and renders the journey
 * with [NotificationCompat.ProgressStyle]. The promotion request only actually surfaces as a
 * status-bar/lock-screen Live Update if the user has enabled "Live updates" for this app in
 * system settings (see [MainActivity]) — otherwise it silently falls back to a normal ongoing
 * notification, which is also what's shown pre-36.
 */
object LiveUpdateNotifier {
    const val CHANNEL_ID = "metro_live_updates"
    const val NOTIFICATION_ID = 42
    const val ACTION_STOP = "com.shmetro.liveupdates.action.STOP"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /** The line/current/next-station triple, only present once a line has actually been matched. */
    private data class Located(val line: MetroLine, val current: Station, val next: Station)

    fun build(context: Context, state: TrackingState): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, MetroTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val line = state.currentLine
        val current = state.currentStation
        val next = state.nextStation
        val located = if (line != null && current != null && next != null) {
            Located(line, current, next)
        } else {
            null
        }

        val title = when {
            located != null && state.arriving ->
                context.getString(R.string.notification_title_arriving, located.line.nameZh, located.next.nameZh)
            located != null -> {
                val terminus = if (state.directionForward) located.line.stations.last() else located.line.stations.first()
                context.getString(R.string.notification_title_bound_for, located.line.nameZh, terminus.nameZh)
            }
            else -> context.getString(R.string.notification_title_locating, context.getString(R.string.app_name))
        }

        val bodyText = when {
            located != null && state.arriving ->
                context.getString(R.string.notification_body_arriving, located.next.nameZh)
            located != null -> {
                val distance = state.distanceToNextMeters
                if (distance != null) {
                    context.getString(
                        R.string.notification_body_with_distance,
                        located.current.nameZh,
                        located.next.nameZh,
                        distance,
                    )
                } else {
                    context.getString(R.string.notification_body, located.current.nameZh, located.next.nameZh)
                }
            }
            else -> context.getString(R.string.notification_body_locating)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.action_stop_tracking), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (located != null) {
            builder.setColor(located.line.colorArgb)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setRequestPromotedOngoing(true)
            if (located != null) {
                val shortText = if (state.arriving) {
                    "${located.next.nameZh}站到了"
                } else {
                    "${located.current.nameZh} → ${located.next.nameZh}"
                }
                builder.setShortCriticalText(shortText)

                val trackerIcon = IconCompat.createWithResource(context, R.drawable.ic_train)
                val progressStyle = NotificationCompat.ProgressStyle()
                    .setProgressSegments(
                        listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(located.line.colorArgb)),
                    )
                    .setProgressPoints(
                        listOf(
                            NotificationCompat.ProgressStyle.Point(0).setColor(located.line.colorArgb),
                            NotificationCompat.ProgressStyle.Point(100).setColor(located.line.colorArgb),
                        ),
                    )
                    .setProgressTrackerIcon(trackerIcon)
                    .setProgress(state.segmentProgressPercent)
                builder.setStyle(progressStyle)
            } else {
                builder.setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
            }
        }

        return builder.build()
    }
}
