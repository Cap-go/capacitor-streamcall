package ee.forgr.capacitor.streamcall

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import io.getstream.video.android.compose.theme.VideoTheme
import io.getstream.video.android.compose.ui.components.background.CallBackground
import io.getstream.video.android.compose.ui.components.call.ringing.incomingcall.IncomingCallControls
import io.getstream.video.android.compose.ui.components.call.ringing.incomingcall.IncomingCallDetails
import io.getstream.video.android.core.Call
import io.getstream.video.android.core.MemberState
import io.getstream.video.android.core.RingingState
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.call.state.AcceptCall
import io.getstream.video.android.core.call.state.DeclineCall
import io.getstream.video.android.model.User
import java.time.OffsetDateTime

@Composable
fun IncomingCallView(
    streamVideo: StreamVideo?,
    call: Call? = null,
    onDeclineCall: ((Call) -> Unit)? = null,
    onAcceptCall: ((Call) -> Unit)? = null,
    onHideIncomingCall: (() -> Unit)? = null
) {
    val ringingState = call?.state?.ringingState?.collectAsState(initial = RingingState.Idle)

    LaunchedEffect(ringingState?.value) {
        Log.d("IncomingCallView", "Changing ringingState to $ringingState?.value")
        if (ringingState?.value == RingingState.TimeoutNoAnswer || ringingState?.value == RingingState.RejectedByAll) {
            Log.d("IncomingCallView", "Call timed out, hiding incoming call view")
            onHideIncomingCall?.invoke()
        }
    }

    if (ringingState != null) {
        Log.d("IncomingCallView", "Ringing state changed to: ${ringingState.value}")
    }

    val backgroundColor = when {
        streamVideo == null -> Color.Cyan
        call == null -> Color.Red
        else -> Color.Green
    }

    if (call !== null) {
//        val participants by call.state.participants.collectAsState()
//        val members by call.state.members.collectAsState()
//        call.state.session
        val session by call.state.session.collectAsState()
        val isCameraEnabled by call.camera.isEnabled.collectAsState()
        val isVideoType = true

        val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
        val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues()
        val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
        val layoutDirection = LocalLayoutDirection.current

        // println("participants: ${participants.map { it.name.value }} Members: ${members}")

        VideoTheme {
            CallBackground(
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = statusBarPadding.calculateTopPadding(),
                            bottom = navigationBarPadding.calculateBottomPadding()
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    IncomingCallDetails(
                        modifier = Modifier
                            .padding(
                                top = VideoTheme.dimens.spacingXl,
                                start = safeDrawingPadding.calculateStartPadding(layoutDirection),
                                end = safeDrawingPadding.calculateEndPadding(layoutDirection)
                            ),
                        isVideoType = isVideoType,
                        participants = (session?.participants?.map { MemberState(
                            user = User(
                                id = it.user.id,
                                name = it.user.id,
                                image = it.user.image
                            ),
                            custom = mapOf(),
                            role = it.role,
                            createdAt = org.threeten.bp.OffsetDateTime.now(),
                            updatedAt = org.threeten.bp.OffsetDateTime.now(),
                            deletedAt = org.threeten.bp.OffsetDateTime.now(),
                            acceptedAt = org.threeten.bp.OffsetDateTime.now(),
                            rejectedAt = org.threeten.bp.OffsetDateTime.now()
                        ) }?.filter { it.user.id != streamVideo?.userId }) ?: listOf()
                    )

                    IncomingCallControls(
                        modifier = Modifier
                            .padding(
                                bottom = VideoTheme.dimens.spacingL,
                                start = safeDrawingPadding.calculateStartPadding(layoutDirection),
                                end = safeDrawingPadding.calculateEndPadding(layoutDirection)
                            ),
                        isVideoCall = isVideoType,
                        isCameraEnabled = isCameraEnabled,
                        onCallAction = { action ->
                            when (action) {
                                DeclineCall -> onDeclineCall?.invoke(call)
                                AcceptCall -> onAcceptCall?.invoke(call)
                                else -> { /* ignore other actions */ }
                            }
                        }
                    )
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        )
    }
}