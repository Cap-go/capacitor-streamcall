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
                
                true
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
                
                val call = client.call(callType, callId)
                
                // Create call with options
                val result = call.create(
                    memberIds = userIds,
                    ring = ring,
                    notify = ring,
                    startsAt = null,
                    custom = custom ?: emptyMap(),
                    team = team,
                    video = video
                )
                
                result.isSuccess
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
