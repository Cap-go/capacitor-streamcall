package ee.forgr.capacitor.streamcall

import android.app.Application
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import io.getstream.android.video.generated.models.CallAcceptedEvent
import io.getstream.android.video.generated.models.CallCreatedEvent
import io.getstream.android.video.generated.models.CallEndedEvent
import io.getstream.android.video.generated.models.CallMissedEvent
import io.getstream.android.video.generated.models.CallRejectedEvent
import io.getstream.android.video.generated.models.CallRingEvent
import io.getstream.android.video.generated.models.CallSessionEndedEvent
import io.getstream.android.video.generated.models.CallSessionParticipantLeftEvent
import io.getstream.android.video.generated.models.CallSessionStartedEvent
import io.getstream.android.video.generated.models.VideoEvent
import io.getstream.video.android.core.Call
import io.getstream.video.android.core.EventSubscription
import io.getstream.video.android.core.GEO
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.StreamVideoBuilder
import io.getstream.video.android.core.events.ParticipantLeftEvent
import io.getstream.video.android.core.internal.InternalStreamVideoApi
import io.getstream.video.android.core.notifications.handlers.StreamNotificationBuilderInterceptors
import io.getstream.video.android.core.notifications.NotificationConfig
import io.getstream.video.android.core.sounds.RingingConfig
import io.getstream.video.android.core.sounds.toSounds
import io.getstream.video.android.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.getcapacitor.JSObject
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import io.getstream.video.android.core.notifications.handlers.CompatibilityStreamNotificationHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import java.io.File

object StreamCallManager {
    private var state: State = State.NOT_INITIALIZED
    var isFreshInstall = false
        private set
    private lateinit var application: Application
    var streamVideoClient: StreamVideo? = null
        private set

    private var eventSubscription: EventSubscription? = null
    private var activeCallStateJob: Job? = null
    private val managerScope = CoroutineScope(Dispatchers.IO)

    private val _callState = MutableStateFlow<CallStateUpdate?>(null)
    val callState: StateFlow<CallStateUpdate?> = _callState.asStateFlow()

    private val callTimeoutStates: MutableMap<String, CallTimeoutState> = mutableMapOf()

    private enum class State {
        NOT_INITIALIZED,
        INITIALIZING,
        INITIALIZED
    }

    fun initialize(app: Application) {
        val noBackupDir = app.noBackupFilesDir
        val flagFile = File(noBackupDir, ".installed_flag")

        if (!flagFile.exists()) {
            isFreshInstall = true
            Log.d("StreamCallManager", "Fresh install detected, clearing any stored user credentials.")
            SecureUserRepository.getInstance(app.applicationContext).removeCurrentUser()
            try {
                flagFile.createNewFile()
                Log.d("StreamCallManager", "Install flag created in no_backup directory.")
            } catch (e: Exception) {
                Log.e("StreamCallManager", "Error creating install flag file.", e)
            }
        } else {
            isFreshInstall = false
        }

        if (state != State.NOT_INITIALIZED) {
            Log.d("StreamCallManager", "Already initialized.")
            return
        }
        this.application = app
        Log.d("StreamCallManager", "Initializing StreamCallManager.")

        // Try to auto-login with saved credentials. This must be synchronous.
        val credentials = SecureUserRepository.getInstance(app.applicationContext).loadCurrentUser()
        if (credentials != null) {
            Log.d("StreamCallManager", "Found saved credentials for ${credentials.user.id}. Creating client synchronously.")
            try {
                val apiKey = ApiKeyManager.getEffectiveApiKey(app)
                createStreamVideoClient(credentials.user, credentials.tokenValue, apiKey)
                Log.d("StreamCallManager", "StreamVideo client created synchronously for user ${credentials.user.id}.")
            } catch (e: Exception) {
                Log.e("StreamCallManager", "Failed to auto-initialize StreamVideo client", e)
                state = State.NOT_INITIALIZED
            }
        } else {
            Log.d("StreamCallManager", "No saved credentials found. Ready for manual login.")
            state = State.INITIALIZED
        }
    }

    fun login(user: User, token: String, apiKey: String) {
        managerScope.launch {
            if (state == State.INITIALIZING) {
                Log.w("StreamCallManager", "Login already in progress.")
                _callState.value = CallStateUpdate("loginError", JSObject().put("error", "Login already in progress"))
                return@launch
            }

            if (streamVideoClient != null && streamVideoClient?.user?.id == user.id) {
                Log.d("StreamCallManager", "Already logged in as ${user.id}.")
                _callState.value = CallStateUpdate("loginSuccess", JSObject().put("success", true))
                return@launch
            }

            state = State.INITIALIZING

            if (streamVideoClient != null) {
                Log.d("StreamCallManager", "Switching user. Disconnecting previous client.")
                cleanupStreamVideoClient()
            }

            try {
                createStreamVideoClient(user, token, apiKey)
                _callState.value = CallStateUpdate("loginSuccess", JSObject().put("success", true))
            } catch (e: Exception) {
                Log.e("StreamCallManager", "Failed to build StreamVideo client", e)
                state = State.NOT_INITIALIZED
                _callState.value = CallStateUpdate("loginError", JSObject().put("error", e.message ?: "Failed to login"))
            }
        }
    }

