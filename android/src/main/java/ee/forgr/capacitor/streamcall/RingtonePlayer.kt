package ee.forgr.capacitor.streamcall

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.getstream.video.android.core.notifications.NotificationHandler

class RingtonePlayer(
    private val application: Application,
    private val cancelIncomingCallService: () -> Unit = {  }
) {
    companion object {
        private const val DEFAULT_RINGTONE_DURATION = 30000L // 30 seconds in milliseconds
    }

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopRingtoneRunnable: Runnable? = null
    private var isPaused = false
    private var isStopped = true

    private fun isOurNotification(notification: android.service.notification.StatusBarNotification): Boolean {
        return notification.id == NotificationHandler.INCOMING_CALL_NOTIFICATION_ID
    }

    fun pauseRinging() {
        Log.d("RingtonePlayer", "Pause ringing")
        try {
            if (!isStopped) {
                mediaPlayer?.pause()
                isPaused = true
            }
        } catch (e: Exception) {
            Log.e("RingtonePlayer", "Error pausing ringtone: ${e.message}")
        }
    }

    fun resumeRinging() {
        Log.d("RingtonePlayer", "Resume ringing")
        try {
            if (!isStopped && isPaused) {
                mediaPlayer?.start()
                isPaused = false
            }
        } catch (e: Exception) {
            Log.e("RingtonePlayer", "Error resuming ringtone: ${e.message}")
        }
    }

    fun isPaused(): Boolean {
        return isPaused
    }

    fun startRinging() {
        Log.d("RingtonePlayer", "Start ringing")
        try {
            isStopped = false
            isPaused = false
            if (mediaPlayer == null) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(application, uri)
                    isLooping = true
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    prepare()
                }
            }

            val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifs = notificationManager.activeNotifications.toList()
            var notificationTime = 0L

            for (notification in notifs) {
                // First check if it's our notification
                val isOurs = isOurNotification(notification)

                // Only proceed with ringtone if it's our notification
                if (!isOurs) {
                    Log.d("RingtonePlayer", "Skipping notification as it's not our incoming call notification")
                    continue
                }

                // Cancel our notification
                try {
                    Log.d("RingtonePlayer", "Canceling notification/service with id: ${notification.id}")
                    this.cancelIncomingCallService()
                } catch (e: Exception) {
                    Log.e("RingtonePlayer", "Error cancelling notification: ${e.message}")
                }
                notificationTime = notification.postTime
            }

            if (notificationTime > 0) {
                val currentTime = System.currentTimeMillis()
                val elapsedTime = currentTime - notificationTime

                // Only start playing if we're within the ringtone duration
                if (elapsedTime < DEFAULT_RINGTONE_DURATION) {
                    // Get the ringtone duration
                    val ringtoneDuration = mediaPlayer?.duration?.toLong() ?: DEFAULT_RINGTONE_DURATION
                    
                    // Calculate the position to seek to
                    val seekPosition = (elapsedTime % ringtoneDuration).toInt()
                    Log.d("RingtonePlayer", "Seeking to position: $seekPosition ms in ringtone")
                    
                    mediaPlayer?.seekTo(seekPosition)
                    mediaPlayer?.start()

                    // Schedule stop at the remaining duration
                    val remainingDuration = DEFAULT_RINGTONE_DURATION - elapsedTime
                    stopRingtoneRunnable = Runnable { stopRinging() }
                    handler.postDelayed(stopRingtoneRunnable!!, remainingDuration)
                    
                    Log.d("RingtonePlayer", "Starting ringtone with offset: $elapsedTime ms, will play for $remainingDuration ms")
                } else {
                    Log.d("RingtonePlayer", "Not starting ringtone as elapsed time ($elapsedTime ms) exceeds duration")
                }
            } else {
                // If no notification time found, just play normally
                mediaPlayer?.start()
                
                // Schedule stop at the default duration
                stopRingtoneRunnable = Runnable { stopRinging() }
                handler.postDelayed(stopRingtoneRunnable!!, DEFAULT_RINGTONE_DURATION)
                
                Log.d("RingtonePlayer", "Starting ringtone with default duration")
            }
        } catch (e: Exception) {
            Log.e("RingtonePlayer", "Error playing ringtone: ${e.message}")
        }
    }

    fun stopRinging() {
        Log.d("RingtonePlayer", "Stop ringing")
        try {
            isStopped = true
            isPaused = false
            stopRingtoneRunnable?.let { handler.removeCallbacks(it) }
            stopRingtoneRunnable = null
            
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("RingtonePlayer", "Error stopping ringtone: ${e.message}")
        }
    }


} 