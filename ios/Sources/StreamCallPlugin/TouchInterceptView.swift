import UIKit
import os.log
import WebKit

class TouchInterceptView: UIView {
    private weak var webView: UIView?
    private weak var overlayView: UIView?
    private var forwardTimer: Timer?
    private var lastTouchPoint: CGPoint?
    private let touchThreshold: CGFloat = 5.0 // pixels
    private let timerDelay: TimeInterval = 0.1 // seconds
    private var isCallActive: Bool = false
    private var hasActiveCallCheck: (() -> Bool)?

    func setupWithWebView(_ webView: UIView, overlayView: UIView) {
        self.webView = webView
        self.overlayView = overlayView

        // Ensure this view is transparent and doesn't interfere with display
        self.backgroundColor = .clear
        self.isOpaque = false
        // os_log(.debug, "TouchInterceptView: setupWithWebView - webView: %{public}s, overlayView: %{public}s", String(describing: webView), String(describing: overlayView))
    }

    func setActiveCallCheck(_ check: @escaping () -> Bool) {
        self.hasActiveCallCheck = check
    }

    func setCallActive(_ active: Bool) {
        self.isCallActive = active
        // os_log(.debug, "TouchInterceptView: setCallActive - %{public}s", String(describing: active))

        // Cancel any pending timer when call becomes inactive
        if !active {
            forwardTimer?.invalidate()
            forwardTimer = nil
            lastTouchPoint = nil
        }
    }

    private func shouldInterceptTouches() -> Bool {
        // Check both our flag and actual call state
        let hasActiveCall = hasActiveCallCheck?() ?? false
        let shouldIntercept = isCallActive && hasActiveCall

        if isCallActive != hasActiveCall {
            // os_log(.debug, "TouchInterceptView: State mismatch - isCallActive: %{public}s, hasActiveCall: %{public}s", String(describing: isCallActive), String(describing: hasActiveCall))
        }

        return shouldIntercept
    }

    private func isInteractive(_ view: UIView) -> Bool {
        if view is UIControl { return true }
        if let grs = view.gestureRecognizers, !grs.isEmpty { return true }
        return false
    }

    private func nonGreedyInteractiveHitTest(in view: UIView, point: CGPoint, with event: UIEvent?) -> UIView? {
        // Traverse subviews in reverse order (frontmost first)
        for subview in view.subviews.reversed() where subview.isUserInteractionEnabled && !subview.isHidden && subview.alpha >= 0.01 {
            let converted = view.convert(point, to: subview)
            if subview.point(inside: converted, with: event) {
                if let hit = nonGreedyInteractiveHitTest(in: subview, point: converted, with: event) {
                    return hit
                }
            }
        }
        // If no subview handled, return view itself only if truly interactive
        return isInteractive(view) && view.point(inside: point, with: event) ? view : nil
    }