    private fun createStreamVideoClient(user: User, token: String, apiKey: String) {
        val contextToUse = application.applicationContext
        val soundsConfig = object : RingingConfig {
            override val incomingCallSoundUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            override val outgoingCallSoundUri: Uri? = null
        }
        streamVideoClient = StreamVideoBuilder(
            context = contextToUse,
            apiKey = apiKey,
            geo = GEO.GlobalEdgeNetwork,
            user = user,
            token = token,
            sounds = soundsConfig.toSounds(),
            notificationConfig = NotificationConfig(
                requestPermissionOnAppLaunch = { true }, // Request notification permission on app launch
                notificationHandler = CompatibilityStreamNotificationHandler(
                    application = contextToUse as Application,
                    intentResolver = CustomStreamIntentResolver(contextToUse as Application),
                    initialNotificationBuilderInterceptor = object : StreamNotificationBuilderInterceptors() {
                        override fun onBuildIncomingCallNotification(
                            builder: NotificationCompat.Builder,
                            fullScreenPendingIntent: PendingIntent,
                            acceptCallPendingIntent: PendingIntent,
                            rejectCallPendingIntent: PendingIntent,
                            callerName: String?,
                            shouldHaveContentIntent: Boolean
                        ): NotificationCompat.Builder {
                            return builder.setContentIntent(fullScreenPendingIntent)
                                .setFullScreenIntent(fullScreenPendingIntent, true)
                        }
                    }
                )
            ),
        ).build()
        registerEventHandlers()
        state = State.INITIALIZED
    }

    private fun cleanupStreamVideoClient() {
        eventSubscription?.dispose()
        activeCallStateJob?.cancel()
        streamVideoClient?.logOut()
        StreamVideo.removeClient()
        streamVideoClient = null
    }

    fun call(callType: String, callId: String, userIds: List<String>, shouldRing: Boolean, team: String?, custom: Map<String, Any>?, video: Boolean) {
        val selfUserId = streamVideoClient?.user?.id
        if (selfUserId == null) {
            _callState.value = CallStateUpdate("error", JSObject().put("error", "Not logged in"))
            return
        }

        managerScope.launch {
            try {
                val streamCall = streamVideoClient?.call(type = callType, id = callId)
                val createResult = streamCall?.create(
                    memberIds = userIds + selfUserId,
                    custom = custom ?: emptyMap(),
                    ring = shouldRing,
                    team = team,
                    video = video,
                )

                if (createResult?.isFailure == true) {
                    throw (createResult.errorOrNull() as Throwable? ?: RuntimeException("Unknown error creating call"))
                }
                
                // The `created` event will be sent by the event handler.
                // We can also send a pre-emptive update to the UI here if needed.
                _callState.value = CallStateUpdate("callCreationSuccess", JSObject().put("callId", callId))

            } catch (e: Exception) {
                Log.e("StreamCallManager", "Error making call: ${e.message}")
                 _callState.value = CallStateUpdate("error", JSObject().put("error", "Failed to make call: ${e.message}"))
            }
        }
    }

