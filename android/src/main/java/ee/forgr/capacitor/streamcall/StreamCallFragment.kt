package ee.forgr.capacitor.streamcall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.getstream.video.android.core.Call
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer

class StreamCallFragment : Fragment() {
    private var call: Call? = null
    private var videoRenderer: VideoTextureViewRenderer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        videoRenderer = VideoTextureViewRenderer(requireContext())
        return videoRenderer!!
    }

    fun getCall(): Call? {
        return call
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videoRenderer = null
    }
} 
