import android.view.MotionEvent
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout

class TouchInterceptWrapper(private val originalViewGroup: ViewGroup) : CoordinatorLayout(
    originalViewGroup.context
) {
    init {
        // Copy layout parameters and children
        layoutParams = originalViewGroup.layoutParams
        while (originalViewGroup.childCount > 0) {
            val child = originalViewGroup.getChildAt(0)
            originalViewGroup.removeViewAt(0)
            addView(child)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Broadcast to all children first
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == VISIBLE) {
                val eventCopy = MotionEvent.obtain(ev)
                child.dispatchTouchEvent(eventCopy)
                eventCopy.recycle()
            }
        }
        // Then let the normal touch handling occur
        return super.dispatchTouchEvent(ev)
    }
}