    private func forwardClickToWeb(at pointInSelf: CGPoint) {
        guard let wk = webView as? WKWebView else { return }
        let locationInWeb = self.convert(pointInSelf, to: wk)
        let x = Int(locationInWeb.x)
        let y = Int(locationInWeb.y)
        let js = """
        (() => {
            const x = \(x); const y = \(y);
            const el = document.elementFromPoint(x, y);
            if (!el) return 'NO_ELEM';
            const eventInit = { bubbles: true, cancelable: true, clientX: x, clientY: y };
            const touchInit = { bubbles: true, cancelable: true, touches: [{ clientX: x, clientY: y }], targetTouches: [], changedTouches: [], shiftKey: false };
            const seq = [];
            try {
                seq.push(new TouchEvent('touchstart', touchInit));
            } catch(e) { console.log('TouchEvent not supported', e); }
            seq.push(new PointerEvent('pointerdown', { ...eventInit, pointerType: 'touch' }));
            seq.push(new MouseEvent('mousedown', eventInit));
            try {
                seq.push(new TouchEvent('touchend', touchInit));
            } catch(e) { }
            seq.push(new PointerEvent('pointerup', { ...eventInit, pointerType: 'touch' }));
            seq.push(new MouseEvent('mouseup', eventInit));
            seq.push(new MouseEvent('click', eventInit));
            seq.forEach(evt => el.dispatchEvent(evt));
            console.log('SyntheticClick seq on', el);
            return el.tagName;
        })();
        """
        // os_log(.debug, "TouchInterceptView: forwardClickToWeb - (%{public}d,%{public}d)", x, y)
        wk.evaluateJavaScript(js) { _, error in
            if let error = error {
                // os_log(.error, "TouchInterceptView: JS error %{public}s", String(describing: error))
            } else {
                // os_log(.debug, "TouchInterceptView: JS returned %{public}s", String(describing: result))
            }
        }
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {

        // Check if we should intercept touches
        if !shouldInterceptTouches() {
            if let webView = self.webView {
                let webPoint = self.convert(point, to: webView)
                let result = webView.hitTest(webPoint, with: event)
                //                os_log(.debug, "TouchInterceptView: hitTest - Not intercepting, direct WebView result %{public}s at %{public}s", String(describing: result), String(describing: webPoint))
                return result
            }
            return nil
        }

        // os_log(.debug, "TouchInterceptView: hitTest entry at %{public}s, callActive: %{public}s", String(describing: point), String(describing: isCallActive))

        // Check if this is same touch location continuing
        if let lastPoint = lastTouchPoint {
            let distance = sqrt(pow(point.x - lastPoint.x, 2) + pow(point.y - lastPoint.y, 2))
            if distance <= touchThreshold {
                // Same touch continuing, cancel existing timer
                forwardTimer?.invalidate()
                // os_log(.debug, "TouchInterceptView: Touch continuing at %{public}s, cancelling timer", String(describing: point))
            }
        }

        // Store current point and start new timer
        lastTouchPoint = point
        forwardTimer?.invalidate()
        forwardTimer = Timer.scheduledTimer(withTimeInterval: timerDelay, repeats: false) { [weak self] _ in
            guard let self = self, self.shouldInterceptTouches() else {
                // os_log(.debug, "TouchInterceptView: Timer fired but no longer in call, skipping web forward")
                return
            }
            // os_log(.debug, "TouchInterceptView: Timer fired, forwarding click to web at %{public}s", String(describing: point))
            self.forwardClickToWeb(at: point)
        }

        // 1. interactive hit on overlay (including root)
        if let overlayView = self.overlayView, !overlayView.isHidden {
            let overlayPoint = self.convert(point, to: overlayView)
            if let overlayHit = nonGreedyInteractiveHitTest(in: overlayView, point: overlayPoint, with: event) {
                // os_log(.debug, "TouchInterceptView: hitTest - Overlay view %{public}s at %{public}s", String(describing: overlayHit), String(describing: overlayPoint))
                return overlayHit
            }
        }
        // 2. webView fallback
        if let webView = self.webView {
            let webPoint = self.convert(point, to: webView)
            let result = webView.hitTest(webPoint, with: event)
            // os_log(.debug, "TouchInterceptView: hitTest - WebView result %{public}s at %{public}s", String(describing: result), String(describing: webPoint))
            return result
        }
        // os_log(.debug, "TouchInterceptView: hitTest - No view found for %{public}s", String(describing: point))
        return nil
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        // Check if we should intercept touches
        if !shouldInterceptTouches() {
            guard let webView = self.webView else {
                return super.point(inside: point, with: event)
            }
            let webViewPoint = self.convert(point, to: webView)
            let result = webView.point(inside: webViewPoint, with: event)
            // os_log(.debug, "TouchInterceptView: point(inside) - Not intercepting, WebView only (%{public}s at %{public}s) for original point %{public}s = %s", String(describing: result), String(describing: webViewPoint), String(describing: point), String(describing: result))
            return result
        }

        guard let webView = self.webView else {
            // os_log(.debug, "TouchInterceptView: point(inside) - webView is nil for point %{public}s. Checking overlay or deferring to super.", String(describing: point))
            if let overlayView = self.overlayView, !overlayView.isHidden {
                let overlayPoint = self.convert(point, to: overlayView)
                let overlayViewConsidersPointInside = overlayView.point(inside: overlayPoint, with: event)
                // os_log(.debug, "TouchInterceptView: point(inside) - webView nil. Overlay (%{public}s) for converted point %{public}s = %s", String(describing: overlayViewConsidersPointInside), String(describing: overlayPoint), String(describing: overlayViewConsidersPointInside))
                return overlayViewConsidersPointInside
            }
            return super.point(inside: point, with: event)
        }

        let webViewPoint = self.convert(point, to: webView)
        let webViewConsidersPointInside = webView.point(inside: webViewPoint, with: event)

        if let overlayView = self.overlayView, !overlayView.isHidden {
            let overlayPoint = self.convert(point, to: overlayView)
            let overlayViewConsidersPointInside = overlayView.point(inside: overlayPoint, with: event)
            let result = webViewConsidersPointInside || overlayViewConsidersPointInside
            // os_log(.debug, "TouchInterceptView: point(inside) - WebView (%{public}s at %{public}s) OR Visible Overlay (%{public}s at %{public}s) for original point %{public}s = %s", String(describing: webViewConsidersPointInside), String(describing: webViewPoint), String(describing: overlayViewConsidersPointInside), String(describing: overlayPoint), String(describing: point), String(describing: result))
            return result
        } else {
            if self.overlayView == nil {
                // os_log(.debug, "TouchInterceptView: point(inside) - Overlay nil. WebView (%{public}s at %{public}s) for original point %{public}s = %s", String(describing: webViewConsidersPointInside), String(describing: webViewPoint), String(describing: point), String(describing: webViewConsidersPointInside))
            } else {
                // os_log(.debug, "TouchInterceptView: point(inside) - Overlay hidden. WebView (%{public}s at %{public}s) for original point %{public}s = %s", String(describing: webViewConsidersPointInside), String(describing: webViewPoint), String(describing: point), String(describing: webViewConsidersPointInside))
            }
            return webViewConsidersPointInside
        }
    }
}
