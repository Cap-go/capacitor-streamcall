package ee.forgr.capacitor.streamcall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DeclineCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val callCid = intent?.getStringExtra("call_cid")
        if (callCid == null) {
            Log.e("DeclineCallReceiver", "call_cid is null")
            return
        }

        val (type, id) = callCid.toStreamCallId() ?: return
        
        val call = StreamCallManager.streamVideoClient?.call(type, id)
        if (call != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    call.reject()
                    Log.d("DeclineCallReceiver", "Call $callCid rejected")
                } catch (e: Exception) {
                    Log.e("DeclineCallReceiver", "Error rejecting call", e)
                }
            }
        } else {
            Log.w("DeclineCallReceiver", "Call object not found for cid: $callCid")
        }
    }
} 
