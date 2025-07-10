package ee.forgr.capacitor.streamcall

import TouchInterceptWrapper
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import io.getstream.video.android.compose.theme.VideoTheme
import io.getstream.video.android.compose.ui.components.call.activecall.CallContent
import io.getstream.video.android.compose.ui.components.call.renderer.FloatingParticipantVideo
import io.getstream.video.android.compose.ui.components.call.renderer.ParticipantVideo
import io.getstream.video.android.compose.ui.components.call.renderer.RegularVideoRendererStyle
import io.getstream.video.android.compose.ui.components.video.VideoScalingType
import io.getstream.video.android.core.Call
import io.getstream.video.android.core.CameraDirection
import io.getstream.video.android.core.internal.InternalStreamVideoApi
import io.getstream.video.android.core.notifications.NotificationHandler
import io.getstream.video.android.model.User
import io.getstream.video.android.model.streamCallId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import org.json.JSONObject
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

// I am not a religious pearson, but at this point, I am not sure even god himself would understand this code
// It's a spaghetti-like, tangled, unreadable mess and frankly, I am deeply sorry for the code crimes commited in the Android impl
@CapacitorPlugin(name = "StreamCall")
class StreamCallPlugin : Plugin() {
    private var overlayView: ComposeView? = null
    private var barrierView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var callFragment: StreamCallFragment? = null
    private var touchInterceptWrapper: TouchInterceptWrapper? = null
    
    // Track permission request timing and attempts
    private var permissionRequestStartTime: Long = 0
    private var permissionAttemptCount: Int = 0

    private var pendingLoginCall: PluginCall? = null
    private var pendingLogoutCall: PluginCall? = null
    
    // Store pending call information for permission handling
    private var pendingCall: PluginCall? = null
    private var pendingCallUserIds: List<String>? = null
    private var pendingCallType: String? = null
    private var pendingCallShouldRing: Boolean? = null
    private var pendingCallTeam: String? = null
    private var pendingCustomObject: JSObject? = null
    private var pendingAcceptCall: Call? = null // Store the actual call object for acceptance

    private fun runOnMainThread(action: () -> Unit) {
        mainHandler.post { action() }
    }

    override fun handleOnPause() {
        super.handleOnPause()
    }

    override fun handleOnResume() {
        super.handleOnResume()
        
        Log.d("StreamCallPlugin", "handleOnResume: App resumed, checking permissions and pending operations")
        Log.d("StreamCallPlugin", "handleOnResume: Have pendingCall: ${pendingCall != null}")
        Log.d("StreamCallPlugin", "handleOnResume: Have pendingCallUserIds: ${pendingCallUserIds != null}")
        Log.d("StreamCallPlugin", "handleOnResume: Have pendingAcceptCall: ${pendingAcceptCall != null}")
        Log.d("StreamCallPlugin", "handleOnResume: Permission attempt count: $permissionAttemptCount")
        
        // Check if permissions were granted after returning from settings or permission dialog
        if (checkPermissions()) {
            Log.d("StreamCallPlugin", "handleOnResume: Permissions are now granted")
            // Handle any pending calls that were waiting for permissions
            handlePermissionGranted()
        } else if (pendingCall != null || pendingAcceptCall != null) {
            Log.d("StreamCallPlugin", "handleOnResume: Permissions still not granted, but have pending operations")
            // If we have pending operations but permissions are still not granted,
            // it means the permission dialog was dismissed without granting
            // We should trigger our retry logic if we haven't exhausted attempts
            if (permissionAttemptCount > 0) {
                Log.d("StreamCallPlugin", "handleOnResume: Permission dialog was dismissed, treating as denial (attempt: $permissionAttemptCount)")
                val timeSinceRequest = System.currentTimeMillis() - permissionRequestStartTime
                handlePermissionDenied(timeSinceRequest)
            } else {
                Log.d("StreamCallPlugin", "handleOnResume: No permission attempts yet, starting permission request")
                // If we have pending operations but no attempts yet, start the permission flow
                if (pendingAcceptCall != null) {
                    Log.d("StreamCallPlugin", "handleOnResume: Have active call waiting for permissions, requesting now")
                    permissionAttemptCount = 0
                    requestPermissions()
                } else if (pendingCall != null && pendingCallUserIds != null) {
                    Log.d("StreamCallPlugin", "handleOnResume: Have outgoing call waiting for permissions, requesting now")
                    permissionAttemptCount = 0
                    requestPermissions()
                }
            }
        } else {
            Log.d("StreamCallPlugin", "handleOnResume: No pending operations, nothing to handle")
        }
    }

