package ee.forgr.capacitor.streamcall

import TouchInterceptWrapper
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.isVisible
import com.getcapacitor.BridgeActivity
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import io.getstream.android.push.firebase.FirebasePushDeviceGenerator
import io.getstream.android.push.permissions.ActivityLifecycleCallbacks
import io.getstream.video.android.core.Call
import io.getstream.video.android.core.GEO
import io.getstream.video.android.core.RingingState
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.StreamVideoBuilder
import io.getstream.video.android.core.model.RejectReason
import io.getstream.video.android.core.notifications.NotificationConfig
import io.getstream.video.android.core.notifications.NotificationHandler
import io.getstream.video.android.core.sounds.emptyRingingConfig
import io.getstream.video.android.core.sounds.toSounds
import io.getstream.video.android.model.StreamCallId
import io.getstream.video.android.model.User
import io.getstream.video.android.model.streamCallId
import kotlinx.coroutines.launch
import org.openapitools.client.models.CallEndedEvent
import org.openapitools.client.models.CallMissedEvent
import org.openapitools.client.models.CallRejectedEvent
import org.openapitools.client.models.CallSessionEndedEvent
import org.openapitools.client.models.VideoEvent

// I am not a religious pearson, but at this point, I am not sure even god himself would understand this code
// It's a spaghetti-like, tangled, unreadable mess and frankly, I am deeply sorry for the code crimes commited in the Android impl
@CapacitorPlugin(name = "StreamCall")
public class StreamCallPlugin : Plugin() {
    private var streamVideoClient: StreamVideo? = null
    private var state: State = State.NOT_INITIALIZED
    private var overlayView: ComposeView? = null
    private var incomingCallView: ComposeView? = null
    private var barrierView: View? = null
    private var ringtonePlayer: RingtonePlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var savedContext: Context? = null
    private var bootedToHandleCall: Boolean = false
    private var initializationTime: Long = 0
    private var savedActivity: Activity? = null

    private enum class State {
        NOT_INITIALIZED,
        INITIALIZING,
        INITIALIZED
    }

    private fun runOnMainThread(action: () -> Unit) {
        mainHandler.post { action() }
    }

    override fun handleOnPause() {
        this.ringtonePlayer.let { it?.pauseRinging() }
        super.handleOnPause()
    }

    override fun handleOnResume() {
        this.ringtonePlayer.let { it?.resumeRinging() }
        super.handleOnResume()
    }

    override fun load() {
        // general init
        ringtonePlayer = RingtonePlayer(
            this.activity.application,
            cancelIncomingCallService = {
                var streamVideoClient = this.streamVideoClient
                if (streamVideoClient == null) {
                    android.util.Log.d("StreamCallPlugin", "StreamVideo SDK client is null, no incoming call notification can be constructed")
                    return@RingtonePlayer
                }

                try {
                    val callServiceClass = Class.forName("io.getstream.video.android.core.notifications.internal.service.CallService")
                    val companionClass = callServiceClass.declaredClasses.first { it.simpleName == "Companion" }
                    // Instead of getting INSTANCE, we'll get the companion object through the enclosing class
                    val companionField = callServiceClass.getDeclaredField("Companion")
                    companionField.isAccessible = true
                    val companionInstance = companionField.get(null)
                    
                    val removeIncomingCallMethod = companionClass.getDeclaredMethod(
                        "removeIncomingCall",
                        Context::class.java,
                        Class.forName("io.getstream.video.android.model.StreamCallId"),
                        Class.forName("io.getstream.video.android.core.notifications.internal.service.CallServiceConfig")
                    )
                    removeIncomingCallMethod.isAccessible = true

                    // Get the default config using reflection
                    val defaultConfigClass = Class.forName("io.getstream.video.android.core.notifications.internal.service.DefaultCallConfigurations")
                    val defaultField = defaultConfigClass.getDeclaredField("INSTANCE")
                    val defaultInstance = defaultField.get(null)
                    val defaultMethod = defaultConfigClass.getDeclaredMethod("getDefault")
                    val defaultConfig = defaultMethod.invoke(defaultInstance)

                    val app = this.activity.application
                    val cId = streamVideoClient?.state?.ringingCall?.value?.cid?.let { StreamCallId.fromCallCid(it) }
                    if (app == null || cId == null || defaultConfig == null) {
                        android.util.Log.e("StreamCallPlugin", "Some required parameters are null - app: ${app == null}, cId: ${cId == null}, defaultConfig: ${defaultConfig == null}")
                    }

                    // Call the method
                    removeIncomingCallMethod.invoke(companionInstance, app, cId, defaultConfig)
                } catch (e : Throwable) {
                    android.util.Log.e("StreamCallPlugin", "Reflecting streamNotificationManager and the config DID NOT work", e);
                }
            }
        )
        initializeStreamVideo()
        setupViews()
        super.load()

        // Handle initial intent if present
        activity?.intent?.let { handleOnNewIntent(it) }
    }

