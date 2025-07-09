package ee.forgr.capacitor.streamcall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.getstream.video.android.core.notifications.NotificationHandler
import io.getstream.video.android.model.streamCallId

class AcceptCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("AcceptCallReceiver", "onReceive called with action: ${intent?.action}")

        val callCid = intent?.getStringExtra("call_cid")
        if (callCid == null) {
            Log.e("AcceptCallReceiver", "Call CID is null, cannot proceed.")
            return
        }
        Log.d("AcceptCallReceiver", "Accepting call with CID: $callCid")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = "io.getstream.video.android.action.ACCEPT_CALL"
            putExtra("call_cid", callCid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        if (launchIntent != null) {
            context.startActivity(launchIntent)
            Log.d("AcceptCallReceiver", "Started MainActivity to handle ACCEPT_CALL action.")
        } else {
            Log.e("AcceptCallReceiver", "Could not get launch intent for package ${context.packageName}")
        }
    }
} 