    override fun load() {
        // general init
        setupViews()
        super.load()
        observeCallState()
        checkPermissions()
        // Register broadcast receiver for ACCEPT_CALL action with high priority
        val filter = IntentFilter("io.getstream.video.android.action.ACCEPT_CALL")
        filter.priority = 999 // Set high priority to ensure it captures the intent
        ContextCompat.registerReceiver(activity, acceptCallReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Log.d("StreamCallPlugin", "Registered broadcast receiver for ACCEPT_CALL action with high priority")

        // Start the background service to keep the app alive
        val serviceIntent = Intent(activity, StreamCallBackgroundService::class.java)
        activity.startService(serviceIntent)
        Log.d("StreamCallPlugin", "Started StreamCallBackgroundService to keep app alive")
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun handleOnNewIntent(intent: Intent) {
        Log.d("StreamCallPlugin", "handleOnNewIntent called: action=${intent.action}, data=${intent.data}, extras=${intent.extras}")
        super.handleOnNewIntent(intent)

        val action = intent.action
        val data = intent.data
        val extras = intent.extras
        Log.d("StreamCallPlugin", "handleOnNewIntent: Parsed action: $action")

        if (action === "io.getstream.video.android.action.INCOMING_CALL") {
            Log.d("StreamCallPlugin", "handleOnNewIntent: Matched INCOMING_CALL action")
            // We need to make sure the activity is visible on locked screen in such case
            changeActivityAsVisibleOnLockScreen(this@StreamCallPlugin.activity, true)
            activity?.runOnUiThread {
                val cid = intent.streamCallId(NotificationHandler.INTENT_EXTRA_CALL_CID)
                Log.d("StreamCallPlugin", "handleOnNewIntent: INCOMING_CALL - Extracted cid: $cid")
                if (cid != null) {
                    Log.d("StreamCallPlugin", "handleOnNewIntent: INCOMING_CALL - cid is not null, processing.")
                    val call = StreamCallManager.streamVideoClient?.call(id = cid.id, type = cid.type)
                    Log.d("StreamCallPlugin", "handleOnNewIntent: INCOMING_CALL - Got call object: ${call?.id}")

                    // Try to get caller information from the call
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            val callInfo = call?.get()
                            val callerInfo = callInfo?.getOrNull()?.call?.createdBy
                            val custom = callInfo?.getOrNull()?.call?.custom
                            
                            val payload = JSObject().apply {
                                put("cid", cid.cid)
                                put("type", "incoming")
                                if (callerInfo != null) {
                                    val caller = JSObject().apply {
                                        put("userId", callerInfo.id)
                                        put("name", callerInfo.name ?: "")
                                        put("imageURL", callerInfo.image ?: "")
                                        put("role", callerInfo.role)
                                    }
                                    put("caller", caller)
                                }
                                if (custom != null) {
                                    put("custom", JSONObject(custom))
                                }
                            }
                            
                            // Notify WebView/JS about incoming call so it can render its own UI
                            notifyListeners("incomingCall", payload, true)
                            
                            // Delay bringing app to foreground to allow the event to be processed first
                            kotlinx.coroutines.delay(500) // 500ms delay
                            bringAppToForeground()
                        } catch (e: Exception) {
                            Log.e("StreamCallPlugin", "Error getting call info for incoming call", e)
                            // Fallback to basic payload without caller info
                            val payload = JSObject().apply {
                                put("cid", cid.cid)
                                put("type", "incoming")
                            }
                            notifyListeners("incomingCall", payload, true)
                            
                            // Delay bringing app to foreground to allow the event to be processed first
                            kotlinx.coroutines.delay(500) // 500ms delay
                            bringAppToForeground()
                        }
                    }
                } else {
                    Log.w("StreamCallPlugin", "handleOnNewIntent: INCOMING_CALL - cid is null. Cannot process.")
                }
            }
        } else if (action === "io.getstream.video.android.action.ACCEPT_CALL") {
            Log.d("StreamCallPlugin", "handleOnNewIntent: Matched ACCEPT_CALL action")
            val callCidString = intent.getStringExtra("call_cid")
            if (callCidString == null) {
                Log.e("StreamCallPlugin", "handleOnNewIntent: ACCEPT_CALL - call_cid string extra is null.")
                return
            }

            val callIdParts = callCidString.split(":")
            if (callIdParts.size < 2) {
                Log.e("StreamCallPlugin", "handleOnNewIntent: ACCEPT_CALL - Invalid call CID format: $callCidString")
                return
            }

            val callType = callIdParts[0]
            val callId = callIdParts[1]
            
            Log.d("StreamCallPlugin", "handleOnNewIntent: ACCEPT_CALL - Reconstructed cid: $callType:$callId")

            val call = StreamCallManager.streamVideoClient?.call(id = callId, type = callType)
            if (call != null) {
                kotlinx.coroutines.GlobalScope.launch {
                    internalAcceptCall(call, requestPermissionsAfter = !checkPermissions())
                }
                bringAppToForeground()
            } else {
                Log.e("StreamCallPlugin", "handleOnNewIntent: ACCEPT_CALL - Call object is null for cid: $callCidString")
            }
        }
        // Log the intent information
        Log.d("StreamCallPlugin", "New Intent - Action: $action")
        Log.d("StreamCallPlugin", "New Intent - Data: $data")
        Log.d("StreamCallPlugin", "New Intent - Extras: $extras")
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun declineCall(call: Call) {
        Log.d("StreamCallPlugin", "declineCall called for call: ${call.id}")
        kotlinx.coroutines.GlobalScope.launch {
            try {
                call.reject()
                changeActivityAsVisibleOnLockScreen(this@StreamCallPlugin.activity, false)

                // Notify that call has ended using our helper
                notifyListeners("callEvent", JSObject().put("callId", call.id).put("state", "rejected"))

                hideIncomingCall()
            } catch (e: Exception) {
                Log.e("StreamCallPlugin", "Error declining call: ${e.message}")
            }
        }
    }

    private fun hideIncomingCall() {
        activity?.runOnUiThread {
            // No dedicated incoming-call native view anymore; UI handled by web layer
        }
    }

    @OptIn(InternalStreamVideoApi::class)
    private fun setupViews() {
        val context = context
        val originalParent = bridge?.webView?.parent as? ViewGroup ?: return

        // Wrap original parent with TouchInterceptWrapper to allow touch passthrough
        val rootParent = originalParent.parent as? ViewGroup
        val indexInRoot = rootParent?.indexOfChild(originalParent) ?: -1
        if (rootParent != null && indexInRoot >= 0) {
            rootParent.removeViewAt(indexInRoot)
            touchInterceptWrapper = TouchInterceptWrapper(originalParent).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            rootParent.addView(touchInterceptWrapper, indexInRoot)
        }

        val parent: ViewGroup = touchInterceptWrapper ?: originalParent

        // Make WebView initially visible and opaque
        bridge?.webView?.setBackgroundColor(Color.WHITE) // Or whatever background color suits your app

        // Create and add overlay view below WebView for calls
        overlayView = ComposeView(context).apply {
            isVisible = false // Start invisible until a call starts
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        parent.addView(overlayView, 0)  // Add at index 0 to ensure it's below WebView

        // Initialize with active call content
        setOverlayContent()

        // Create barrier view (above webview for blocking interaction during call setup)
        barrierView = View(context).apply {
            isVisible = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor("#1a242c".toColorInt())
        }
        parent.addView(barrierView, parent.indexOfChild(bridge?.webView) + 1) // Add above WebView
    }

    /**
     * Centralized function to set the overlay content with call UI.
     * This handles all the common Compose UI setup for video calls.
     */
    private fun setOverlayContent(call: Call? = null) {
        overlayView?.setContent {
            VideoTheme {
                val activeCall = call ?: StreamCallManager.streamVideoClient?.state?.activeCall?.collectAsState()?.value
                if (activeCall != null) {

                    val currentLocal by activeCall.state.me.collectAsStateWithLifecycle()

                    CallContent(
                        call = activeCall,
                        enableInPictureInPicture = false,
                        onBackPressed = { /* Handle back press if needed */ },
                        controlsContent = { /* Empty to disable native controls */ },
                        appBarContent = { /* Empty to disable app bar with stop call button */ },
                        videoRenderer = { videoModifier, videoCall, videoParticipant, videoStyle ->
                            ParticipantVideo(
                                modifier = videoModifier,
                                call = videoCall,
                                participant = videoParticipant,
                                style = videoStyle,
                                actionsContent = {_, _, _ -> {}},
                                scalingType = VideoScalingType.SCALE_ASPECT_FIT
                            )
                        },
                        floatingVideoRenderer = { call, parentSize ->
                            currentLocal?.let {
                                FloatingParticipantVideo(
                                    call = call,
                                    participant = currentLocal!!,
                                    style = RegularVideoRendererStyle().copy(isShowingConnectionQualityIndicator = false),
                                    parentBounds = parentSize,
                                    videoRenderer = { _ ->
                                        ParticipantVideo(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(VideoTheme.shapes.dialog),
                                            call = call,
                                            participant = it,
                                            style = RegularVideoRendererStyle().copy(isShowingConnectionQualityIndicator = false),
                                            actionsContent = {_, _, _ -> {}},
                                        )
                                    }
                                )
                            }

                        }
                    )
                }
            }
        }
    }

    @PluginMethod
    fun login(call: PluginCall) {
        val token = call.getString("token")
        val userId = call.getString("userId")
        val name = call.getString("name")
        val apiKey = call.getString("apiKey") ?: ApiKeyManager.getEffectiveApiKey()

        if (token == null || userId == null || name == null) {
            call.reject("Missing required parameters: token, userId, name")
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

            // Save the call to be resolved later by the observer
            pendingLoginCall = call

            // Create credentials and save them
            val credentials = UserCredentials(user, token)
            SecureUserRepository.getInstance(context).save(credentials)

            // Initialize Stream Video with new credentials
            StreamCallManager.login(user, token, apiKey)

        } catch (e: Exception) {
            call.reject("Failed to login", e)
        }
    }

    @PluginMethod
    fun logout(call: PluginCall) {
        try {
            // Clear stored credentials
            SecureUserRepository.getInstance(context).removeCurrentUser()
            pendingLogoutCall = call
            StreamCallManager.logout()
        } catch (e: Exception) {
            call.reject("Failed to logout", e)
        }
    }

    private fun moveAllActivitiesToBackgroundOrKill(context: Context) {
        try {
            // The original logic for killing the app was removed to prevent memory leaks.
            // The reliable action is to send the app to the background.
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d("StreamCallPlugin", "Moving app to background.")
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Failed to move app to background", e)
        }
    }

    private fun observeCallState() {
        CoroutineScope(Dispatchers.Main).launch {
            StreamCallManager.callState.onEach { stateUpdate ->
                stateUpdate?.let {
                    Log.d("StreamCallPlugin", "Received call state update: ${it.type} with data ${it.data}")
                    when (it.type) {
                        "loginSuccess" -> {
                            pendingLoginCall?.resolve(it.data)
                            pendingLoginCall = null
                        }
                        "loginError" -> {
                            pendingLoginCall?.reject(it.data.getString("error"))
                            pendingLoginCall = null
                        }
                        "logoutSuccess" -> {
                            pendingLogoutCall?.resolve(it.data)
                            pendingLogoutCall = null
                        }
                        "callCreationSuccess" -> {
                             pendingCall?.resolve(it.data)
                             pendingCall = null
                        }
                        else -> {
                            // Forward other events to the web layer
                            notifyListeners(it.type, it.data)
                        }
                    }
                }
            }.launchIn(this)
        }
    }

    @PluginMethod
    fun acceptCall(call: PluginCall) {
        Log.d("StreamCallPlugin", "acceptCall called")
        try {
            val streamVideoCall = StreamCallManager.streamVideoClient?.state?.ringingCall?.value
            if (streamVideoCall == null) {
                call.reject("Ringing call is null")
                return
            }
            
            Log.d("StreamCallPlugin", "acceptCall: Accepting call immediately, will handle permissions after")
            
            // Accept call immediately regardless of permissions - time is critical!
            kotlinx.coroutines.GlobalScope.launch {
                try {
                    internalAcceptCall(streamVideoCall, requestPermissionsAfter = !checkPermissions())
                    call.resolve(JSObject().apply {
                        put("success", true)
                    })
                } catch (e: Exception) {
                    Log.e("StreamCallPlugin", "Error accepting call", e)
                    call.reject("Failed to accept call: ${e.message}")
                }
            }
        } catch (t: Throwable) {
            Log.d("StreamCallPlugin", "JS -> acceptCall fail", t)
            call.reject("Cannot acceptCall")
        }
    }

    @PluginMethod
    fun rejectCall(call: PluginCall) {
        Log.d("StreamCallPlugin", "rejectCall called")
        try {
            val streamVideoCall = StreamCallManager.streamVideoClient?.state?.ringingCall?.value
            if (streamVideoCall == null) {
                call.reject("Ringing call is null")
                return
            }
            kotlinx.coroutines.GlobalScope.launch {
                declineCall(streamVideoCall)
            }
        } catch (t: Throwable) {
            Log.d("StreamCallPlugin", "JS -> rejectCall fail", t)
            call.reject("Cannot rejectCall")
        }
    }

    @OptIn(DelicateCoroutinesApi::class, InternalStreamVideoApi::class)
    internal fun internalAcceptCall(call: Call, requestPermissionsAfter: Boolean = false) {
        Log.d("StreamCallPlugin", "internalAcceptCall: Entered for call: ${call.id}, requestPermissionsAfter: $requestPermissionsAfter")

        kotlinx.coroutines.GlobalScope.launch {
            try {
                Log.d("StreamCallPlugin", "internalAcceptCall: Coroutine started for call ${call.id}")

                // Hide incoming call view first
                runOnMainThread {
                    Log.d("StreamCallPlugin", "internalAcceptCall: Hiding incoming call view for call ${call.id}")
                    // No dedicated incoming-call native view anymore; UI handled by web layer
                }
                Log.d("StreamCallPlugin", "internalAcceptCall: Incoming call view hidden for call ${call.id}")

                // Accept and join call immediately - don't wait for permissions!
                Log.d("StreamCallPlugin", "internalAcceptCall: Accepting call immediately for ${call.id}")
                call.accept()
                Log.d("StreamCallPlugin", "internalAcceptCall: call.accept() completed for call ${call.id}")
                call.join()
                Log.d("StreamCallPlugin", "internalAcceptCall: call.join() completed for call ${call.id}")
                StreamCallManager.streamVideoClient?.state?.setActiveCall(call)
                Log.d("StreamCallPlugin", "internalAcceptCall: setActiveCall completed for call ${call.id}")

                // Notify that call has started using helper
                notifyListeners("callEvent", JSObject().put("callId", call.id).put("state", "joined"))
                Log.d("StreamCallPlugin", "internalAcceptCall: updateCallStatusAndNotify(joined) called for ${call.id}")

                // Show overlay view with the active call and make webview transparent
                runOnMainThread {
                    Log.d("StreamCallPlugin", "internalAcceptCall: Updating UI for active call ${call.id} - setting overlay visible.")
                    bridge?.webView?.setBackgroundColor(Color.TRANSPARENT) // Make webview transparent
                    Log.d("StreamCallPlugin", "internalAcceptCall: WebView background set to transparent for call ${call.id}")
                    bridge?.webView?.bringToFront() // Ensure WebView is on top and transparent
                    Log.d("StreamCallPlugin", "internalAcceptCall: WebView brought to front for call ${call.id}")
                    
                    // Enable camera/microphone based on permissions
                    val hasPermissions = checkPermissions()
                    Log.d("StreamCallPlugin", "internalAcceptCall: Has permissions: $hasPermissions for call ${call.id}")
                    
                    call.microphone.setEnabled(hasPermissions)
                    call.camera.setEnabled(hasPermissions)
                    Log.d("StreamCallPlugin", "internalAcceptCall: Microphone and camera set to $hasPermissions for call ${call.id}")
                    
                    Log.d("StreamCallPlugin", "internalAcceptCall: Setting CallContent with active call ${call.id}")
                    setOverlayContent(call)
                    Log.d("StreamCallPlugin", "internalAcceptCall: Content set for overlayView for call ${call.id}")
                    overlayView?.isVisible = true
                    Log.d("StreamCallPlugin", "internalAcceptCall: OverlayView set to visible for call ${call.id}, isVisible: ${overlayView?.isVisible}")

                    // Ensure overlay is behind WebView by adjusting its position in the parent
                    val parent = overlayView?.parent as? ViewGroup
                    parent?.removeView(overlayView)
                    parent?.addView(overlayView, 0) // Add at index 0 to ensure it's behind other views
                    Log.d("StreamCallPlugin", "internalAcceptCall: OverlayView re-added to parent at index 0 for call ${call.id}")
                    
                    // Add a small delay to ensure UI refresh
                    mainHandler.postDelayed({
                        Log.d("StreamCallPlugin", "internalAcceptCall: Delayed UI check, overlay visible: ${overlayView?.isVisible} for call ${call.id}")
                        if (overlayView?.isVisible == true) {
                            overlayView?.invalidate()
                            overlayView?.requestLayout()
                            Log.d("StreamCallPlugin", "internalAcceptCall: UI invalidated and layout requested for call ${call.id}")
                            // Force refresh with active call from client
                            val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
                            if (activeCall != null) {
                                Log.d("StreamCallPlugin", "internalAcceptCall: Force refreshing CallContent with active call ${activeCall.id}")
                                setOverlayContent(activeCall)
                                Log.d("StreamCallPlugin", "internalAcceptCall: Content force refreshed for call ${activeCall.id}")
                            } else {
                                Log.w("StreamCallPlugin", "internalAcceptCall: Active call is null during force refresh for call ${call.id}")
                            }
                        } else {
                            Log.w("StreamCallPlugin", "internalAcceptCall: overlayView not visible after delay for call ${call.id}")
                        }
                    }, 1000) // Increased delay to ensure all events are processed
                }

                // Request permissions after joining if needed
                if (requestPermissionsAfter) {
                    Log.d("StreamCallPlugin", "internalAcceptCall: Requesting permissions after call acceptance for ${call.id}")
                    runOnMainThread {
                        // Store reference to the active call for enabling camera/mic later
                        pendingAcceptCall = call
                        Log.d("StreamCallPlugin", "internalAcceptCall: Set pendingAcceptCall to ${call.id}, resetting attempt count")
                        permissionAttemptCount = 0
                        requestPermissions()
                    }
                }
                
            } catch (e: Exception) {
                Log.e("StreamCallPlugin", "internalAcceptCall: Error accepting call ${call.id}: ${e.message}", e)
                runOnMainThread {
                    android.widget.Toast.makeText(
                        context,
                        "Failed to join call: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Function to check required permissions
    private fun checkPermissions(): Boolean {
        Log.d("StreamCallPlugin", "checkPermissions: Entered")
        val audioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        Log.d("StreamCallPlugin", "checkPermissions: RECORD_AUDIO permission status: $audioPermission (Granted=${PackageManager.PERMISSION_GRANTED})")
        val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        Log.d("StreamCallPlugin", "checkPermissions: CAMERA permission status: $cameraPermission (Granted=${PackageManager.PERMISSION_GRANTED})")
        val allGranted = audioPermission == PackageManager.PERMISSION_GRANTED && cameraPermission == PackageManager.PERMISSION_GRANTED
        Log.d("StreamCallPlugin", "checkPermissions: All permissions granted: $allGranted")
        return allGranted
    }

    // Override to handle permission results
    override fun handleRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("StreamCallPlugin", "handleRequestPermissionsResult: Entered. RequestCode: $requestCode, Attempt: $permissionAttemptCount")
        Log.d("StreamCallPlugin", "handleRequestPermissionsResult: Expected requestCode: 9001")
        
        if (requestCode == 9001) {
            val responseTime = System.currentTimeMillis() - permissionRequestStartTime
            Log.d("StreamCallPlugin", "handleRequestPermissionsResult: Response time: ${responseTime}ms")
            
            logPermissionResults(permissions, grantResults)
            
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.i("StreamCallPlugin", "handleRequestPermissionsResult: All permissions GRANTED.")
                // Reset attempt count on success
                permissionAttemptCount = 0
                handlePermissionGranted()
            } else {
                Log.e("StreamCallPlugin", "handleRequestPermissionsResult: Permissions DENIED. Attempt: $permissionAttemptCount")
                handlePermissionDenied(responseTime)
            }
        } else {
            Log.w("StreamCallPlugin", "handleRequestPermissionsResult: Received unknown requestCode: $requestCode")
        }
    }

    private fun logPermissionResults(permissions: Array<out String>, grantResults: IntArray) {
        Log.d("StreamCallPlugin", "logPermissionResults: Logging permission results:")
        for (i in permissions.indices) {
            val permission = permissions[i]
            val grantResult = if (grantResults.size > i) grantResults[i] else -999 // -999 for safety if arrays mismatch
            val resultString = if (grantResult == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED ($grantResult)"
            Log.d("StreamCallPlugin", "  Permission: $permission, Result: $resultString")
        }
    }

    private fun handlePermissionGranted() {
        Log.d("StreamCallPlugin", "handlePermissionGranted: Processing granted permissions")
        
        // Reset attempt count since permissions are now granted
        permissionAttemptCount = 0
        
        // Determine what type of pending operation we have
        val hasOutgoingCall = pendingCall != null && pendingCallUserIds != null
        val hasActiveCallNeedingPermissions = pendingAcceptCall != null
        
        Log.d("StreamCallPlugin", "handlePermissionGranted: hasOutgoingCall=$hasOutgoingCall, hasActiveCallNeedingPermissions=$hasActiveCallNeedingPermissions")
        
        when {
            hasOutgoingCall -> {
                // Outgoing call creation was waiting for permissions
                Log.d("StreamCallPlugin", "handlePermissionGranted: Executing pending outgoing call with ${pendingCallUserIds?.size} users")
                executePendingCall()
            }
            
            hasActiveCallNeedingPermissions -> {
                // Active call needing camera/microphone enabled 
                val callToHandle = pendingAcceptCall!!
                val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
                
                Log.d("StreamCallPlugin", "handlePermissionGranted: Processing call ${callToHandle.id}")
                Log.d("StreamCallPlugin", "handlePermissionGranted: Active call in state: ${activeCall?.id}")
                
                if (activeCall != null && activeCall.id == callToHandle.id) {
                    // Call is already active - enable camera/microphone
                    Log.d("StreamCallPlugin", "handlePermissionGranted: Enabling camera/microphone for active call ${callToHandle.id}")
                    runOnMainThread {
                        try {
                            callToHandle.microphone.setEnabled(true)
                            callToHandle.camera.setEnabled(true)
                            Log.d("StreamCallPlugin", "handlePermissionGranted: Camera and microphone enabled for call ${callToHandle.id}")
                            
                            // Show success message
                            android.widget.Toast.makeText(
                                context,
                                "Camera and microphone enabled",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Log.e("StreamCallPlugin", "Error enabling camera/microphone", e)
                        }
                        clearPendingCall()
                    }
                } else if (pendingCall != null) {
                    // Call not active yet - accept it (old flow, shouldn't happen with new flow)
                    Log.d("StreamCallPlugin", "handlePermissionGranted: Accepting pending incoming call ${callToHandle.id}")
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            internalAcceptCall(callToHandle)
                            pendingCall?.resolve(JSObject().apply {
                                put("success", true)
                            })
                        } catch (e: Exception) {
                            Log.e("StreamCallPlugin", "Error accepting call after permission grant", e)
                            pendingCall?.reject("Failed to accept call: ${e.message}")
                        } finally {
                            clearPendingCall()
                        }
                    }
                } else {
                    // Just enable camera/mic for the stored call even if not currently active
                    Log.d("StreamCallPlugin", "handlePermissionGranted: Enabling camera/microphone for stored call ${callToHandle.id}")
                    runOnMainThread {
                        try {
                            callToHandle.microphone.setEnabled(true)
                            callToHandle.camera.setEnabled(true)
                            Log.d("StreamCallPlugin", "handlePermissionGranted: Camera and microphone enabled for stored call ${callToHandle.id}")
                            
                            android.widget.Toast.makeText(
                                context,
                                "Camera and microphone enabled",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Log.e("StreamCallPlugin", "Error enabling camera/microphone for stored call", e)
                        }
                        clearPendingCall()
                    }
                }
            }
            
            pendingCall != null -> {
                // We have a pending call but unclear what type - fallback handling
                Log.w("StreamCallPlugin", "handlePermissionGranted: Have pendingCall but unclear operation type")
                Log.w("StreamCallPlugin", "  - pendingCallUserIds: ${pendingCallUserIds != null}")
                Log.w("StreamCallPlugin", "  - pendingAcceptCall: ${pendingAcceptCall != null}")
                
                // Try fallback to current ringing call for acceptance
                val ringingCall = StreamCallManager.streamVideoClient?.state?.ringingCall?.value
                if (ringingCall != null) {
                    Log.d("StreamCallPlugin", "handlePermissionGranted: Fallback - accepting current ringing call ${ringingCall.id}")
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            internalAcceptCall(ringingCall)
                            pendingCall?.resolve(JSObject().apply {
                                put("success", true)
                            })
                        } catch (e: Exception) {
                            Log.e("StreamCallPlugin", "Error accepting fallback call after permission grant", e)
                            pendingCall?.reject("Failed to accept call: ${e.message}")
                        } finally {
                            clearPendingCall()
                        }
                    }
                } else {
                    Log.w("StreamCallPlugin", "handlePermissionGranted: No ringing call found for fallback")
                    pendingCall?.reject("Unable to determine pending operation")
                    clearPendingCall()
                }
            }
            
            else -> {
                Log.d("StreamCallPlugin", "handlePermissionGranted: No pending operations to handle")
            }
        }
    }

    private fun handlePermissionDenied(responseTime: Long) {
        Log.d("StreamCallPlugin", "handlePermissionDenied: Response time: ${responseTime}ms, Attempt: $permissionAttemptCount")
        
        // Check if the response was instant (< 500ms) indicating "don't ask again"
        val instantDenial = responseTime < 500
        Log.d("StreamCallPlugin", "handlePermissionDenied: Instant denial detected: $instantDenial")
        
        if (instantDenial) {
            // If it's an instant denial (don't ask again), go straight to settings dialog
            Log.d("StreamCallPlugin", "handlePermissionDenied: Instant denial, showing settings dialog")
            showPermissionSettingsDialog()
        } else if (permissionAttemptCount < 2) {
            // Try asking again immediately if this is the first denial
            Log.d("StreamCallPlugin", "handlePermissionDenied: First denial (attempt $permissionAttemptCount), asking again immediately")
            requestPermissions() // This will increment the attempt count
        } else {
            // Second denial - show settings dialog (final ask)
            Log.d("StreamCallPlugin", "handlePermissionDenied: Second denial (attempt $permissionAttemptCount), showing settings dialog (final ask)")
            showPermissionSettingsDialog()
        }
    }

    private fun executePendingCall() {
        val call = pendingCall
        val userIds = pendingCallUserIds
        val callType = pendingCallType
        val shouldRing = pendingCallShouldRing
        val team = pendingCallTeam
        val custom = pendingCustomObject?.toMap()
        
        if (call != null && userIds != null && callType != null && shouldRing != null) {
            Log.d("StreamCallPlugin", "executePendingCall: Executing call with ${userIds.size} users")
            
            // Clear pending call data
            clearPendingCall()
            
            // Execute the call creation logic via the manager
            val callId = java.util.UUID.randomUUID().toString()
            StreamCallManager.call(callType, callId, userIds, shouldRing, team, custom)
            // The plugin call will be resolved/rejected based on the StateFlow events from the manager
        } else {
            Log.w("StreamCallPlugin", "executePendingCall: Missing pending call data")
            call?.reject("Internal error: missing call parameters")
            clearPendingCall()
        }
    }

    private fun clearPendingCall() {
        pendingCall = null
        pendingCallUserIds = null
        pendingCallType = null
        pendingCallShouldRing = null
        pendingCallTeam = null
        pendingAcceptCall = null
        pendingCustomObject = null
        permissionAttemptCount = 0 // Reset attempt count when clearing
    }



    @OptIn(DelicateCoroutinesApi::class, InternalStreamVideoApi::class)
    private fun createAndStartCall(call: PluginCall, userIds: List<String>, callType: String, shouldRing: Boolean, team: String?, custom: JSObject?) {
        val callId = java.util.UUID.randomUUID().toString()
        StreamCallManager.call(callType, callId, userIds, shouldRing, team, custom?.toMap())
        // The plugin call will be resolved/rejected based on the StateFlow events from the manager
        // We can optimistically resolve here, or wait for a confirmation event.
        // For now, let's assume the manager will send an event that the observer handles.
        call.resolve(JSObject().put("success", true).put("message", "Call initiated"))
    }

    // Function to request required permissions
    private fun requestPermissions() {
        permissionAttemptCount++
        Log.d("StreamCallPlugin", "requestPermissions: Attempt #$permissionAttemptCount - Requesting RECORD_AUDIO and CAMERA permissions.")
        
        // Record timing for instant denial detection
        permissionRequestStartTime = System.currentTimeMillis()
        Log.d("StreamCallPlugin", "requestPermissions: Starting permission request at $permissionRequestStartTime")
        
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
            9001 // Use high request code to avoid Capacitor conflicts
        )
        
        Log.d("StreamCallPlugin", "requestPermissions: Permission request initiated with code 9001")
    }

    private fun showPermissionSettingsDialog() {
        activity?.runOnUiThread {
            val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
            val hasActiveCall = activeCall != null && pendingAcceptCall != null && activeCall.id == pendingAcceptCall?.id
            
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Enable Permissions")
            
            if (hasActiveCall) {
                builder.setMessage("Your call is active but camera and microphone are disabled.\n\nWould you like to open Settings to enable video and audio?")
                builder.setNegativeButton("Continue without") { _, _ ->
                    Log.d("StreamCallPlugin", "User chose to continue call without permissions")
                    showPermissionRequiredMessage()
                }
            } else {
                builder.setMessage("To make video calls, this app needs Camera and Microphone permissions.\n\nWould you like to open Settings to enable them?")
                builder.setNegativeButton("Cancel") { _, _ ->
                    Log.d("StreamCallPlugin", "User declined to grant permissions - final rejection")
                    showPermissionRequiredMessage()
                }
            }
            
            builder.setPositiveButton("Open Settings") { _, _ ->
                Log.d("StreamCallPlugin", "User chose to open app settings")
                openAppSettings()
                // Don't reject the call yet - let them go to settings and come back
            }
            
            builder.setCancelable(false)
            builder.show()
        }
    }

    private fun showPermissionRequiredMessage() {
        activity?.runOnUiThread {
            val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
            val hasActiveCall = activeCall != null && pendingAcceptCall != null && activeCall.id == pendingAcceptCall?.id
            
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Permissions Required")
            
            if (hasActiveCall) {
                builder.setMessage("Camera and microphone permissions are required for video calling. Your call will continue without camera/microphone.")
            } else {
                builder.setMessage("Camera/microphone permission is required for the calling functionality of this app")
            }
            
            builder.setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                handleFinalPermissionDenial()
            }
            builder.setCancelable(false)
            builder.show()
        }
    }

    private fun handleFinalPermissionDenial() {
        Log.d("StreamCallPlugin", "handleFinalPermissionDenial: Processing final permission denial")
        
        val hasOutgoingCall = pendingCall != null && pendingCallUserIds != null
        val hasIncomingCall = pendingCall != null && pendingAcceptCall != null
        val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
        
        when {
            hasOutgoingCall -> {
                // Outgoing call that couldn't be created due to permissions
                Log.d("StreamCallPlugin", "handleFinalPermissionDenial: Rejecting outgoing call creation")
                pendingCall?.reject("Permissions required for call. Please grant them.")
                clearPendingCall()
            }
            
            hasIncomingCall && activeCall != null && activeCall.id == pendingAcceptCall?.id -> {
                // Incoming call that's already active - DON'T end the call, just keep it without camera/mic
                Log.d("StreamCallPlugin", "handleFinalPermissionDenial: Incoming call already active, keeping call without camera/mic")
                
                // Ensure camera and microphone are disabled since no permissions
                try {
                    activeCall.microphone.setEnabled(false)
                    activeCall.camera.setEnabled(false)
                    Log.d("StreamCallPlugin", "handleFinalPermissionDenial: Disabled camera/microphone for call ${activeCall.id}")
                } catch (e: Exception) {
                    Log.w("StreamCallPlugin", "handleFinalPermissionDenial: Error disabling camera/mic", e)
                }
                
                android.widget.Toast.makeText(
                    context,
                    "Call continues without camera/microphone",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
                // Resolve the pending call since the call itself was successful (just no permissions)
                pendingCall?.resolve(JSObject().apply {
                    put("success", true)
                    put("message", "Call accepted without camera/microphone permissions")
                })
                clearPendingCall()
            }
            
            hasIncomingCall -> {
                // Incoming call that wasn't accepted yet (old flow)
                Log.d("StreamCallPlugin", "handleFinalPermissionDenial: Rejecting incoming call acceptance")
                pendingCall?.reject("Permissions required for call. Please grant them.")
                clearPendingCall()
            }
            
            else -> {
                Log.d("StreamCallPlugin", "handleFinalPermissionDenial: No pending operations to handle")
                clearPendingCall()
            }
        }
    }

    private fun openAppSettings() {
        try {
            // Try to open app-specific permission settings directly (Android 11+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        ("package:" + activity.packageName).toUri())
                    intent.addCategory(Intent.CATEGORY_DEFAULT)
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.d("StreamCallPlugin", "Opened app details settings (Android 11+)")
                    
                    // Show toast with specific instructions
                    runOnMainThread {
                        android.widget.Toast.makeText(
                            context,
                            "Tap 'Permissions' → Enable Camera and Microphone",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                } catch (e: Exception) {
                    Log.w("StreamCallPlugin", "Failed to open app details, falling back", e)
                }
            }
            
            // Fallback for older Android versions or if the above fails
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("StreamCallPlugin", "Opened app settings via fallback")
            
            // Show more specific instructions for older versions
            runOnMainThread {
                android.widget.Toast.makeText(
                    context,
                    "Find 'Permissions' and enable Camera + Microphone",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Error opening app settings", e)
            
            // Final fallback - open general settings
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d("StreamCallPlugin", "Opened general settings as final fallback")
                
                runOnMainThread {
                    android.widget.Toast.makeText(
                        context,
                        "Go to Apps → ${context.applicationInfo.loadLabel(context.packageManager)} → Permissions",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (finalException: Exception) {
                Log.e("StreamCallPlugin", "All settings intents failed", finalException)
                runOnMainThread {
                    android.widget.Toast.makeText(
                        context,
                        "Please manually enable Camera and Microphone permissions",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }



    @OptIn(DelicateCoroutinesApi::class)
    @PluginMethod
    fun setMicrophoneEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: run {
            call.reject("Missing required parameter: enabled")
            return
        }

        try {
            val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall
            if (activeCall == null) {
                call.reject("No active call")
                return
            }

            kotlinx.coroutines.GlobalScope.launch {
                try {
                    activeCall.value?.microphone?.setEnabled(enabled)
                    call.resolve(JSObject().apply {
                        put("success", true)
                    })
                } catch (e: Exception) {
                    Log.e("StreamCallPlugin", "Error setting microphone: ${e.message}")
                    call.reject("Failed to set microphone: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Error setting microphone: ${e.message}")
            call.reject("StreamVideo not initialized")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @PluginMethod
    fun isCameraEnabled(call: PluginCall) {
        try {
            val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall
            if (activeCall == null) {
                call.reject("No active call")
                return
            }

            kotlinx.coroutines.GlobalScope.launch {
                try {
                    val enabled = activeCall.value?.camera?.isEnabled?.value
                    if (enabled == null) {
                        call.reject("Cannot figure out if camera is enabled or not")
                        return@launch
                    }
                    call.resolve(JSObject().apply {
                        put("enabled", enabled)
                    })
                } catch (e: Exception) {
                    Log.e("StreamCallPlugin", "Error checking the camera status: ${e.message}")
                    call.reject("Failed to check if camera is enabled: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Error checking camera status: ${e.message}")
            call.reject("StreamVideo not initialized")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @PluginMethod
    fun setCameraEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: run {
            call.reject("Missing required parameter: enabled")
            return
        }

        try {
            val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall
            if (activeCall == null) {
                call.reject("No active call")
                return
            }

            kotlinx.coroutines.GlobalScope.launch {
                try {
                    activeCall.value?.camera?.setEnabled(enabled)
                    call.resolve(JSObject().apply {
                        put("success", true)
                    })
                } catch (e: Exception) {
                    Log.e("StreamCallPlugin", "Error setting camera: ${e.message}")
                    call.reject("Failed to set camera: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Error setting camera: ${e.message}")
            call.reject("StreamVideo not initialized")
        }
    }

    @OptIn(InternalStreamVideoApi::class)
    private suspend fun endCallRaw(call: Call) {
        val callId = call.id
        Log.d("StreamCallPlugin", "Attempting to end call $callId")
        
        try {
            // Get call information to make the decision
            val callInfo = call.get()
            val callData = callInfo.getOrNull()?.call
            val currentUserId = StreamCallManager.streamVideoClient?.userId
            val createdBy = callData?.createdBy?.id
            val isCreator = createdBy == currentUserId
            
            // Use call.state.totalParticipants to get participant count (as per StreamVideo Android SDK docs)
            val totalParticipants = call.state.totalParticipants.value
            val shouldEndCall = isCreator || totalParticipants <= 1
            
            Log.d("StreamCallPlugin", "Call $callId - Creator: $createdBy, CurrentUser: $currentUserId, IsCreator: $isCreator, TotalParticipants: $totalParticipants, ShouldEnd: $shouldEndCall")
            
            if (shouldEndCall) {
                // End the call for everyone if I'm the creator or only 1 person
                Log.d("StreamCallPlugin", "Ending call $callId for all participants (creator: $isCreator, participants: $totalParticipants)")
                call.end()
            } else {
                // Just leave the call if there are more than 1 person and I'm not the creator
                Log.d("StreamCallPlugin", "Leaving call $callId (not creator, >1 participants)")
                call.leave()
            }

            // Here, we'll also mark the activity as not-visible on lock screen
            this@StreamCallPlugin.activity?.let {
                changeActivityAsVisibleOnLockScreen(it, false)
            }

        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Error getting call info for $callId, defaulting to leave()", e)
            // Fallback to leave if we can't determine the call info
            call.leave()
        }

        // Capture context from the overlayView
        val currentContext = overlayView?.context
        if (currentContext == null) {
            Log.w("StreamCallPlugin", "Cannot end call $callId because context is null")
            return
        }

        runOnMainThread {
            Log.d("StreamCallPlugin", "Setting overlay invisible after ending call")


            currentContext.let { ctx ->
                val keyguardManager = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (keyguardManager.isKeyguardLocked) {
                    // we allow kill exclusively here
                    // the idea is that:
                    // the 'empty' instance of this plugin class gets created in application
                    // then, it handles a notification and setts the context (this.savedContext)
                    // if the context is new
                    moveAllActivitiesToBackgroundOrKill(ctx)
                }
            }

            val savedCapacitorActivity = activity
            if (savedCapacitorActivity != null) {
                // This logic needs to be revisited. It's not clear if savedActivityPaused is available
                // or how to correctly check if the activity is paused.
                Log.d("StreamCallPlugin", "Moving activity to background")
            }

            setOverlayContent(call)
            overlayView?.isVisible = false
            bridge?.webView?.setBackgroundColor(Color.WHITE) // Restore webview opacity

            // Also hide incoming call view if visible
            Log.d("StreamCallPlugin", "Hiding incoming call view for call")
            // No dedicated incoming-call native view anymore; UI handled by web layer
        }

        // Notify that call has ended using helper
        notifyListeners("callEvent", JSObject().put("callId", callId).put("state", "left"))
    }

    private fun changeActivityAsVisibleOnLockScreen(activity: Activity, visible: Boolean) {
        if (visible) {
            // Ensure the activity is visible over the lock screen when launched via full-screen intent
            Log.d("StreamCallPlugin", "Mark the mainActivity as visible on the lockscreen")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            } else {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        } else {
            // Ensure the activity is NOT visible over the lock screen when launched via full-screen intent
            Log.d("StreamCallPlugin", "Clear the flag for the mainActivity for visible on the lockscreen")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(false)
                activity.setTurnScreenOn(false)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    @PluginMethod
    fun endCall(call: PluginCall) {
        try {
            val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
            val ringingCall = StreamCallManager.streamVideoClient?.state?.ringingCall?.value
            
            val callToEnd = activeCall ?: ringingCall
            
            if (callToEnd == null) {
                Log.w("StreamCallPlugin", "Attempted to end call but no active or ringing call found")
                call.reject("No active call to end")
                return
            }

            Log.d("StreamCallPlugin", "Ending call: activeCall=${activeCall?.id}, ringingCall=${ringingCall?.id}, callToEnd=${callToEnd.id}")

            kotlinx.coroutines.GlobalScope.launch {
                try {
                    endCallRaw(callToEnd)
                    call.resolve(JSObject().apply {
                        put("success", true)
                    })
                } catch (e: Exception) {
                    Log.e("StreamCallPlugin", "Error ending call: ${e.message}")
                    call.reject("Failed to end call: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Error ending call: ${e.message}")
            call.reject("StreamVideo not initialized")
        }
    }

    @OptIn(DelicateCoroutinesApi::class, InternalStreamVideoApi::class)
    @PluginMethod
    fun call(call: PluginCall) {
        val userIds = call.getArray("userIds")?.toList<String>()
        if (userIds.isNullOrEmpty()) {
            call.reject("Missing required parameter: userIds (array of user IDs)")
            return
        }

        val custom = call.getObject("custom")

        try {
            if (StreamCallManager.streamVideoClient == null) {
                call.reject("StreamVideo not initialized or not logged in.")
                return
            }

            val callType = call.getString("type") ?: "default"
            val shouldRing = call.getBoolean("ring") ?: true
            val team = call.getString("team")

            // Check permissions before creating the call
            if (!checkPermissions()) {
                Log.d("StreamCallPlugin", "Permissions not granted, storing call parameters and requesting permissions")
                // Store call parameters for later execution
                pendingCall = call
                pendingCallUserIds = userIds
                pendingCallType = callType
                pendingCallShouldRing = shouldRing
                pendingCallTeam = team
                custom?.let {
                    pendingCustomObject = it
                }
                // Reset attempt count for new permission flow
                permissionAttemptCount = 0
                requestPermissions()
                return // Don't reject immediately, wait for permission result
            }

            // Execute call creation immediately if permissions are granted
            createAndStartCall(call, userIds, callType, shouldRing, team, custom)
        } catch (e: Exception) {
            call.reject("Failed to make call: ${e.message}")
        }
    }

    @PluginMethod
    fun getCallStatus(call: PluginCall) {
        val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
        if (activeCall == null) {
            call.reject("Not in a call")
            return
        }

        val result = JSObject()
        result.put("callId", activeCall.cid)
        result.put("state", "active") // simplified status

        call.resolve(result)
    }

    @PluginMethod
    fun setSpeaker(call: PluginCall) {
        val name = call.getString("name") ?: "speaker"
        val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
        if (activeCall != null) {
            if (name == "speaker")
                activeCall.speaker.setSpeakerPhone(enable = true)
            else
                activeCall.speaker.setSpeakerPhone(enable = false)
            call.resolve(JSObject().apply {
                put("success", true)
            })
        } else {
            call.reject("No active call")
        }
    }

    @PluginMethod
    fun switchCamera(call: PluginCall) {
        val camera = call.getString("camera") ?: "front"
        val activeCall = StreamCallManager.streamVideoClient?.state?.activeCall?.value
        if (activeCall != null) {
            if (camera == "front")
                activeCall.camera.setDirection(CameraDirection.Front)
            else
                activeCall.camera.setDirection(CameraDirection.Back)
            call.resolve(JSObject().apply {
                put("success", true)
            })
        } else {
            call.reject("No active call")
        }
    }

    @PluginMethod
    fun setDynamicStreamVideoApikey(call: PluginCall) {
        val apiKey = call.getString("apiKey")
        if (apiKey == null) {
            call.reject("Missing required parameter: apiKey")
            return
        }

        try {
            ApiKeyManager.saveDynamicApiKey(apiKey)
            Log.d("StreamCallPlugin", "Dynamic API key saved successfully")
            call.resolve(JSObject().apply {
                put("success", true)
            })
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Error saving dynamic API key", e)
            call.reject("Failed to save API key: ${e.message}")
        }
    }

    @PluginMethod
    fun getDynamicStreamVideoApikey(call: PluginCall) {
        try {
            val apiKey = ApiKeyManager.getDynamicApiKey()
            call.resolve(JSObject().apply {
                if (apiKey != null) {
                    put("apiKey", apiKey)
                    put("hasDynamicKey", true)
                } else {
                    put("apiKey", null)
                    put("hasDynamicKey", false)
                }
            })
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "Error getting dynamic API key", e)
            call.reject("Failed to get API key: ${e.message}")
        }
    }

    @PluginMethod
    fun joinCall(call: PluginCall) {
        val fragment = callFragment
        if (fragment != null && fragment.getCall() != null) {
            if (!checkPermissions()) {
                requestPermissions()
                call.reject("Permissions required for call. Please grant them.")
                return
            }
            CoroutineScope(Dispatchers.Main).launch {
                fragment.getCall()?.join()
                call.resolve()
            }
        } else {
            call.reject("No active call or fragment not initialized")
        }
    }

    @PluginMethod
    fun leaveCall(call: PluginCall) {
        val fragment = callFragment
        if (fragment != null && fragment.getCall() != null) {
            CoroutineScope(Dispatchers.Main).launch {
                fragment.getCall()?.leave()
                call.resolve()
            }
        } else {
            call.reject("No active call or fragment not initialized")
        }
    }

    private val acceptCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "io.getstream.video.android.action.ACCEPT_CALL") {
                Log.d("StreamCallPlugin", "BroadcastReceiver: Received broadcast with action: ${intent.action}")
                val cid = intent.streamCallId(NotificationHandler.INTENT_EXTRA_CALL_CID)
                if (cid != null) {
                    Log.d("StreamCallPlugin", "BroadcastReceiver: ACCEPT_CALL broadcast received with cid: $cid")
                    val call = StreamCallManager.streamVideoClient?.call(id = cid.id, type = cid.type)
                    if (call != null) {
                        Log.d("StreamCallPlugin", "BroadcastReceiver: Accepting call with cid: $cid")
                        kotlinx.coroutines.GlobalScope.launch {
                            internalAcceptCall(call, requestPermissionsAfter = !checkPermissions())
                        }
                        bringAppToForeground()
                    } else {
                        Log.e("StreamCallPlugin", "BroadcastReceiver: Call object is null for cid: $cid")
                    }
                }
            }
        }
    }

    private fun bringAppToForeground() {
        try {
            val ctx = context
            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (launchIntent != null) {
                ctx.startActivity(launchIntent)
                Log.d("StreamCallPlugin", "bringAppToForeground: Launch intent executed to foreground app")
            } else {
                Log.w("StreamCallPlugin", "bringAppToForeground: launchIntent is null")
            }
        } catch (e: Exception) {
            Log.e("StreamCallPlugin", "bringAppToForeground error", e)
        }
    }

}
