package ee.forgr.capacitor.streamcall

import io.getstream.video.android.model.StreamCallId

fun String.toStreamCallId(): StreamCallId? {
    val parts = this.split(":")
    return if (parts.size == 2) StreamCallId(parts[0], parts[1]) else null
} 