    fun logout() {
        managerScope.launch {
            if (streamVideoClient != null) {
                Log.d("StreamCallManager", "Logging out user.")
                cleanupStreamVideoClient()
                state = State.NOT_INITIALIZED
                _callState.value = CallStateUpdate("logoutSuccess", JSObject())
            } else {
                _callState.value = CallStateUpdate("logoutSuccess", JSObject())
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun registerEventHandlers() {
        Log.d("StreamCallManager", "registerEventHandlers called")
        eventSubscription?.dispose()
        activeCallStateJob?.cancel()

        streamVideoClient?.let { client ->
            eventSubscription = client.subscribe { event: VideoEvent ->
                 Log.v("StreamCallManager", "Received an event ${event.getEventType()} $event")
                // A simple way to forward events. This could be made more specific.
                val eventData = JSObject()
                eventData.put("type", event.getEventType())
                when(event) {
                    is CallRingEvent -> {
                         _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "ringing").put("callId", event.callCid))
                    }
                     is CallCreatedEvent -> {
                        val callParticipants = event.members.filter { it.user.id != streamVideoClient?.user?.id }.map { it.user.id }
                        startCallTimeoutMonitor(event.callCid, callParticipants)
                        _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "created").put("callId", event.callCid))
                    }
                    is CallSessionStartedEvent -> {
                        stopCallTimeoutMonitor(event.callCid)
                        _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "session_started").put("callId", event.callCid))
                    }
                    is CallRejectedEvent -> {
                        updateCallResponse(event.callCid, event.user.id, "rejected")
                         _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "rejected").put("callId", event.callCid).put("userId", event.user.id))
                    }
                    is CallMissedEvent -> {
                        updateCallResponse(event.callCid, event.user.id, "missed")
                         _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "missed").put("callId", event.callCid).put("userId", event.user.id))
                    }
                    is CallAcceptedEvent -> {
                        stopCallTimeoutMonitor(event.callCid)
                         _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "accepted").put("callId", event.callCid).put("userId", event.user.id))
                    }
                    is CallEndedEvent, is CallSessionEndedEvent -> {
                         val callCid = if (event is CallEndedEvent) event.callCid else (event as CallSessionEndedEvent).callCid
                        stopCallTimeoutMonitor(callCid)
                        _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "left").put("callId", callCid))
                    }
                    is ParticipantLeftEvent, is CallSessionParticipantLeftEvent -> {
                        val activeCall = streamVideoClient?.state?.activeCall?.value
                        val callId = when (event) {
                            is ParticipantLeftEvent -> event.callCid
                            is CallSessionParticipantLeftEvent -> event.callCid
                            else -> ""
                        }

                        if (activeCall != null && activeCall.cid == callId) {
                            val total = activeCall.state.participantCounts.value?.total
                            if (total != null && total <= 1) {
                                managerScope.launch(Dispatchers.IO) {
                                    endCall(activeCall)
                                }
                            }
                        }
                    }
                }
            }

            activeCallStateJob = managerScope.launch {
                client.state.activeCall.collect { call ->
                    if (call != null) {
                         _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "joined").put("callId", call.cid))
                    } else {
                        // When call ends
                         _callState.value = CallStateUpdate("callEvent", JSObject().put("state", "left").put("callId", ""))
                    }
                }
            }
        }
    }
    
    @OptIn(InternalStreamVideoApi::class)
    suspend fun endCall(call: Call) {
        try {
            val callInfo = call.get()
            val callData = callInfo.getOrNull()?.call
            val currentUserId = streamVideoClient?.userId
            val createdBy = callData?.createdBy?.id
            val isCreator = createdBy == currentUserId
            
            val totalParticipants = call.state.totalParticipants.value
            val shouldEndCall = isCreator || totalParticipants <= 1
            
            if (shouldEndCall) {
                call.end()
            } else {
                call.leave()
            }
        } catch (e: Exception) {
            Log.e("StreamCallManager", "Error ending call, defaulting to leave()", e)
            call.leave()
        }
    }

    private fun startCallTimeoutMonitor(callCid: String, memberIds: List<String>) {
        if (callTimeoutStates.containsKey(callCid)) return
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable { checkCallTimeout(callCid) }
        handler.postDelayed(timeoutRunnable, 30000) // 30 second timeout
        callTimeoutStates[callCid] = CallTimeoutState(members = memberIds, timer = handler, timeoutRunnable = timeoutRunnable)
        Log.d("StreamCallManager", "Started timeout monitor for call $callCid")
    }

    private fun stopCallTimeoutMonitor(callCid: String) {
        callTimeoutStates[callCid]?.let {
            it.timer.removeCallbacks(it.timeoutRunnable)
            callTimeoutStates.remove(callCid)
            Log.d("StreamCallManager", "Stopped timeout monitor for call $callCid")
        }
    }

    private fun updateCallResponse(callCid: String, userId: String, response: String) {
        val state = callTimeoutStates[callCid] ?: return
        state.participantResponses[userId] = response
        checkAllParticipantsResponded(callCid)
    }

    private fun checkAllParticipantsResponded(callCid: String) {
        val state = callTimeoutStates[callCid] ?: return
        if (state.participantResponses.keys.containsAll(state.members)) {
            if (state.participantResponses.values.all { it == "rejected" || it == "missed" }) {
                Log.d("StreamCallManager", "All participants rejected or missed call $callCid. Ending call.")
                endCallByCid(callCid)
            }
        }
    }

    private fun checkCallTimeout(callCid: String) {
        val state = callTimeoutStates[callCid] ?: return
        Log.d("StreamCallManager", "Call $callCid timed out. $state")
        endCallByCid(callCid)
    }
    
    private fun endCallByCid(callCid: String) {
        stopCallTimeoutMonitor(callCid)
        val (type, id) = callCid.toStreamCallId() ?: return
        val call = streamVideoClient?.call(type, id)
        managerScope.launch {
            call?.let { endCall(it) }
        }
    }
    
    data class CallStateUpdate(val type: String, val data: JSObject)
    data class CallTimeoutState(
        val members: List<String>,
        val participantResponses: MutableMap<String, String> = mutableMapOf(),
        val timer: Handler,
        val timeoutRunnable: Runnable
    )
} 
