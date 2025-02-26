import UIKit

class TouchInterceptView: UIView {
    private var webView: UIView?
    private var overlayView: UIView?
    private var labeledFrames: [ViewFramePreferenceData] = []
    
    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = true
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        isUserInteractionEnabled = true
    }
    
    func setupWithWebView(_ webView: UIView, overlayView: UIView) {
        self.webView = webView
        self.overlayView = overlayView
        
        // Add overlay first (bottom)
        addSubview(overlayView)
        // Add webview second (top)
        addSubview(webView)
        
        // Make sure webview stays on top
        bringSubviewToFront(webView)
        
        // Ensure both views can receive touches
        webView.isUserInteractionEnabled = true
        overlayView.isUserInteractionEnabled = true
    }
    
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        return true
    }
    
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let webView = webView,
              let overlayView = overlayView else {
            return super.hitTest(point, with: event)
        }
        
        // Convert point to global coordinates for labeled frame checking
        let globalPoint = convert(point, to: nil)
        
        print("TouchInterceptView - Hit test at global point: \(globalPoint)")
        print("Current labeled frames: \(labeledFrames.map { "\($0.label): \($0.frame)" }.joined(separator: ", "))")
        
        // Convert point for both views
        let webViewPoint = convert(point, to: webView)
        let overlayPoint = convert(point, to: overlayView)
        
        // First check if the point is inside any labeled frame
        for labeledFrame in labeledFrames {
            if labeledFrame.frame.contains(globalPoint) {
                print("Hit labeled frame: \(labeledFrame.label)")
                // If it's in a labeled frame, let the overlay handle it
                if overlayView.point(inside: overlayPoint, with: event),
                   let overlayHitView = overlayView.hitTest(overlayPoint, with: event) {
                    return overlayHitView
                }
            }
        }
        
        // If not in a labeled frame, let webview try first
        if webView.point(inside: webViewPoint, with: event),
           let webViewHitView = webView.hitTest(webViewPoint, with: event) {
            return webViewHitView
        }
        
        // Finally check if overlay wants to handle the touch
        if overlayView.point(inside: overlayPoint, with: event),
           let overlayHitView = overlayView.hitTest(overlayPoint, with: event) {
            return overlayHitView
        }
        
        return super.hitTest(point, with: event)
    }
    
    func updateLabeledFrames(_ frames: [ViewFramePreferenceData]) {
        print("TouchInterceptView - Updating labeled frames:")
        print("Number of frames: \(frames.count)")
        frames.forEach { frame in
            print("Label: \(frame.label), Frame: \(frame.frame)")
        }
        self.labeledFrames = frames
    }
}
