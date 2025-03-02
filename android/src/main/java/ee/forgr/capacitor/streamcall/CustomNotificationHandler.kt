package ee.forgr.capacitor.streamcall

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.getstream.video.android.core.notifications.DefaultNotificationHandler
import io.getstream.video.android.model.StreamCallId

class CustomNotificationHandler(
    val application: Application,
    private val endCall: (callId: StreamCallId) -> Unit = {},
    private val incomingCall: () -> Unit = {}
) : DefaultNotificationHandler(application, hideRingingNotificationInForeground = false) {
    companion object {
        private const val PREFS_NAME = "StreamCallPrefs"
        private const val KEY_NOTIFICATION_TIME = "notification_creation_time"
    }
    var allowSound = true;

    override fun getIncomingCallNotification(
        fullScreenPendingIntent: PendingIntent,
        acceptCallPendingIntent: PendingIntent,
        rejectCallPendingIntent: PendingIntent,
        callerName: String?,
        shouldHaveContentIntent: Boolean,
    ): Notification {
        val showAsHighPriority = true
        val channelId = "incoming_calls_custom"

        customCreateIncomingCallChannel(channelId, showAsHighPriority)

        return buildNotification(
            fullScreenPendingIntent,
            acceptCallPendingIntent,
            rejectCallPendingIntent,
            callerName,
            shouldHaveContentIntent,
            channelId,
            true // Include sound
        )
    }

    fun buildNotification(
        fullScreenPendingIntent: PendingIntent,
        acceptCallPendingIntent: PendingIntent,
        rejectCallPendingIntent: PendingIntent,
        callerName: String?,
        shouldHaveContentIntent: Boolean,
        channelId: String,
        includeSound: Boolean
    ): Notification {
        return getNotification {
            priority = NotificationCompat.PRIORITY_HIGH
            setContentTitle(callerName)
            setContentText("Incoming call")
            setChannelId(channelId)
            setOngoing(true)
            setAutoCancel(false)
            setCategory(NotificationCompat.CATEGORY_CALL)
            
            // Clear all defaults first
            setDefaults(0)
            
            if (includeSound && allowSound) {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            } else {
                setSound(null)
                setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            }
            
            setVibrate(longArrayOf(0, 1000, 500, 1000))
            setLights(0xFF0000FF.toInt(), 1000, 1000)
            setFullScreenIntent(fullScreenPendingIntent, true)
            if (shouldHaveContentIntent) {
                setContentIntent(fullScreenPendingIntent)
            } else {
                val emptyIntent = PendingIntent.getActivity(
                    application,
                    0,
                    Intent(),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                setContentIntent(emptyIntent)
            }
            addCallActions(acceptCallPendingIntent, rejectCallPendingIntent, callerName)
        }.apply {
            // flags = flags or NotificationCompat.FLAG_ONGOING_EVENT
        }
    }

    override fun onMissedCall(callId: StreamCallId, callDisplayName: String) {
        endCall(callId)
        super.onMissedCall(callId, callDisplayName)
    }

    open fun customCreateIncomingCallChannel(channelId: String, showAsHighPriority: Boolean) {
        maybeCreateChannel(
            channelId = channelId,
            context = application,
            configure = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    name = application.getString(
                        R.string.stream_video_incoming_call_notification_channel_title,
                    )
                    description = application.getString(
                        if (showAsHighPriority) {
                            R.string.stream_video_incoming_call_notification_channel_description
                        } else {
                            R.string.stream_video_incoming_call_low_priority_notification_channel_description
                        },
                    )
                    importance = if (showAsHighPriority) {
                        NotificationManager.IMPORTANCE_HIGH
                    } else {
                        NotificationManager.IMPORTANCE_LOW
                    }
                    this.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    this.setShowBadge(true)
                    
                    // Set the channel to be silent since we handle sound via RingtonePlayer
                    setSound(null, null)
                    enableVibration(true)
                    enableLights(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    this.setAllowBubbles(true)
                }
            },
        )
    }

    public fun clone(): CustomNotificationHandler {
        return CustomNotificationHandler(this.application, this.endCall, this.incomingCall)
    }
}