    override fun handleOnNewIntent(intent: android.content.Intent) {
        super.handleOnNewIntent(intent)
        
        val action = intent.action
        val data = intent.data
        val extras = intent.extras

        if (action === "io.getstream.video.android.action.INCOMING_CALL") {
            activity?.runOnUiThread {
                val cid = intent.streamCallId(NotificationHandler.INTENT_EXTRA_CALL_CID)
                if (cid != null) {
                    val call = streamVideoClient?.call(id = cid.id, type = cid.type)
                    // Start playing ringtone
                    ringtonePlayer?.startRinging()
                    // let's set a barrier. This will prevent the user from interacting with the webview while the calling screen is loading
                    // Launch a coroutine to handle the suspend function
                    showBarrier()

                    kotlinx.coroutines.GlobalScope.launch {
                        call?.get()
                        activity?.runOnUiThread {
                            incomingCallView?.setContent {
                                IncomingCallView(
                                    streamVideo = streamVideoClient,
                                    call = call,
                                    onDeclineCall = { declinedCall ->
                                        declineCall(declinedCall)
                                    },
                                    onAcceptCall = { acceptedCall ->
                                        acceptCall(acceptedCall)
                                    },
                                    onHideIncomingCall = {
                                        hideIncomingCall()
                                    }
                                )
                            }
                            incomingCallView?.isVisible = true
                            hideBarrier()
                        }
                    }
                }
            }
        } else if (action === "io.getstream.video.android.action.ACCEPT_CALL") {
            // it's a strategic placed initializeStreamVideo. I want to register the even listeners
            // (which are not initialized during the first load in initialization by the application class)
            // initializeStreamVideo()
            val cid = intent.streamCallId(NotificationHandler.INTENT_EXTRA_CALL_CID)
            if (cid != null) {
                val call = streamVideoClient?.call(id = cid.id, type = cid.type)
                kotlinx.coroutines.GlobalScope.launch {
                    call?.get()
                    call?.let { acceptCall(it) }
                }
            }
        }
        // Log the intent information
        android.util.Log.d("StreamCallPlugin", "New Intent - Action: $action")
        android.util.Log.d("StreamCallPlugin", "New Intent - Data: $data")
        android.util.Log.d("StreamCallPlugin", "New Intent - Extras: $extras")
    }

    private fun declineCall(call: Call) {
        kotlinx.coroutines.GlobalScope.launch {
            call.reject(RejectReason.Decline)
            
            // Stop ringtone
            ringtonePlayer?.stopRinging()
            
            // Notify that call has ended
            val data = JSObject().apply {
                put("callId", call.id)
                put("state", "rejected")
            }
            notifyListeners("callEvent", data)
            
            hideIncomingCall()
        }
    }

    private fun hideIncomingCall() {
        activity?.runOnUiThread {
            incomingCallView?.isVisible = false
            // Stop ringtone if it's still playing
            ringtonePlayer?.stopRinging()
            // Check if device is locked using KeyguardManager
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                activity.moveTaskToBack(true)
            }
        }
    }

    private fun showBarrier() {
        activity?.runOnUiThread {
            barrierView?.isVisible = true
        }
    }

    private fun hideBarrier() {
        activity?.runOnUiThread {
            barrierView?.isVisible = false
        }
    }

