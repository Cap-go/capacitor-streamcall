package ee.forgr.capacitor.streamcall

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.getstream.video.android.compose.theme.VideoTheme
import io.getstream.video.android.compose.ui.ComposeStreamCallActivity
import io.getstream.video.android.compose.ui.StreamCallActivityComposeDelegate
import io.getstream.video.android.compose.ui.components.call.CallAppBar
import io.getstream.video.android.compose.ui.components.call.activecall.AudioOnlyCallContent
import io.getstream.video.android.compose.ui.components.call.activecall.CallContent
import io.getstream.video.android.compose.ui.components.call.renderer.FloatingParticipantVideo
import io.getstream.video.android.compose.ui.components.call.renderer.ParticipantVideo
import io.getstream.video.android.compose.ui.components.call.renderer.RegularVideoRendererStyle
import io.getstream.video.android.compose.ui.components.call.renderer.VideoRendererStyle
import io.getstream.video.android.compose.ui.components.video.VideoScalingType
import io.getstream.video.android.core.Call
import io.getstream.video.android.core.ParticipantState
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.call.state.CallAction
import io.getstream.video.android.core.notifications.NotificationHandler
import io.getstream.video.android.ui.common.StreamCallActivity
import io.getstream.video.android.ui.common.StreamCallActivityConfiguration
import io.getstream.video.android.ui.common.util.StreamCallActivityDelicateApi
import kotlinx.coroutines.runBlocking

@OptIn(StreamCallActivityDelicateApi::class)
class CallActivity : ComposeStreamCallActivity() {

