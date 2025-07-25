package ee.forgr.capacitor.streamcall

import android.content.Context
import android.util.Log
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.StreamVideoBuilder
import io.getstream.video.android.core.notifications.NotificationConfig
import io.getstream.video.android.core.notifications.handlers.CompatibilityStreamNotificationHandler
import io.getstream.video.android.core.notifications.handlers.StreamNotificationBuilderInterceptors
import io.getstream.android.push.firebase.FirebasePushDeviceGenerator
import io.getstream.video.android.model.User
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import android.app.Application
import kotlinx.coroutines.*

/**
 * Singleton manager for StreamCall functionality.
 * Handles StreamVideo client initialization as a separated service.
 */
object StreamCallManager {
    private const val TAG = "StreamCallManager"
    
    private var streamVideo: StreamVideo? = null
    private var applicationContext: Context? = null

    /**
     * Initializes the StreamCallManager with the given context.
     * Always stores the Application context to avoid memory leaks.
     */
    fun initialize(context: Context) {
        Log.d(TAG, "Initializing StreamCallManager")
        // Always use application context to avoid memory leaks
        applicationContext = context.applicationContext
    }
    
    /**
     * Logs in the user and creates StreamVideo client with current notification system
     */
    suspend fun login(
        apiKey: String,
        token: String,
        userId: String,
        userName: String,
        userImage: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val context = applicationContext ?: throw IllegalStateException("StreamCallManager not initialized")
                
                Log.d(TAG, "Logging in user: $userId")
                
                // Clean up existing client
                cleanupClient()
                
                // Create user
                val user = User(
                    id = userId,
                    name = userName,
                    image = userImage
                )
                
                // Create notification configuration
                val notificationConfig = NotificationConfig(
                    pushDeviceGenerators = listOf(
                        FirebasePushDeviceGenerator(
                            providerName = "firebase",
                            context = context
                        )
                    ),
                    requestPermissionOnAppLaunch = { true },
                    notificationHandler = CompatibilityStreamNotificationHandler(
                        application = context as Application,
                        intentResolver = CustomStreamIntentResolver(context),
                        initialNotificationBuilderInterceptor = object : StreamNotificationBuilderInterceptors() {
                            override fun onBuildIncomingCallNotification(
                                builder: NotificationCompat.Builder,
                                fullScreenPendingIntent: PendingIntent,
                                acceptCallPendingIntent: PendingIntent,
                                rejectCallPendingIntent: PendingIntent,
                                callerName: String?,
                                shouldHaveContentIntent: Boolean
                            ): NotificationCompat.Builder {
                                return builder.setContentIntent(acceptCallPendingIntent)
                                    .setFullScreenIntent(fullScreenPendingIntent, true)
                            }
                        }
                    )
                )
                
                // Create StreamVideo client
                val client = StreamVideoBuilder(
                    context = context,
                    apiKey = apiKey,
                    user = user,
                    token = token,
                    notificationConfig = notificationConfig
                ).build()
                
                streamVideo = client
                Log.d(TAG, "StreamVideo client created successfully")
                
                // Wait for client to be fully authenticated (userId available)
                var attempts = 0
                val maxAttempts = 50 // 5 seconds total
                while (client.userId.isNullOrEmpty() && attempts < maxAttempts) {
                    Log.d(TAG, "Waiting for client authentication... attempt ${attempts + 1}/$maxAttempts")
                    kotlinx.coroutines.delay(100)
                    attempts++
                }
                
                if (client.userId.isNullOrEmpty()) {
                    Log.e(TAG, "Client authentication timed out - userId still null after ${maxAttempts * 100}ms")
                    false
                } else {
                    Log.d(TAG, "Client authenticated successfully with userId: ${client.userId}")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to login", e)
                false
            }
        }
    }
    
    /**
     * Cleans up the StreamVideo client
     */
    fun cleanupClient() {
        Log.d(TAG, "Cleaning up StreamVideo client")
        streamVideo?.let { client ->
            try {
                // StreamVideo client cleanup is handled automatically
                Log.d(TAG, "StreamVideo client cleanup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during client cleanup", e)
            }
        }
        streamVideo = null
    }
    
    /**
     * Makes a call to the specified users
     */
    suspend fun makeCall(
        callType: String,
        callId: String,
        userIds: List<String>,
        ring: Boolean = true,
        video: Boolean = false,
        team: String? = null,
        custom: Map<String, Any>? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = streamVideo ?: throw IllegalStateException("StreamVideo client not initialized")
                
                Log.d(TAG, "Making call to users: $userIds, callId: $callId")
                Log.d(TAG, "Call parameters - type: $callType, ring: $ring, video: $video, team: $team")
                Log.d(TAG, "Custom data: ${custom ?: "none"}")
                Log.d(TAG, "Ring parameter received: $ring (type: ${ring::class.simpleName})")
                
                val call = client.call(callType, callId)
                
                // Include current user in memberIds (required by Stream API)
                val currentUserId = client.userId
                val allMemberIds = if (currentUserId in userIds) userIds else userIds + currentUserId
                Log.d(TAG, "All members including self: $allMemberIds (current user: $currentUserId)")
                
                // Create call with options
                val result = call.create(
                    memberIds = allMemberIds,
                    ring = ring,
                    notify = ring,
                    startsAt = null,
                    custom = custom ?: emptyMap(),
                    team = team,
                    video = video
                )
                
                if (result.isFailure) {
                    val error = result.errorOrNull()
                    Log.w(TAG, "Call creation API returned failure, but this might be a false negative: ${error?.message}")
                    Log.w(TAG, "Error details: $error")
                    // Don't return false immediately - the call might still be created (as shown by CallCreatedEvent)
                }
                
                Log.d(TAG, "Call creation initiated - success status: ${result.isSuccess}")
                // Return true as the call creation was initiated (CallCreatedEvent will confirm actual creation)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to make call", e)
                false
            }
        }
    }
    
    /**
     * Gets the current StreamVideo client instance
     */
    fun getStreamVideo(): StreamVideo? = streamVideo
    
    /**
     * Checks if user is currently logged in
     */
    fun isLoggedIn(): Boolean = streamVideo != null
    
    /**
     * Logs out the current user
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Logging out user")
        
        try {
            // Clear stored credentials
            applicationContext?.let { context ->
                SecureUserRepository.getInstance(context).removeCurrentUser()
            }
            
            // Cleanup StreamVideo client
            streamVideo?.let { client ->
                try {
                    // Delete Firebase device token
                    magicDeviceDelete(client)
                    // Logout from StreamVideo
                    client.logOut()
                    // Remove client singleton
                    StreamVideo.removeClient()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during client logout", e)
                }
            }
            
            cleanupClient()
        } catch (e: Exception) {
            Log.e(TAG, "Error during logout", e)
        }
    }
    
    /**
     * Deletes Firebase device token from StreamVideo
     */
    private fun magicDeviceDelete(client: StreamVideo) {
        try {
            Log.d(TAG, "Starting device cleanup")
            
            // Import FirebaseMessaging and related classes
            val firebaseMessaging = com.google.firebase.messaging.FirebaseMessaging.getInstance()
            firebaseMessaging.token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d(TAG, "Found firebase token")
                    val device = io.getstream.video.android.model.Device(
                        id = token,
                        pushProvider = io.getstream.android.push.PushProvider.FIREBASE.key,
                        pushProviderName = "firebase",
                    )
                    
                    // Delete device in background
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            client.deleteDevice(device)
                            Log.d(TAG, "Device deleted successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting device", e)
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to get Firebase token", task.exception)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in device cleanup", e)
        }
    }
} 
