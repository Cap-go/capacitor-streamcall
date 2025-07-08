import android.view.MotionEvent
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import android.util.Log
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible

class TouchInterceptWrapper(private val originalViewGroup: ViewGroup) : CoordinatorLayout(
    originalViewGroup.context
) {
    init {
        // Copy layout parameters and children
        layoutParams = originalViewGroup.layoutParams
        while (originalViewGroup.isNotEmpty()) {
            val child = originalViewGroup.getChildAt(0)
            originalViewGroup.removeViewAt(0)
            addView(child)
        }
        Log.d("TouchInterceptWrapper", "Wrapped original view group and moved children.")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Log.d("TouchInterceptWrapper", "dispatchTouchEvent: action=${MotionEvent.actionToString(ev.action)}, x=${ev.x}, y=${ev.y}")
        var eventHandledOverall = false // Declare and initialize
        // Traverse children from top (highest z) to bottom
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            Log.d("TouchInterceptWrapper", "Checking child $i: ${child::class.java.simpleName}, visibility: ${child.isVisible}")
            if (child.isVisible) {
                val copy = MotionEvent.obtain(ev)
                // It's important to transform event coordinates to the child's coordinate system
                copy.offsetLocation(-child.left.toFloat(), -child.top.toFloat())
                val handledByChild = child.dispatchTouchEvent(copy)
                Log.d("TouchInterceptWrapper", "Child $i (${child::class.java.simpleName}) handled: $handledByChild")
                copy.recycle()
                if (handledByChild) {
                    Log.d("TouchInterceptWrapper", "Event handled by child $i (${child::class.java.simpleName})")
                    eventHandledOverall = true // Set to true if any child handles it
                    // Do NOT return true here, allow other children to receive the event
                }
            }
        }
        Log.d("TouchInterceptWrapper", "Overall event handled: $eventHandledOverall")
        return eventHandledOverall // Return true if any child handled it, false otherwise
    }
}
