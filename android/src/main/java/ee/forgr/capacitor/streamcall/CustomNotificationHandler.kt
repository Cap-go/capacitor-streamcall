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
        isOutgoingCall: Boolean,
        remoteParticipantCount: Int
    ): Notification? {
        Log.d("CustomNotificationHandler", "getOngoingCallNotification called: callId=$callId, isOutgoing=$isOutgoingCall, participants=$remoteParticipantCount")

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
 
    override fun onMissedCall(callId: StreamCallId, callDisplayName: String) {
        Log.d("CustomNotificationHandler", "onMissedCall called: callId=$callId, callDisplayName=$callDisplayName")
        endCall(callId)
        super.onMissedCall(callId, callDisplayName)
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
 