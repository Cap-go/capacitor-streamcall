package ee.forgr.capacitor.streamcall

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import io.getstream.log.taggedLogger
import io.getstream.video.android.core.notifications.NotificationHandler
import io.getstream.video.android.core.notifications.StreamIntentResolver
import io.getstream.video.android.model.StreamCallId

class CustomStreamIntentResolver(private val context: Application) : StreamIntentResolver {

    private val logger by taggedLogger("CustomIntentResolver")
    private val PENDING_INTENT_FLAG = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    override fun searchIncomingCallPendingIntent(callId: StreamCallId, notificationId: Int): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationHandler.INTENT_EXTRA_CALL_CID, callId)
            action = "io.getstream.video.android.action.INCOMING_CALL"
        }

        return PendingIntent.getActivity(
            context,
            callId.cid.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun searchOutgoingCallPendingIntent(callId: StreamCallId, notificationId: Int): PendingIntent? {
        // For outgoing calls, create a specific intent that only opens webview when user taps
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("callCid", callId.cid)
            putExtra("action", "outgoing_call_tap") // Different action to distinguish from automatic events
            putExtra("openWebview", true)
            putExtra("fromNotification", true)
            putExtra("userTapped", true) // Explicitly mark this as user-initiated
            action = "ee.forgr.capacitor.streamcall.OUTGOING_CALL_TAP.${callId.cid}"
        }

        return PendingIntent.getActivity(
            context,
            callId.cid.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun searchNotificationCallPendingIntent(callId: StreamCallId, notificationId: Int): PendingIntent? =
        searchActivityPendingIntent(Intent(NotificationHandler.ACTION_NOTIFICATION), callId, notificationId)

    override fun searchMissedCallPendingIntent(callId: StreamCallId, notificationId: Int): PendingIntent? =
        searchActivityPendingIntent(Intent(NotificationHandler.ACTION_MISSED_CALL), callId, notificationId)

    override fun getDefaultPendingIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setPackage(context.packageName)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun searchLiveCallPendingIntent(callId: StreamCallId, notificationId: Int): PendingIntent? =
        searchActivityPendingIntent(Intent(NotificationHandler.ACTION_LIVE_CALL), callId, notificationId)

    override fun searchAcceptCallPendingIntent(callId: StreamCallId, notificationId: Int): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = NotificationHandler.ACTION_ACCEPT_CALL
            putExtra(NotificationHandler.INTENT_EXTRA_CALL_CID, callId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        return PendingIntent.getActivity(
            context,
            callId.cid.hashCode() + 10,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun searchRejectCallPendingIntent(callId: StreamCallId): PendingIntent? =
        searchBroadcastPendingIntent(Intent(NotificationHandler.ACTION_REJECT_CALL), callId)

    override fun searchEndCallPendingIntent(callId: StreamCallId): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = NotificationHandler.ACTION_LEAVE_CALL
            putExtra(NotificationHandler.INTENT_EXTRA_CALL_CID, callId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        return PendingIntent.getActivity(
            context,
            callId.cid.hashCode() + 30,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun searchOngoingCallPendingIntent(callId: StreamCallId, notificationId: Int): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = NotificationHandler.ACTION_ONGOING_CALL
            putExtra(NotificationHandler.INTENT_EXTRA_CALL_CID, callId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        return PendingIntent.getActivity(
            context,
            callId.cid.hashCode() + 20,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun searchBroadcastPendingIntent(baseIntent: Intent, callId: StreamCallId): PendingIntent? =
        searchResolveInfo { context.packageManager.queryBroadcastReceivers(baseIntent, 0) }?.let {
            getBroadcastForIntent(baseIntent, it, callId)
        }

    private fun searchActivityPendingIntent(baseIntent: Intent, callId: StreamCallId, notificationId: Int): PendingIntent? =
        searchResolveInfo { context.packageManager.queryIntentActivities(baseIntent, 0) }?.let {
            getActivityForIntent(baseIntent, it, callId, notificationId)
        }

    private fun searchResolveInfo(availableComponents: () -> List<ResolveInfo>): ResolveInfo? =
        availableComponents()
            .filter { it.activityInfo.packageName == context.packageName }
            .maxByOrNull { it.priority }

    private fun getActivityForIntent(baseIntent: Intent, resolveInfo: ResolveInfo, callId: StreamCallId, notificationId: Int, flags: Int = PENDING_INTENT_FLAG): PendingIntent {
        return PendingIntent.getActivity(
            context,
            notificationId,
            buildComponentIntent(baseIntent, resolveInfo, callId),
            flags
        )
    }

    private fun getBroadcastForIntent(baseIntent: Intent, resolveInfo: ResolveInfo, callId: StreamCallId, flags: Int = PENDING_INTENT_FLAG): PendingIntent {
        return PendingIntent.getBroadcast(context, 0, buildComponentIntent(baseIntent, resolveInfo, callId), flags)
    }

    private fun buildComponentIntent(baseIntent: Intent, resolveInfo: ResolveInfo, callId: StreamCallId): Intent {
        return Intent(baseIntent).apply {
            component = ComponentName(resolveInfo.activityInfo.applicationInfo.packageName, resolveInfo.activityInfo.name)
            putExtra(NotificationHandler.INTENT_EXTRA_CALL_CID, callId)
            putExtra(NotificationHandler.INTENT_EXTRA_NOTIFICATION_ID, NotificationHandler.INCOMING_CALL_NOTIFICATION_ID)
        }
    }
} 