    override val uiDelegate: StreamDemoUiDelegate = StreamDemoUiDelegate()
    override val configuration: StreamCallActivityConfiguration =
        StreamCallActivityConfiguration(
            closeScreenOnCallEnded = false,
            canSkipPermissionRationale = false,
        )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.action == NotificationHandler.ACTION_ACCEPT_CALL) {
            val activeCall = StreamVideo.instance().state.activeCall.value
            if (activeCall != null) {
                leave(activeCall)
                finish()
                startActivity(intent)
            }
        }
    }

    @StreamCallActivityDelicateApi
    override fun onPreCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        runBlocking {
            if (!StreamVideo.isInstalled) {
                android.util.Log.i("CustomCallActivity", "Cannot start, because SDK not installed")
                this@CallActivity.finish()
            }
        }
        listRunningActivities()
        super.onPreCreate(savedInstanceState, persistentState)
    }

    private fun listRunningActivities() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appTasks = activityManager.getAppTasks()
        
        android.util.Log.d("CallActivity", "Currently running activities in our app:")
        for (taskInfo in appTasks) {
            val taskInfo = taskInfo.taskInfo
            android.util.Log.d("CallActivity", "Task ID: ${taskInfo.id}")
            android.util.Log.d("CallActivity", "Base Activity: ${taskInfo.baseActivity?.className}")
            android.util.Log.d("CallActivity", "Top Activity: ${taskInfo.topActivity?.className}")
            android.util.Log.d("CallActivity", "Number of Activities: ${taskInfo.numActivities}")
            android.util.Log.d("CallActivity", "-------------------")

            // If CallActivity is the base activity, we need to start Capacitor in background
            if (taskInfo.baseActivity?.className == "ee.forgr.capacitor.streamcall.CallActivity") {
                val mainActivityClass = getString(R.string.CAPACITOR_STREAM_VIDEO_MAIN_ACTIVITY_CLASS)
                    ?: throw IllegalStateException("CAPACITOR_STREAM_VIDEO_MAIN_ACTIVITY_CLASS not found in resources")
                
                try {
                    val intent = Intent(this, Class.forName(mainActivityClass)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                               Intent.FLAG_ACTIVITY_NO_ANIMATION or 
                               Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                               Intent.FLAG_ACTIVITY_NO_HISTORY
                    }
                    // startActivity(intent)
                    // Immediately bring call activity back to front
                    val callIntent = Intent(this, CallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                    // startActivity(callIntent)
                } catch (e: Exception) {
                    android.util.Log.e("CallActivity", "Failed to start main activity", e)
                }
            }
        }
    }

    class StreamDemoUiDelegate() : StreamCallActivityComposeDelegate() {

        @Composable
        override fun StreamCallActivity.VideoCallContent(call: Call) {
            val customOnCallAction: (CallAction) -> Unit = {
                onCallAction(call, it)
            };

            val videoRendererNoAction: @Composable (Modifier, Call, ParticipantState, VideoRendererStyle) -> Unit =
                { modifier, _, participant, _ ->
                    ParticipantVideo(
                        modifier = modifier,
                        call = call,
                        participant = participant,
                        style = RegularVideoRendererStyle(),
                        scalingType = VideoScalingType.SCALE_ASPECT_FIT,
                        actionsContent = { _, _, _ -> }
                    )
                }
            val floatingVideoRenderer: @Composable (BoxScope.(call: Call, IntSize) -> Unit)? =
                { call, size ->
                    val currentLocal by call.state.me.collectAsStateWithLifecycle()
                    val participants by call.state.participants.collectAsStateWithLifecycle()
                    val sortedParticipants by call.state.sortedParticipants.collectAsStateWithLifecycle(emptyList())
                    val callParticipants by remember(participants) {
                        derivedStateOf {
                            if (sortedParticipants.size > 6) {
                                sortedParticipants
                            } else {
                                participants
                            }
                        }
                    }

                    val configuration = LocalConfiguration.current
                    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
                    val layoutDirection = LocalLayoutDirection.current
                    val density = LocalDensity.current

                    val adjustedSize = with(density) {
                        IntSize(
                            width = (configuration.screenWidthDp.dp.toPx() - safeDrawingPadding.calculateLeftPadding(layoutDirection).toPx() - safeDrawingPadding.calculateRightPadding(layoutDirection).toPx()).toInt(),
                            height = (configuration.screenHeightDp.dp.toPx() - safeDrawingPadding.calculateTopPadding().toPx() - safeDrawingPadding.calculateBottomPadding().toPx()).toInt()
                        )
                    }

                    val participant = if (LocalInspectionMode.current) {
                        callParticipants.first()
                    } else {
                        currentLocal
                    }

                    participant?.let {
                        FloatingParticipantVideo(
                            call = call,
                            participant = it,
                            style = RegularVideoRendererStyle().copy(isShowingConnectionQualityIndicator = false),
                            parentBounds = adjustedSize,
                            videoRenderer = { participant ->
                                ParticipantVideo(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(VideoTheme.shapes.dialog),
                                    call = call,
                                    participant = participant,
                                    style = RegularVideoRendererStyle(),
                                    scalingType = VideoScalingType.SCALE_ASPECT_FIT,
                                    actionsContent = { _, _, _ -> }
                                )
                            }

                        )
                    }


                }

            CallContent(
                call = call,
                onCallAction = customOnCallAction,
                onBackPressed = {
                    onBackPressed(call)
                },
                videoRenderer = videoRendererNoAction,

                appBarContent = {
                    CallAppBar(
                        call = call,
                        onCallAction = customOnCallAction,
                        trailingContent = {},
                        leadingContent = {}
                    )
                },
                controlsContent = {},
                floatingVideoRenderer = floatingVideoRenderer
            )
        }

        @Composable
        override fun StreamCallActivity.CallDisconnectedContent(call: Call) {
            goBackToMainScreen()
        }

        @Composable
        override fun StreamCallActivity.AudioCallContent(call: Call) {
            val micEnabled by call.microphone.isEnabled.collectAsStateWithLifecycle()

            AudioOnlyCallContent(
                call = call,
                isMicrophoneEnabled = micEnabled,
                onCallAction = { onCallAction(call, it) },
                onBackPressed = { onBackPressed(call) },
            )
        }

        private fun StreamCallActivity.goBackToMainScreen() {
            if (!isFinishing) {
                finish()
            }
        }
    }
}