//    private fun remoteIncomingCallNotif() {
//        CallService.removeIncomingCall(
//            context,
//            StreamCallId.fromCallCid(call.cid),
//            StreamVideo.instance().state.callConfigRegistry.get(call.type),
//        )
//    }

    private fun setupViews() {
        val context = context
        val parent = bridge?.webView?.parent as? ViewGroup ?: return

        // Make WebView transparent
        bridge?.webView?.setBackgroundColor(Color.TRANSPARENT)

        // Create and add overlay view below WebView
        overlayView = ComposeView(context).apply {
            isVisible = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                CallOverlayView(
                    context = context,
                    streamVideo = streamVideoClient,
                    call = null
                )
            }
        }
        parent.addView(overlayView, 0)  // Add at index 0 to ensure it's below WebView

        val originalContainer: ViewGroup = getBridge().webView

        val wrapper = TouchInterceptWrapper(parent)
        (parent.parent as ViewGroup).removeView(originalContainer)
        (parent.parent as ViewGroup).addView(wrapper, 0)

        // Create barrier view
        barrierView = View(context).apply {
            isVisible = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1a242c"))
        }
        parent.addView(barrierView, parent.indexOfChild(bridge?.webView) + 1) // Add above WebView

        // Create and add incoming call view
        incomingCallView = ComposeView(context).apply {
            isVisible = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                IncomingCallView(streamVideoClient)
            }
        }
        parent.addView(incomingCallView, parent.indexOfChild(bridge?.webView) + 2)  // Add above WebView
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

            // Initialize Stream Video with new credentials
            if (!hadSavedCredentials) {
                initializeStreamVideo()
            }

            val ret = JSObject()
            ret.put("success", true)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to login", e)
        }
    }

    @PluginMethod
    fun logout(call: PluginCall) {
        try {
            // Clear stored credentials
            SecureUserRepository.getInstance(context).removeCurrentUser()
            
            // Properly cleanup the client
            streamVideoClient?.let {
                StreamVideo.removeClient()
            }
            streamVideoClient = null
            state = State.NOT_INITIALIZED

            val ret = JSObject()
            ret.put("success", true)
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to logout", e)
        }
    }

    public fun initializeStreamVideo(passedContext: Context? = null, passedApplication: Application? = null) {
        android.util.Log.v("StreamCallPlugin", "Attempting to initialize streamVideo")
        if (state == State.INITIALIZING) {
            android.util.Log.v("StreamCallPlugin", "Returning, already in the process of initializing")
            return
        }
        state = State.INITIALIZING

        if (passedContext != null) {
            this.savedContext = passedContext
        }
        val contextToUse = passedContext ?: this.context

        // Try to get user credentials from repository
        val savedCredentials = SecureUserRepository.getInstance(contextToUse).loadCurrentUser()
        if (savedCredentials == null) {
            android.util.Log.v("StreamCallPlugin", "Saved credentials are null")
            state = State.NOT_INITIALIZED
            return
        }

        try {
            // Check if we can reuse existing StreamVideo singleton client
            if (StreamVideo.isInstalled) {
                android.util.Log.v("StreamCallPlugin", "Found existing StreamVideo singleton client")
                if (streamVideoClient == null) {
                    android.util.Log.v("StreamCallPlugin", "Plugin's streamVideoClient is null, reusing singleton and registering event handlers")
                    streamVideoClient = StreamVideo.instance()
                    // Register event handlers since streamVideoClient was null
                    registerEventHandlers()
                } else {
                    android.util.Log.v("StreamCallPlugin", "Plugin already has streamVideoClient, skipping event handler registration")
                }
                state = State.INITIALIZED
                initializationTime = System.currentTimeMillis()
                return
            }

            // If we reach here, we need to create a new client
            android.util.Log.v("StreamCallPlugin", "No existing StreamVideo singleton client, creating new one")

            // unsafe cast, add better handling
            val application = contextToUse.applicationContext as Application
            android.util.Log.d("StreamCallPlugin", "No existing StreamVideo singleton client, creating new one")
            val notificationHandler = CustomNotificationHandler(
                application = application,
                endCall = { callId ->
                    val activeCall = streamVideoClient?.call(callId.type, callId.id)

                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            android.util.Log.i(
                                "StreamCallPlugin",
                                "Attempt to endCallRaw, activeCall == null: ${activeCall == null}",
                            )
                            activeCall?.let { endCallRaw(it) }
                        } catch (e: Exception) {
                            android.util.Log.e(
                                "StreamCallPlugin",
                                "Error ending after missed call notif action",
                                e
                            )
                        }
                    }
                },
                incomingCall = {
                    if (this.savedContext != null && initializationTime != 0L) {
                        val contextCreatedAt = initializationTime
                        val now = System.currentTimeMillis()
                        val isWithinOneSecond = (now - contextCreatedAt) <= 1000L

                        android.util.Log.i(
                            "StreamCallPlugin",
                            "Time between context creation and activity created (incoming call notif): ${now - contextCreatedAt}"
                        )
                        if (isWithinOneSecond && !bootedToHandleCall) {
                            android.util.Log.i(
                                "StreamCallPlugin",
                                "Notification incomingCall received less than 1 second after the creation of streamVideoSDK. Booted FOR SURE in order to handle the notification"
                            )
                        }
                    }
                }
            )

            val notificationConfig = NotificationConfig(
                pushDeviceGenerators = listOf(FirebasePushDeviceGenerator(
                    providerName = "firebase",
                    context = contextToUse
                )),
                requestPermissionOnAppLaunch = { true },
                notificationHandler = notificationHandler,
            )

            val soundsConfig = emptyRingingConfig()
            soundsConfig.incomingCallSoundUri
            // Initialize StreamVideo client
            streamVideoClient = StreamVideoBuilder(
                context = contextToUse,
                apiKey = contextToUse.getString(R.string.CAPACITOR_STREAM_VIDEO_APIKEY),
                geo = GEO.GlobalEdgeNetwork,
                user = savedCredentials.user,
                token = savedCredentials.tokenValue,
                notificationConfig = notificationConfig,
                sounds = soundsConfig.toSounds()
                //, loggingLevel = LoggingLevel(priority = Priority.VERBOSE)
            ).build()

            // don't do event handler registration when activity may be null
            if (passedContext != null) {
                android.util.Log.w("StreamCallPlugin", "Ignoring event listeners for initializeStreamVideo")
                passedApplication?.let {
                    registerActivityEventListener(it)
                }
                initializationTime = System.currentTimeMillis()
                this.state = State.INITIALIZED
                return
            }

            registerEventHandlers()
            
            android.util.Log.v("StreamCallPlugin", "Initialization finished")
            initializationTime = System.currentTimeMillis()
            state = State.INITIALIZED
        } catch (e: Exception) {
            state = State.NOT_INITIALIZED
            throw e
        }
    }

    private fun moveAllActivitiesToBackgroundOrKill(context: Context, allowKill: Boolean = false) {
        try {
            if (allowKill && bootedToHandleCall && savedActivity != null) {
                android.util.Log.d("StreamCallPlugin", "App was booted to handle call and allowKill is true, killing app")
                savedActivity?.let { act ->
                    try {
                        // Get the ActivityManager
                        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        // Remove the task
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val tasks = activityManager.appTasks
                            tasks.forEach { task ->
                                task.finishAndRemoveTask()
                            }
                        }
                        // Finish the activity
                        act.finish()
                        // Remove from recents
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            act.finishAndRemoveTask()
                        }
                        // Give a small delay for cleanup
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Kill the process
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }, 100)
                    } catch (e: Exception) {
                        android.util.Log.e("StreamCallPlugin", "Error during aggressive cleanup", e)
                        // Fallback to direct process kill
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
                return
            }

            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            android.util.Log.d("StreamCallPlugin", "Moving app to background using HOME intent")
        } catch (e: Exception) {
            android.util.Log.e("StreamCallPlugin", "Failed to move app to background", e)
        }
    }

    private fun registerEventHandlers() {
        // Subscribe to call events
        streamVideoClient?.let { client ->
            client.subscribe { event: VideoEvent ->
                android.util.Log.v("StreamCallPlugin", "Received an event ${event.getEventType()} $event")
                when (event) {
                    is CallEndedEvent -> {
                        runOnMainThread {
                            android.util.Log.d("StreamCallPlugin", "Setting overlay invisible due to CallEndedEvent for call ${event.call.cid}")
                            overlayView?.setContent {
                                CallOverlayView(
                                    context = context,
                                    streamVideo = streamVideoClient,
                                    call = null
                                )
                            }
                            overlayView?.isVisible = false
                        }
                        val data = JSObject().apply {
                            put("callId", event.call.cid)
                            put("state", "left")
                        }
                        notifyListeners("callEvent", data)
                    }
                    is CallSessionEndedEvent -> {
                        runOnMainThread {
                            android.util.Log.d("StreamCallPlugin", "Setting overlay invisible due to CallSessionEndedEvent for call ${event.call.cid}")
                            overlayView?.setContent {
                                CallOverlayView(
                                    context = context,
                                    streamVideo = streamVideoClient,
                                    call = null
                                )
                            }
                            overlayView?.isVisible = false
                        }
                        val data = JSObject().apply {
                            put("callId", event.call.cid)
                            put("state", "left")
                        }
                        notifyListeners("callEvent", data)
                    }
                    is CallRejectedEvent -> {
                        runOnMainThread {
                            android.util.Log.d("StreamCallPlugin", "Setting overlay invisible due to CallRejectedEvent for call ${event.call.cid}")
                            overlayView?.setContent {
                                CallOverlayView(
                                    context = context,
                                    streamVideo = streamVideoClient,
                                    call = null
                                )
                            }
                            overlayView?.isVisible = false

                            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                            if (keyguardManager.isKeyguardLocked) {
                                android.util.Log.d("StreamCallPlugin", "Stop ringing and move to background")
                                this@StreamCallPlugin.ringtonePlayer?.stopRinging()
                                moveAllActivitiesToBackgroundOrKill(context)
                            }
                        }
                        val data = JSObject().apply {
                            put("callId", event.call.cid)
                            put("state", "rejected")
                        }
                        notifyListeners("callEvent", data)
                    }
                    is CallMissedEvent -> {
                        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        if (keyguardManager.isKeyguardLocked) {
                            android.util.Log.d("StreamCallPlugin", "Stop ringing and move to background")
                            this.ringtonePlayer?.stopRinging()
                            moveAllActivitiesToBackgroundOrKill(context)
                        }
                    }
                }
                val data = JSObject().apply {
                    put("callId", streamVideoClient?.state?.activeCall?.value?.cid)
                    put("state", event.getEventType())
                }
                notifyListeners("callEvent", data)
            }

            // Add call state subscription using collect
            // used so that it follows the same patterns as iOS
            kotlinx.coroutines.GlobalScope.launch {
                client.state.activeCall.collect { call ->
                    android.util.Log.d("StreamCallPlugin", "Call State Update:")
                    android.util.Log.d("StreamCallPlugin", "- Call is null: ${call == null}")

                    call?.state?.let { state ->
                        android.util.Log.d("StreamCallPlugin", "- Session ID: ${state.session.value?.id}")
                        android.util.Log.d("StreamCallPlugin", "- All participants: ${state.participants}")
                        android.util.Log.d("StreamCallPlugin", "- Remote participants: ${state.remoteParticipants}")

                        // Notify that a call has started
                        val data = JSObject().apply {
                            put("callId", call.cid)
                            put("state", "joined")
                        }
                        notifyListeners("callEvent", data)
                    } ?: run {
                        // Notify that call has ended
                        val data = JSObject().apply {
                            put("callId", "")
                            put("state", "left")
                        }
                        notifyListeners("callEvent", data)
                    }
                }
            }
        }
    }

    private fun registerActivityEventListener(application: Application) {
        android.util.Log.i("StreamCallPlugin", "Registering activity event listener")
        application.registerActivityLifecycleCallbacks(object: ActivityLifecycleCallbacks() {
            override fun onActivityCreated(activity: Activity, bunlde: Bundle?) {
                android.util.Log.d("StreamCallPlugin", "onActivityCreated called")
                savedContext?.let {
                    if (this@StreamCallPlugin.savedActivity != null) {
                        android.util.Log.d("StreamCallPlugin", "Activity created before, but got re-created. saving and returning")
                        this@StreamCallPlugin.savedActivity = activity;
                        return
                    }
                    if (initializationTime == 0L) {
                        android.util.Log.w("StreamCallPlugin", "initializationTime is zero. Not continuing with onActivityCreated")
                        return
                    }

                    val keyguardManager = application.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    val isLocked = keyguardManager.isKeyguardLocked

                    if (isLocked) {
                        this@StreamCallPlugin.bootedToHandleCall = true;
                        android.util.Log.d("StreamCallPlugin", "Detected that the app booted an activity while locked. We will kill after the call fails")
                    }

                    if (this@StreamCallPlugin.bridge == null && activity is BridgeActivity) {
                        this@StreamCallPlugin.savedActivity = activity
                    }
                }
                super.onActivityCreated(activity, bunlde)
            }
        })
    }

    private fun acceptCall(call: Call) {
        kotlinx.coroutines.GlobalScope.launch {
            android.util.Log.i("StreamCallPlugin", "Attempting to accept call ${call.id}")
            try {
                // Stop ringtone
                ringtonePlayer?.stopRinging()
                
                // Hide incoming call view first
                runOnMainThread {
                    android.util.Log.d("StreamCallPlugin", "Hiding incoming call view for call ${call.id}")
                    incomingCallView?.isVisible = false
                }

                // Accept the call
                call.accept()

                // Notify that call has started
                val data = JSObject().apply {
                    put("callId", call.id)
                    put("state", "joined")
                }
                notifyListeners("callEvent", data)

                // Show overlay view with the active call
                runOnMainThread {
                    android.util.Log.d("StreamCallPlugin", "Setting overlay visible after accepting call ${call.id}")
                    overlayView?.setContent {
                        CallOverlayView(
                            context = context,
                            streamVideo = streamVideoClient,
                            call = call
                        )
                    }
                    overlayView?.isVisible = true
                }
            } catch (e: Exception) {
                android.util.Log.e("StreamCallPlugin", "Error accepting call ${call.id}: ${e.message}")
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

    @PluginMethod
    fun setMicrophoneEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: run {
            call.reject("Missing required parameter: enabled")
            return
        }

        try {
            val activeCall = streamVideoClient?.state?.activeCall
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
                    android.util.Log.e("StreamCallPlugin", "Error setting microphone: ${e.message}")
                    call.reject("Failed to set microphone: ${e.message}")
                }
            }
        } catch (e: Exception) {
            call.reject("StreamVideo not initialized")
        }
    }

    @PluginMethod
    fun setCameraEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: run {
            call.reject("Missing required parameter: enabled")
            return
        }

        try {
            val activeCall = streamVideoClient?.state?.activeCall
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
                    android.util.Log.e("StreamCallPlugin", "Error setting camera: ${e.message}")
                    call.reject("Failed to set camera: ${e.message}")
                }
            }
        } catch (e: Exception) {
            call.reject("StreamVideo not initialized")
        }
    }

    suspend fun endCallRaw(call: Call) {
        val callId = call.id
        android.util.Log.d("StreamCallPlugin", "Attempting to end call $callId")
        call.leave()
        call.reject(reason = RejectReason.Cancel)
        
        // Capture context from the overlayView
        val currentContext = overlayView?.context ?: this.savedContext
        if (currentContext == null) {
            android.util.Log.w("StreamCallPlugin", "Cannot end call $callId because context is null")
            return
        }
        
        runOnMainThread {
            android.util.Log.d("StreamCallPlugin", "Setting overlay invisible after ending call $callId")


            currentContext.let { ctx ->
                val keyguardManager = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (keyguardManager.isKeyguardLocked) {
                    // we allow kill exclusively here
                    // the idea is that:
                    // the 'empty' instance of this plugin class gets created in application
                    // then, it handles a notification and setts the context (this.savedContext)
                    // if the context is new
                    moveAllActivitiesToBackgroundOrKill(ctx, true)
                }
            }

            var savedCapacitorActivity = savedActivity
            if (savedCapacitorActivity != null) {
                android.util.Log.d("StreamCallPlugin", "Performing a trans-instance call to end call with id $callId")
                if (savedCapacitorActivity !is BridgeActivity) {
                    android.util.Log.e("StreamCallPlugin", "Saved activity is NOT a Capactor activity")
                    return@runOnMainThread
                }
                val plugin = savedCapacitorActivity.bridge.getPlugin("StreamCall")
                if (plugin == null) {
                    android.util.Log.e("StreamCallPlugin", "Plugin with name StreamCall not found?????")
                    return@runOnMainThread
                }
                if (plugin.instance !is StreamCallPlugin) {
                    android.util.Log.e("StreamCallPlugin", "Plugin found, but invalid instance")
                    return@runOnMainThread
                }
                kotlinx.coroutines.GlobalScope.launch {
                    try {
                        (plugin.instance as StreamCallPlugin).endCallRaw(call)
                    } catch (e: Exception) {
                        android.util.Log.e("StreamCallPlugin", "Error ending call on remote instance", e)
                    }
                }

                return@runOnMainThread
            }

            overlayView?.setContent {
                CallOverlayView(
                    context = currentContext,
                    streamVideo = streamVideoClient,
                    call = null
                )
            }
            overlayView?.isVisible = false
            this@StreamCallPlugin.ringtonePlayer?.stopRinging()

            // Also hide incoming call view if visible
            android.util.Log.d("StreamCallPlugin", "Hiding incoming call view for call $callId")
            incomingCallView?.isVisible = false
        }
        
        // Notify that call has ended
        val data = JSObject().apply {
            put("callId", callId)
            put("state", "left")
        }
        notifyListeners("callEvent", data)
    }

    @PluginMethod
    fun endCall(call: PluginCall) {
        try {
            val activeCall = streamVideoClient?.state?.activeCall
            if (activeCall == null) {
                android.util.Log.w("StreamCallPlugin", "Attempted to end call but no active call found")
                call.reject("No active call to end")
                return
            }

            kotlinx.coroutines.GlobalScope.launch {
                try {
                    activeCall.value?.let { endCallRaw(it) }
                    call.resolve(JSObject().apply {
                        put("success", true)
                    })
                } catch (e: Exception) {
                    android.util.Log.e("StreamCallPlugin", "Error ending call: ${e.message}")
                    call.reject("Failed to end call: ${e.message}")
                }
            }
        } catch (e: Exception) {
            call.reject("StreamVideo not initialized")
        }
    }

    @PluginMethod
    fun call(call: PluginCall) {
        val userId = call.getString("userId")
        if (userId == null) {
            call.reject("Missing required parameter: userId")
            return
        }

        try {
            if (state != State.INITIALIZED) {
                call.reject("StreamVideo not initialized")
                return
            }

            val selfUserId = streamVideoClient?.userId
            if (selfUserId == null) {
                call.reject("No self-user id found. Are you not logged in?")
                return
            }

            val callType = call.getString("type") ?: "default"
            val shouldRing = call.getBoolean("ring") ?: true
            val callId = java.util.UUID.randomUUID().toString()

            android.util.Log.d("StreamCallPlugin", "Creating call:")
            android.util.Log.d("StreamCallPlugin", "- Call ID: $callId")
            android.util.Log.d("StreamCallPlugin", "- Call Type: $callType")
            android.util.Log.d("StreamCallPlugin", "- User ID: $userId")
            android.util.Log.d("StreamCallPlugin", "- Should Ring: $shouldRing")

            // Create and join call in a coroutine
            kotlinx.coroutines.GlobalScope.launch {
                try {
                    // Create the call object
                    val streamCall = streamVideoClient?.call(type = callType, id = callId)

                    android.util.Log.d("StreamCallPlugin", "Creating call with member...")
                    // Create the call with the member
                    streamCall?.create(
                        memberIds = listOf(userId, selfUserId),
                        custom = emptyMap(),
                        ring = shouldRing
                    )

//                    streamCall?.let {
//                        this@StreamCallPlugin.streamVideoClient?.state?.setActiveCall(it)
//                    }

                    android.util.Log.d("StreamCallPlugin", "Setting overlay visible for outgoing call $callId")
                    // Show overlay view
                    activity?.runOnUiThread {
                        overlayView?.setContent {
                            CallOverlayView(
                                context = context,
                                streamVideo = streamVideoClient,
                                call = streamCall
                            )
                        }
                        overlayView?.isVisible = true
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
