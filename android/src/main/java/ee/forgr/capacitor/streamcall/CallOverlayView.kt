package ee.forgr.capacitor.streamcall

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.getstream.video.android.compose.permission.LaunchCallPermissions
import io.getstream.video.android.compose.theme.VideoTheme
import io.getstream.video.android.compose.ui.components.call.renderer.FloatingParticipantVideo
import io.getstream.video.android.compose.ui.components.call.renderer.ParticipantVideo
import io.getstream.video.android.compose.ui.components.call.renderer.ParticipantsLayout
import io.getstream.video.android.compose.ui.components.call.renderer.RegularVideoRendererStyle
import io.getstream.video.android.compose.ui.components.call.renderer.VideoRendererStyle
import io.getstream.video.android.compose.ui.components.call.renderer.copy
import io.getstream.video.android.core.GEO
import io.getstream.video.android.core.ParticipantState
import io.getstream.video.android.core.RealtimeConnection
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.StreamVideoBuilder
import io.getstream.video.android.model.User
import io.getstream.video.android.core.Call
import io.getstream.video.android.compose.ui.components.video.VideoRenderer
import io.getstream.video.android.compose.ui.components.video.VideoScalingType
import io.getstream.video.android.compose.ui.components.video.config.VideoRendererConfig
import stream.video.sfu.models.TrackType
import androidx.compose.ui.graphics.Color

