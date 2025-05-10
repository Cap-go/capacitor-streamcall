package ee.forgr.capacitor.streamcall

import android.content.Context
import com.getcapacitor.Plugin
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import io.getstream.video.android.core.GEO
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.StreamVideoBuilder
import io.getstream.video.android.model.StreamCallId
import io.getstream.video.android.model.User
import io.getstream.video.android.ui.common.StreamCallActivity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.getstream.video.android.core.notifications.NotificationHandler
import io.getstream.video.android.ui.common.StreamCallActivityConfiguration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.json.JSONObject

@CapacitorPlugin(name = "StreamCall")
public class StreamCallPlugin : Plugin() {
    companion object {
        const val ACTION_SEND_CAPACITOR_EVENT = "SEND_CAPACITOR_EVENT"
        const val EXTRA_EVENT_NAME = "event_name"
        const val EXTRA_EVENT = "event"

        fun initializeStreamCallClient(contextToUse: Context) {
            android.util.Log.v("StreamCallPlugin", "Attempting to initialize streamVideo")

            // Try to get user credentials from repository
            val savedCredentials = SecureUserRepository.getInstance(contextToUse).loadCurrentUser()
            if (savedCredentials == null) {
                android.util.Log.v("StreamCallPlugin", "Saved credentials are null")
                return
            }

            try {
                if (StreamVideo.isInstalled) {
                    android.util.Log.v("StreamCallPlugin", "Found existing StreamVideo singleton client")
                    // TODO: check if the user is the same, if not redo the initialization or fail??
                } else {
                    android.util.Log.v("StreamCallPlugin", "No existing StreamVideo singleton client, creating new one")
                    val streamVideoClient = StreamVideoBuilder(
                        context = contextToUse,
                        apiKey = contextToUse.getString(R.string.CAPACITOR_STREAM_VIDEO_APIKEY),
                        geo = GEO.GlobalEdgeNetwork,
                        user = savedCredentials.user,
                        token = savedCredentials.tokenValue,
                        // loggingLevel = LoggingLevel(priority = Priority.VERBOSE)
                    ).build()
                }
            } catch (t: Throwable) {
                android.util.Log.e("StreamCallPlugin", "Cannot initialize the sdk", t)
                return
            }
        }
    }

    private val scope = MainScope() // Create a single scope for the plugin lifecycle

    private val eventReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SEND_CAPACITOR_EVENT) {
                val event = intent.getStringExtra(EXTRA_EVENT)
                val eventName = intent.getStringExtra(EXTRA_EVENT_NAME)
                if (event != null && eventName != null) {
                    try {
                        val jsonObject = JSONObject(event)
                        val jsObject = JSObject.fromJSONObject(jsonObject)

                        if (eventName == "callEvent" && jsObject.getString("state", "") == "joined") {
                            // This is a special case where we make the webview transparent. Required for the integration between the 2 activities
                            bridge?.webView?.setBackgroundColor(Color.TRANSPARENT)
                            this@StreamCallPlugin.activity.window.attributes = this@StreamCallPlugin.activity.window.attributes.apply {
                                alpha = 0.5f
                            }
                        }

                        this@StreamCallPlugin.notifyListeners(eventName, jsObject)
                        android.util.Log.d("StreamCallPlugin", "Received event: $event")
                    } catch (t: Throwable) {
                        android.util.Log.e("StreamCallPlugin", "Received a local intent for ACTION_SEND_CAPACITOR_EVENT, but cannot parse JSON. !!!!!!!!!!!!")
                        return
                    }
                } else {
                    android.util.Log.e("StreamCallPlugin", "Received a local intent for ACTION_SEND_CAPACITOR_EVENT, but not found all extras. !!!!!!!!!!!!")
                    return
                }
            }
        }
    }

    // Clean up resources when plugin is destroyed
    override fun handleOnDestroy() {
        scope.cancel() // Cancel the scope when the plugin is destroyed
        LocalBroadcastManager.getInstance(context).unregisterReceiver(eventReceiver)
        super.handleOnDestroy()
    }

    override fun load() {
        // here add communication with call activity
        LocalBroadcastManager.getInstance(context).registerReceiver(
            eventReceiver,
            IntentFilter(ACTION_SEND_CAPACITOR_EVENT)
        )
        super.load()
    }

    @PluginMethod
    fun login(call: PluginCall) {
        val token = call.getString("token")
        val userId = call.getString("userId")
        val name = call.getString("name")

        if (token == null || userId == null || name == null) {
            call.reject("Missing required parameters: token, userId, or name")
            return
        }

        val imageURL = call.getString("imageURL")

        try {
            // Create user object
            val user = User(
                id = userId,
                name = name,
                image = imageURL,
                custom = emptyMap() // Initialize with empty map for custom data
            )

            val savedCredentials = SecureUserRepository.getInstance(this.context).loadCurrentUser()
            val hadSavedCredentials = savedCredentials != null

            // Create credentials and save them
            val credentials = UserCredentials(user, token)
            SecureUserRepository.getInstance(context).save(credentials)

            if (StreamVideo.instanceOrNull() !== null) {
                // TODO: fix
                call.reject("SDK already initialized, re-loging is not yet supported")
                return
            }

            initializeStreamCallClient(this.context)

            val ret = JSObject()
            ret.put("success", true)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to login", e)
        }
    }


    @PluginMethod
    fun call(call: PluginCall) {
        val userIds = call.getArray("userIds")?.toList<String>()
        if (userIds.isNullOrEmpty()) {
            call.reject("Missing required parameter: userIds (array of user IDs)")
            return
        }

        try {
            val sdk = StreamVideo.instanceOrNull()
            if (sdk == null) {
                call.reject("StreamVideo not initialized")
                return
            }

            val selfUserId = sdk.userId

            val callType = call.getString("type") ?: "default"
            val shouldRing = call.getBoolean("ring") ?: true
            val callId = java.util.UUID.randomUUID().toString()
            val team = call.getString("team");

            android.util.Log.d("StreamCallPlugin", "Creating call:")
            android.util.Log.d("StreamCallPlugin", "- Call ID: $callId")
            android.util.Log.d("StreamCallPlugin", "- Call Type: $callType")
            android.util.Log.d("StreamCallPlugin", "- Users: $userIds")
            android.util.Log.d("StreamCallPlugin", "- Should Ring: $shouldRing")

            // Use the class scope instead of creating a new one
            scope.launch {
                try {
                    android.util.Log.d("StreamCallPlugin", "Attempting to create an activity for outgoing call $callId")
                    val finalMembers = userIds.filter { it != selfUserId }
                    finalMembers.forEach { it -> android.util.Log.d("StreamCallPlugin", "${it}") }

                    // Start the activity
                    activity?.runOnUiThread {
                        val intent = StreamCallActivity.callIntent(
                            context = this@StreamCallPlugin.context,
                            cid = StreamCallId(type = "default", id = callId),
                            clazz = CallActivity::class.java,
                            members = finalMembers,
                            action = NotificationHandler.ACTION_OUTGOING_CALL
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        this@StreamCallPlugin.context.startActivity(intent)
                    }

                    // Resolve the call with success
                    call.resolve(JSObject().apply {
                        put("success", true)
                    })
                } catch (e: Exception) {
                    android.util.Log.e("StreamCallPlugin", "Error making call: ${e.message}")
                    call.reject("Failed to make call: ${e.message}")
                }
            }
        } catch (e: Exception) {
            call.reject("Failed to make call: ${e.message}")
        }
    }
}
