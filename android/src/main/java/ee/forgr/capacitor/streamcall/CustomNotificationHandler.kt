package ee.forgr.capacitor.streamcall
 
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.getstream.video.android.core.RingingState
import io.getstream.video.android.core.notifications.DefaultNotificationHandler
import io.getstream.video.android.core.notifications.NotificationHandler
import io.getstream.video.android.model.StreamCallId
import io.getstream.video.android.core.R as StreamVideoR

// declare "incoming_calls_custom" as a constant
const val INCOMING_CALLS_CUSTOM = "incoming_calls_custom"
 
class CustomNotificationHandler(
    private val application: Application,
    private val endCall: (callId: StreamCallId) -> Unit = {},
    private val incomingCall: () -> Unit = {}
) : DefaultNotificationHandler(application) {

    override fun getOngoingCallNotification(
        callId: StreamCallId,
        callDisplayName: String?,
        isOutgoing: Boolean,
        participants: Int
    ): Notification? {
        Log.d("CustomNotificationHandler", "getOngoingCallNotification called: callId=$callId, isOutgoing=$isOutgoing, participants=$participants")

        val launchIntent = application.packageManager.getLaunchIntentForPackage(application.packageName)?.apply {
            putExtra("call_cid", callId.cid)
        }
        val contentIntent: PendingIntent? = if (launchIntent != null) {
            PendingIntent.getActivity(
                application,
                callId.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        return getNotification {
            setContentTitle(callDisplayName ?: "Ongoing Call")
            setContentText("Tap to return to the call.")
            setSmallIcon(StreamVideoR.drawable.stream_video_ic_call)
            setChannelId("ongoing_calls")
            setOngoing(true)
            setAutoCancel(false)
            setCategory(NotificationCompat.CATEGORY_CALL)
            setDefaults(0)
            if (contentIntent != null) {
                setContentIntent(contentIntent)
            }
        }
    }

    override fun getRingingCallNotification(
        ringingState: RingingState,
        callId: StreamCallId,
        callDisplayName: String?,
        shouldHaveContentIntent: Boolean,
    ): Notification? {
        Log.d("CustomNotificationHandler", "getRingingCallNotification called: ringingState=$ringingState, callId=$callId, callDisplayName=$callDisplayName, shouldHaveContentIntent=$shouldHaveContentIntent")
        return if (ringingState is RingingState.Incoming) {
            // Note: we create our own fullScreenPendingIntent later based on acceptCallPendingIntent

            // Get the main launch intent for the application
            val launchIntent = application.packageManager.getLaunchIntentForPackage(application.packageName)
            var targetComponent: android.content.ComponentName? = null
            if (launchIntent != null) {
                targetComponent = launchIntent.component
                Log.d("CustomNotificationHandler", "Derived launch component: ${targetComponent?.flattenToString()}")
            } else {
                Log.e("CustomNotificationHandler", "Could not get launch intent for package: ${application.packageName}. This is problematic for creating explicit intents.")
            }

            // Intent to simply bring the app to foreground and show incoming-call UI (no auto accept)
            val incomingIntentAction = "io.getstream.video.android.action.INCOMING_CALL"
            val incomingCallIntent = Intent(incomingIntentAction)
                .putExtra(NotificationHandler.INTENT_EXTRA_CALL_CID, callId)
                .setPackage(application.packageName)
            if (targetComponent != null) incomingCallIntent.component = targetComponent
            incomingCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

            // Use the app's MainActivity intent so webview loads; user sees app UI
            val requestCodeFull = callId.cid.hashCode()
            val fullScreenPendingIntent = PendingIntent.getActivity(
                application,
                requestCodeFull,
                incomingCallIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // The custom accept call intent that will be used to accept the call
            val acceptCallIntent = Intent(application, AcceptCallReceiver::class.java).apply {
                action = "io.getstream.video.android.action.ACCEPT_CALL"
                putExtra("call_cid", callId.cid)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val customAcceptCallPendingIntent = PendingIntent.getBroadcast(
                application,
                callId.hashCode(),
                acceptCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // The custom reject call intent that will be used to reject the call
            val rejectCallIntent = Intent(application, DeclineCallReceiver::class.java).apply {
                action = "io.getstream.video.android.action.REJECT_CALL"
                putExtra("call_cid", callId.cid)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val rejectCallPendingIntent = PendingIntent.getBroadcast(
                application,
                -callId.hashCode(),
                rejectCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            Log.d("CustomNotificationHandler", "Full Screen PI: $fullScreenPendingIntent")
            Log.d("CustomNotificationHandler", "Custom Accept Call PI: $customAcceptCallPendingIntent")
            Log.d("CustomNotificationHandler", "Resolver Reject Call PI: $rejectCallPendingIntent")
            
            if (fullScreenPendingIntent != null && customAcceptCallPendingIntent != null && rejectCallPendingIntent != null) {
                customGetIncomingCallNotification(
                    fullScreenPendingIntent,
                    customAcceptCallPendingIntent,
                    rejectCallPendingIntent,
                    callDisplayName,
                    includeSound = ringingState is RingingState.Incoming
                )
            } else {
                Log.e("CustomNotificationHandler", "Ringing call notification not shown, one of the intents is null.")
                null
            }
        } else if (ringingState is RingingState.Outgoing) {
            val outgoingCallPendingIntent = intentResolver.searchOutgoingCallPendingIntent(callId)
            val endCallPendingIntent = intentResolver.searchEndCallPendingIntent(callId)
 
            if (outgoingCallPendingIntent != null && endCallPendingIntent != null) {
                getOngoingCallNotification(
                    callId,
                    callDisplayName,
                )
            } else {
                Log.e("CustomNotificationHandler", "Ringing call notification not shown, one of the intents is null.")
                null
            }
        } else {
            null
        }
    }

    private fun createAcceptCallPendingIntent(
        callId: StreamCallId,
        requestCode: Int,
        acceptCallIntent: Intent
    ): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val launchIntent = application.packageManager.getLaunchIntentForPackage(application.packageName)
            if (launchIntent != null) {
                launchIntent.action = NotificationHandler.ACTION_ACCEPT_CALL
                launchIntent.putExtra(NotificationHandler.INTENT_EXTRA_CALL_CID, callId)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                PendingIntent.getActivity(
                    application,
                    requestCode,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                Log.e("CustomNotificationHandler", "Could not get launch intent for package to create Accept PI.")
                null
            }
        } else {
            PendingIntent.getBroadcast(
                application,
                requestCode,
                acceptCallIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
 
    fun customGetIncomingCallNotification(
        fullScreenPendingIntent: PendingIntent,
        acceptCallPendingIntent: PendingIntent,
        rejectCallPendingIntent: PendingIntent,
        callerName: String?,
        channelId: String = INCOMING_CALLS_CUSTOM,
        includeSound: Boolean
    ): Notification {
        // It's a simple notification with a title, text, and an icon.
        return getNotification {
            setContentTitle(callerName)
            setContentText("Incoming call")
            setSmallIcon(StreamVideoR.drawable.stream_video_ic_call)
            setChannelId(channelId)
            priority = NotificationCompat.PRIORITY_MAX
            setCategory(NotificationCompat.CATEGORY_CALL)
            // Use a full-screen intent for incoming calls.
            setFullScreenIntent(fullScreenPendingIntent, true)
            // Add actions
            addAction(NotificationCompat.Action(null, "Decline", rejectCallPendingIntent))
            addAction(NotificationCompat.Action(null, "Accept", acceptCallPendingIntent))

            if (includeSound) {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            } else {
                setDefaults(0)
            }
        }
    }
 
    private fun buildNotification(
        fullScreenPendingIntent: PendingIntent,
        acceptCallPendingIntent: PendingIntent,
        rejectCallPendingIntent: PendingIntent,
        callerName: String?,
        shouldHaveContentIntent: Boolean,
        channelId: String,
        includeSound: Boolean
    ): Notification {
        Log.d("CustomNotificationHandler", "buildNotification called: callerName=$callerName, channelId=$channelId, includeSound=$includeSound")
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
 
            if (includeSound) {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            } else {
                setSound(null)
                setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            }
 
            // setVibrate(longArrayOf(0, 1000, 500, 1000))
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
        Log.d("CustomNotificationHandler", "onMissedCall called: callId=$callId, callDisplayName=$callDisplayName")
        endCall(callId)
        super.onMissedCall(callId, callDisplayName)
    }

    private fun customCreateIncomingCallChannel() {
        Log.d("CustomNotificationHandler", "customCreateIncomingCallChannel called")
        maybeCreateChannel(
            channelId = INCOMING_CALLS_CUSTOM,
            context = application,
            configure = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    name = application.getString(
                        R.string.stream_video_incoming_call_notification_channel_title,
                    )
                    description = application.getString(R.string.stream_video_incoming_call_notification_channel_description)
                    importance = NotificationManager.IMPORTANCE_HIGH
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

    private fun createOngoingCallChannel() {
        Log.d("CustomNotificationHandler", "createOngoingCallChannel called")
        maybeCreateChannel(
            channelId = "ongoing_calls",
            context = application,
            configure = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    name = "Ongoing calls"
                    description = "Notifications for ongoing calls"
                    importance = NotificationManager.IMPORTANCE_LOW
                    this.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    this.setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                }
            },
        )
    }
 
    fun clone(): CustomNotificationHandler {
        Log.d("CustomNotificationHandler", "clone called")
        return CustomNotificationHandler(this.application, this.endCall, this.incomingCall)
    }

    override fun getNotification(
        builder: NotificationCompat.Builder.() -> Unit
    ): Notification {
        createOngoingCallChannel()
        val builder = NotificationCompat.Builder(application, "ongoing_calls")
            .apply(builder)
        return builder.build()
    }
}
 