@Composable
private fun ParticipantVideoView(
    call: Call,
    participant: ParticipantState,
    parentSize: IntSize,
    onVisibilityChanged: ((ParticipantState, Boolean) -> Unit)? = null
) {
    LaunchedEffect(participant) {
        onVisibilityChanged?.invoke(participant, true)
    }

    DisposableEffect(participant) {
        onDispose {
            onVisibilityChanged?.invoke(participant, false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(VideoTheme.colors.baseSenary),
        contentAlignment = Alignment.Center
    ) {
        ParticipantVideo(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            call = call,
            participant = participant
        )
    }
}

@Composable
fun CallOverlayView(
    context: Context,
    streamVideo: StreamVideo?,
    call: Call?
) {
    if (streamVideo == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Red)
        )
        return
    }

    // Collect the active call state
    //val activeCall by internalInstance.state.activeCall.collectAsState()
    // val call = activeCall
    if (call == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.LightGray)
        )
        return
    }

    // Handle permissions in the Composable context
    LaunchCallPermissions(
        call = call,
        onAllPermissionsGranted = {
            try {
                // Check session using reflection before joining
                val callClass = call.javaClass
                val sessionField = callClass.getDeclaredField("session")
                sessionField.isAccessible = true
                val sessionValue = sessionField.get(call)
                
                if (sessionValue != null) {
                    android.util.Log.d("CallOverlayView", "Session already exists, skipping join")
                } else {
                    android.util.Log.d("CallOverlayView", "No existing session, attempting to join call")
                    val result = call.join(create = true)
                    result.onError {
                        android.util.Log.d("CallOverlayView", "Error joining call")
                        Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CallOverlayView", "Error checking session or joining call", e)
                Toast.makeText(context, "Failed to join call: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    )

    // Apply VideoTheme
    VideoTheme {
        // Define required properties.
        val allParticipants by call.state.participants.collectAsState()
        val remoteParticipants = allParticipants.filter { !it.isLocal }
        val remoteParticipantsCount by call.state.participantCounts.collectAsState()
        val connection by call.state.connection.collectAsState()
        val sessionId by call.state.session.collectAsState()
        var parentSize: IntSize by remember { mutableStateOf(IntSize(0, 0)) }

        // Add logging for debugging
        LaunchedEffect(allParticipants, remoteParticipants, remoteParticipantsCount, connection, sessionId) {
            android.util.Log.d("CallOverlayView", "Detailed State Update:")
            android.util.Log.d("CallOverlayView", "- Call ID: ${call.id}")
            android.util.Log.d("CallOverlayView", "- Session ID: ${sessionId?.id}")
            android.util.Log.d("CallOverlayView", "- All Participants: $allParticipants")
            android.util.Log.d("CallOverlayView", "- Remote Participants: $remoteParticipants")
            android.util.Log.d("CallOverlayView", "- Remote Participant Count: $remoteParticipantsCount")
            android.util.Log.d("CallOverlayView", "- Connection State: $connection")
            
            // Log each participant's details
            allParticipants.forEach { participant ->
                android.util.Log.d("CallOverlayView", "Participant Details:")
                android.util.Log.d("CallOverlayView", "- ID: ${participant.userId}")
                android.util.Log.d("CallOverlayView", "- Is Local: ${participant.isLocal}")
                android.util.Log.d("CallOverlayView", "- Has Video: ${participant.videoEnabled}")
                android.util.Log.d("CallOverlayView", "- Has Audio: ${participant.audioEnabled}")
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(VideoTheme.colors.baseSenary)
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .onSizeChanged { parentSize = it }
        ) {
            val videoRenderer: @Composable (
                modifier: Modifier,
                call: Call,
                participant: ParticipantState,
                style: VideoRendererStyle,
            ) -> Unit = { videoModifier, videoCall, videoParticipant, videoStyle ->
                ParticipantVideo(
                    modifier = videoModifier,
                    call = videoCall,
                    participant = videoParticipant,
                    style = videoStyle,
                    scalingType = VideoScalingType.SCALE_ASPECT_FIT,
                    actionsContent = { _, _, _ -> }
                )
            }
            val videoRendererNoAction: @Composable (ParticipantState) -> Unit =
                { participant ->
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
            val floatingVideoRender: @Composable BoxScope.(
                call: Call,
                parentSize: IntSize
            ) -> Unit = { call, _ ->
                val participants by call.state.participants.collectAsState()
                val me = participants.firstOrNull { it.isLocal }
                me?.let { localParticipant ->
                    val configuration = LocalConfiguration.current
                    val layoutDirection = LocalLayoutDirection.current
                    val density = LocalDensity.current
                    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
                    val adjustedSize = with(density) {
                        IntSize(
                            width = (configuration.screenWidthDp.dp.toPx() - safeDrawingPadding.calculateLeftPadding(layoutDirection).toPx() - safeDrawingPadding.calculateRightPadding(layoutDirection).toPx()).toInt(),
                            height = (configuration.screenHeightDp.dp.toPx() - safeDrawingPadding.calculateTopPadding().toPx() - safeDrawingPadding.calculateBottomPadding().toPx()).toInt()
                        )
                    }
                    FloatingParticipantVideo(
                        call = call,
                        videoRenderer = videoRendererNoAction,
                        participant = if (LocalInspectionMode.current) {
                            participants.first()
                        } else {
                            localParticipant
                        },
                        style = RegularVideoRendererStyle(),
                        parentBounds = adjustedSize,
                    )
                }
            }

            if (remoteParticipants.isNotEmpty()) {
                android.util.Log.d("CallOverlayView", "Showing ParticipantsLayout with ${remoteParticipants.size} remote participants")
                ParticipantsLayout(
                    modifier = Modifier
                        .fillMaxSize(),
                    call = call,
                    videoRenderer = videoRenderer,
                    floatingVideoRenderer = floatingVideoRender
                )
            } else {
                if (connection != RealtimeConnection.Connected) {
                    android.util.Log.d("CallOverlayView", "Showing waiting message - not connected")
                    Text(
                        text = "waiting for a remote participant...",
                        fontSize = 30.sp,
                        color = VideoTheme.colors.basePrimary
                    )
                } else {
                    Text(
                        modifier = Modifier.padding(30.dp),
                        text = "Join call ${call.id} in your browser to see the video here",
                        fontSize = 30.sp,
                        color = VideoTheme.colors.basePrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}