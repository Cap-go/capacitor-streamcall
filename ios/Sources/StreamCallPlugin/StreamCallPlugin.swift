import Foundation
import Capacitor
import StreamVideo
import StreamVideoSwiftUI
import SwiftUI
import Combine
import WebKit

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(StreamCallPlugin)
public class StreamCallPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "StreamCallPlugin"
    public let jsName = "StreamCall"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "loginMagicToken", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "login", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "logout", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "call", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "endCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setMicrophoneEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setCameraEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "acceptCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isCameraEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCallStatus", returnType: CAPPluginReturnPromise)
    ]

    private enum State {
        case notInitialized
        case initializing
        case initialized
    }
    private var apiKey: String?
    private var state: State = .notInitialized
    private var currentToken: String?
    private var tokenWaitSemaphore: DispatchSemaphore?

    private var overlayView: UIView?
    private var hostingController: UIHostingController<CallOverlayView>?
    private var tokenSubscription: AnyCancellable?
    private var activeCallSubscription: AnyCancellable?
    private var lastVoIPToken: String?

    private var streamVideo: StreamVideo?

    // Store current call info for getCallStatus
    private var currentCallId: String = ""
    private var currentCallState: String = ""
    private var hasNotifiedCallJoined: Bool = false

    @Injected(\.callKitAdapter) var callKitAdapter
    @Injected(\.callKitPushNotificationAdapter) var callKitPushNotificationAdapter
    @Injected(\.callKitService) var callKitService
    private var webviewDelegate: WebviewNavigationDelegate?

    // Declare as optional and initialize in load() method
    private var callViewModel: CallViewModel?

    // Helper method to update call status and notify listeners
    private func updateCallStatusAndNotify(callId: String, state: String, userId: String? = nil, reason: String? = nil) {
        // Update stored call info
        currentCallId = callId
        currentCallState = state

        // Create data dictionary with only the fields in the CallEvent interface
        var data: [String: Any] = [
            "callId": callId,
            "state": state
        ]

        if let userId = userId {
            data["userId"] = userId
        }

        if let reason = reason {
            data["reason"] = reason
        }

        // Notify listeners
        notifyListeners("callEvent", data: data)
    }

    override public func load() {
        // Read API key from Info.plist
        if let apiKey = Bundle.main.object(forInfoDictionaryKey: "CAPACITOR_STREAM_VIDEO_APIKEY") as? String {
            self.apiKey = apiKey
        }
        if self.apiKey == nil {
            fatalError("Cannot get apikey")
        }
        
        // Check if we have a logged in user for handling incoming calls
        if let credentials = SecureUserRepository.shared.loadCurrentUser() {
            print("Loading user for StreamCallPlugin: \(credentials.user.name)")
            DispatchQueue.global(qos: .userInitiated).async {
                self.initializeStreamVideo()
            }
        }

        // Create and set the navigation delegate
        self.webviewDelegate = WebviewNavigationDelegate(
            wrappedDelegate: self.webView?.navigationDelegate,
            onSetupOverlay: { [weak self] in
                guard let self = self else { return }
                print("Attempting to setup call view")

                self.setupViews()
            }
        )

        self.webView?.navigationDelegate = self.webviewDelegate
    }

    private func requireInitialized() throws {
        guard state == .initialized else {
            throw NSError(domain: "StreamCallPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "StreamVideo not initialized"])
        }
    }
    @objc func loginMagicToken(_ call: CAPPluginCall) {
        guard let token = call.getString("token") else {
            call.reject("Missing token parameter")
            return
        }

        print("loginMagicToken received token")
        currentToken = token
        tokenWaitSemaphore?.signal()
        call.resolve()
    }

    private func setupTokenSubscription() {
        // Cancel existing subscription if any
        tokenSubscription?.cancel()

        // Create new subscription
        tokenSubscription = callKitPushNotificationAdapter.$deviceToken.sink { [weak self] (updatedDeviceToken: String) in
            guard let self = self else { return }
            Task {
                do {
                    print("Setting up token subscription")
                    try self.requireInitialized()
                    if let lastVoIPToken = self.lastVoIPToken, !lastVoIPToken.isEmpty {
                        print("Deleting device: \(lastVoIPToken)")
                        try await self.streamVideo?.deleteDevice(id: lastVoIPToken)
                    }
                    if !updatedDeviceToken.isEmpty {
                        print("Setting voip device: \(updatedDeviceToken)")
                        try await self.streamVideo?.setVoipDevice(id: updatedDeviceToken)
                        // Save the token to our secure storage
                        print("Saving voip token: \(updatedDeviceToken)")
                        SecureUserRepository.shared.save(voipPushToken: updatedDeviceToken)
                    }
                    self.lastVoIPToken = updatedDeviceToken
                } catch {
                    log.error("Error updating VOIP token: \(String(describing: error))")
                }
            }
        }
    }

    private func setupActiveCallSubscription() {
        // Ensure this method is called on the main thread and properly establishes the subscription
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // Cancel existing subscription if any
            self.activeCallSubscription?.cancel()
            self.activeCallSubscription = nil
            
            // Verify callViewModel exists
            guard let callViewModel = self.callViewModel, let streamVideo = self.streamVideo else {
                print("Warning: setupActiveCallSubscription called but callViewModel or streamVideo is nil")
                // Schedule a retry after a short delay if callViewModel is nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                    self?.setupActiveCallSubscription()
                }
                return
            }
            
            print("Setting up active call subscription")
            
            // Create a strong reference to callViewModel to ensure it's not deallocated
            // while the subscription is active
            let viewModel = callViewModel
            
            // Subscribe to streamVideo.state.$activeCall to handle CallKit integration
            let callPublisher = streamVideo.state.$activeCall
                .receive(on: DispatchQueue.main)
                .sink { [weak self, weak viewModel] activeCall in
                    guard let self = self, let viewModel = viewModel else { return }
                    
                    print("Active call update from streamVideo: \(String(describing: activeCall?.cId))")
                    
                    if let activeCall = activeCall {
                        // Sync callViewModel with activeCall from streamVideo state
                        // This ensures CallKit integration works properly
                        viewModel.setActiveCall(activeCall)
                    }
                }
            
            // Store the subscription for activeCall updates
            self.activeCallSubscription = callPublisher
            
            // Additionally, subscribe to callingState for other call state changes
            let statePublisher = viewModel.$callingState
                .receive(on: DispatchQueue.main)
                .sink { [weak self, weak viewModel] newState in
                    guard let self = self, let viewModel = viewModel else {
                        print("Warning: Call state update received but self or viewModel is nil")
                        return
                    }
                    
                    do {
                        try self.requireInitialized()
                        print("Call State Update: \(newState)")
                        
                        if newState == .inCall {
                            print("- In call state detected")
                            print("- All participants: \(String(describing: viewModel.participants))")
                            
                            // Create/update overlay and make visible when there's an active call
                            self.createCallOverlayView()
                            
                            // Notify that a call has started - but only if we haven't notified for this call yet
                            if let callId = viewModel.call?.cId, !self.hasNotifiedCallJoined || callId != self.currentCallId {
                                print("Notifying call joined: \(callId)")
                                self.updateCallStatusAndNotify(callId: callId, state: "joined")
                                self.hasNotifiedCallJoined = true
                            }
                        } else if case .incoming(let incomingCall) = newState {
                            self.updateCallStatusAndNotify(callId: incomingCall.id, state: "ringing")
                        } else if newState == .idle && self.streamVideo?.state.activeCall == nil {
                            // Get the call ID that was active before the state changed
                            let endingCallId = viewModel.call?.cId
                            print("Call ending: \(String(describing: endingCallId))")
                            
                            // Notify that call has ended - use the properly tracked call ID
                            self.updateCallStatusAndNotify(callId: endingCallId ?? "", state: "left")
                            
                            // Reset notification flag when call ends
                            self.hasNotifiedCallJoined = false
                            
                            // Remove the call overlay view when not in a call
                            self.ensureViewRemoved()
                        }
                    } catch {
                        log.error("Error handling call state update: \(String(describing: error))")
                    }
                }
            
            // Combine both publishers
            self.activeCallSubscription = AnyCancellable {
                callPublisher.cancel()
                statePublisher.cancel()
            }
            
            print("Active call subscription setup completed")
            
            // Schedule a periodic check to ensure subscription is active
            self.scheduleSubscriptionCheck()
        }
    }
    
    // Add a new method to periodically check and restore the subscription if needed
    private func scheduleSubscriptionCheck() {
        // Create a timer that checks the subscription every 5 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
            guard let self = self else { return }
            
            // Check if we're in a state where we need the subscription but it's not active
            if self.state == .initialized && self.activeCallSubscription == nil && self.callViewModel != nil {
                print("Subscription check: Restoring lost activeCallSubscription")
                self.setupActiveCallSubscription()
            } else {
                // Schedule the next check
                self.scheduleSubscriptionCheck()
            }
        }
    }

    @objc func login(_ call: CAPPluginCall) {
        guard let token = call.getString("token"),
              let userId = call.getString("userId"),
              let name = call.getString("name") else {
            call.reject("Missing required parameters")
            return
        }

        let imageURL = call.getString("imageURL")
        let user = User(
            id: userId,
            name: name,
            imageURL: imageURL.flatMap { URL(string: $0) },
            customData: [:]
        )

        let credentials = UserCredentials(user: user, tokenValue: token)
        SecureUserRepository.shared.save(user: credentials)
        // Initialize Stream Video with new credentials
        initializeStreamVideo()

        if state != .initialized {
            call.reject("Failed to initialize StreamVideo")
            return
        }

        // Update the CallOverlayView with new StreamVideo instance
        Task { @MainActor in
            // self.overlayViewModel?.updateStreamVideo(self.streamVideo)
        }

        call.resolve([
            "success": true
        ])
    }

    @objc func logout(_ call: CAPPluginCall) {
        // Remove VOIP token from repository
        SecureUserRepository.shared.save(voipPushToken: nil)

        // Try to delete the device from Stream if we have the last token
        if let lastVoIPToken = lastVoIPToken {
            Task {
                do {
                    try await streamVideo?.deleteDevice(id: lastVoIPToken)
                } catch {
                    log.error("Error deleting device during logout: \(String(describing: error))")
                }
            }
        }

        // Cancel subscriptions
        tokenSubscription?.cancel()
        tokenSubscription = nil
        activeCallSubscription?.cancel()
        activeCallSubscription = nil
        lastVoIPToken = nil

        SecureUserRepository.shared.removeCurrentUser()

        // Update the CallOverlayView with nil StreamVideo instance
        Task { @MainActor in
            // self.overlayViewModel?.updateCall(nil)
            // self.overlayViewModel?.updateStreamVideo(nil)
            self.overlayView?.isHidden = true
            self.webView?.isOpaque = true
        }

        call.resolve([
            "success": true
        ])
    }

    @objc func isCameraEnabled(_ call: CAPPluginCall) {
        do {
            try requireInitialized()
        } catch {
            call.reject("SDK not initialized")
        }

        if let activeCall = streamVideo?.state.activeCall {
            call.resolve([
                "enabled": activeCall.camera.status == .enabled
            ])
        } else {
            call.reject("No active call")
        }
    }

    @objc func call(_ call: CAPPluginCall) {
        guard let members = call.getArray("userIds", String.self) else {
            call.reject("Missing required parameter: userIds (array of user IDs)")
            return
        }

        if members.isEmpty {
            call.reject("userIds array cannot be empty")
            return
        }

        // Initialize if needed
        if state == .notInitialized {
            initializeStreamVideo()
            if state != .initialized {
                call.reject("Failed to initialize StreamVideo")
                return
            }
        }

        do {
            try requireInitialized()

            let callType = call.getString("type") ?? "default"
            let shouldRing = call.getBool("ring") ?? true
            let team = call.getString("team")
            let video = call.getBool("video") ?? false

            // Generate a unique call ID
            let callId = UUID().uuidString

            Task {
                do {
                    print("Creating call:")
                    print("- Call ID: \(callId)")
                    print("- Call Type: \(callType)")
                    print("- Users: \(members)")
                    print("- Should Ring: \(shouldRing)")
                    print("- Team: \(team)")
                    print("- Video: \(video)")



                    // Update the CallOverlayView with the active call
                    // Create the call object
await self.callViewModel?.startCall(
    callType: callType,
    callId: callId,
    members: members.map { Member(userId: $0, role: nil, customData: [:], updatedAt: nil) },
    ring: shouldRing,
    video: video
)
                    
                    
                    // Update UI on main thread
                    await MainActor.run {
                        // self.overlayViewModel?.updateCall(streamCall)
                        self.overlayView?.isHidden = false
                        self.webView?.isOpaque = false
                    }

                    call.resolve([
                        "success": true
                    ])  

                } catch {
                    log.error("Error making call: \(String(describing: error))")
                    call.reject("Failed to make call: \(error.localizedDescription)")
                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
        }
    }

    @objc func endCall(_ call: CAPPluginCall) {
        do {
            try requireInitialized()

            Task {
                if let activeCall = streamVideo?.state.activeCall {
                    activeCall.leave()

                    // Update view state instead of cleaning up
                    await MainActor.run {
                        // self.overlayViewModel?.updateCall(nil)
                        self.overlayView?.isHidden = true
                        self.webView?.isOpaque = true
                    }

                    call.resolve([
                        "success": true
                    ])
                } else {
                    call.reject("No active call to end")
                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
        }
    }

    @objc func setMicrophoneEnabled(_ call: CAPPluginCall) {
        guard let enabled = call.getBool("enabled") else {
            call.reject("Missing required parameter: enabled")
            return
        }

        do {
            try requireInitialized()

            Task {
                do {
                    if let activeCall = streamVideo?.state.activeCall {
                        if enabled {
                            try await activeCall.microphone.enable()
                        } else {
                            try await activeCall.microphone.disable()
                        }
                        call.resolve([
                            "success": true
                        ])
                    } else {
                        call.reject("No active call")
                    }
                } catch {
                    log.error("Error setting microphone: \(String(describing: error))")
                    call.reject("Failed to set microphone: \(error.localizedDescription)")
                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
        }
    }

    @objc func setCameraEnabled(_ call: CAPPluginCall) {
        guard let enabled = call.getBool("enabled") else {
            call.reject("Missing required parameter: enabled")
            return
        }

        do {
            try requireInitialized()

            Task {
                do {
                    if let activeCall = streamVideo?.state.activeCall {
                        if enabled {
                            try await activeCall.camera.enable()
                        } else {
                            try await activeCall.camera.disable()
                        }
                        call.resolve([
                            "success": true
                        ])
                    } else {
                        call.reject("No active call")
                    }
                } catch {
                    log.error("Error setting camera: \(String(describing: error))")
                    call.reject("Failed to set camera: \(error.localizedDescription)")
                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
        }
    }

    @objc func acceptCall(_ call: CAPPluginCall) {
        do {
            try requireInitialized()

            Task {
                do {

                    // Get the call object for the given ID
                    let streamCall = streamVideo?.state.ringingCall
                    if streamCall == nil {
                        call.reject("Failed to accept call as there is no ringing call")
                        return
                    }

                    // Join the call
                    print("Accepting and joining call \(streamCall!.cId)...")
                    guard case .incoming(let incomingCall) = await self.callViewModel?.callingState else {
                        call.reject("Failed to accept call as there is no call ID")
                        return
                    }

                    await self.callViewModel?.acceptCall(callType: incomingCall.type, callId: incomingCall.id)
                    print("Successfully joined call")

                    // Update the CallOverlayView with the active call
                    await MainActor.run {
                        // self.overlayViewModel?.updateCall(streamCall)
                        self.overlayView?.isHidden = false
                        self.webView?.isOpaque = false
                    }

                    call.resolve([
                        "success": true
                    ])
                } catch {
                    log.error("Error accepting call: \(String(describing: error))")
                    call.reject("Failed to accept call: \(error.localizedDescription)")
                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
        }
    }

    private func initializeStreamVideo() {
        if (state == .initialized) {
            print("initializeStreamVideo already initialized")
            // Try to get user credentials from repository
            guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser() else {
                print("Save credentials not found, skipping initialization")
                return
            }
            if (savedCredentials.user.id == streamVideo?.user.id) {
                print("Skipping initializeStreamVideo as user is already logged in")
                return
            }
        } else if (state == .initializing) {
            print("initializeStreamVideo rejected - already initializing")
            return
        }
        state = .initializing

        // Try to get user credentials from repository
        guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser(),
              let apiKey = self.apiKey else {
            print("No saved credentials or API key found, skipping initialization")
            state = .notInitialized
            return
        }
        print("Initializing with saved credentials for user: \(savedCredentials.user.name)")

        LogConfig.level = .debug
        self.streamVideo = StreamVideo(
            apiKey: apiKey,
            user: savedCredentials.user,
            token: UserToken(stringLiteral: savedCredentials.tokenValue),
            tokenProvider: {completion in
                guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser() else {
                    print("No saved credentials or API key found, cannot refresh token")
                    
                    completion(.failure(NSError(domain: "No saved credentials or API key found, cannot refresh token", code: 0, userInfo: nil)))
                    return
                }
                completion(.success(UserToken(stringLiteral: savedCredentials.tokenValue)))
            }
        )
        
        if (self.callViewModel == nil) {
            // Initialize on main thread with proper MainActor isolation
            DispatchQueue.main.async {
                Task { @MainActor in
                    self.callViewModel = CallViewModel(participantsLayout: .grid)
                    self.callViewModel?.participantAutoLeavePolicy = LastParticipantAutoLeavePolicy()
                    
                    // Setup subscriptions for new StreamVideo instance
                    self.setupActiveCallSubscription()
                }
            }
        }

        state = .initialized
        callKitAdapter.streamVideo = self.streamVideo
        callKitAdapter.availabilityPolicy = .always
        
        setupTokenSubscription()

        // Register for incoming calls
        callKitAdapter.registerForIncomingCalls()
        callKitService.supportsVideo = true
    }

    private func setupViews() {
        guard let webView = self.webView, let parent = webView.superview else { return }
        
        // Create the touch intercept view as an overlay for touch passthrough
        let touchInterceptView = TouchInterceptView(frame: parent.bounds)
        touchInterceptView.translatesAutoresizingMaskIntoConstraints = false
        touchInterceptView.backgroundColor = .clear
        touchInterceptView.isOpaque = false
        
        // Create SwiftUI view with view model if not already created
        if self.overlayView == nil, let callViewModel = self.callViewModel {
            let hostingController = UIHostingController(rootView: CallOverlayView(viewModel: callViewModel))
            hostingController.view.backgroundColor = .clear
            hostingController.view.translatesAutoresizingMaskIntoConstraints = false
            hostingController.view.isHidden = true // Initially hidden until a call is active
            
            self.hostingController = hostingController
            self.overlayView = hostingController.view
            
            if let overlayView = self.overlayView {
                // Insert overlay view below webview
                parent.insertSubview(overlayView, belowSubview: webView)
                
                // Setup constraints for overlayView
                let safeGuide = parent.safeAreaLayoutGuide
                NSLayoutConstraint.activate([
                    overlayView.topAnchor.constraint(equalTo: safeGuide.topAnchor),
                    overlayView.bottomAnchor.constraint(equalTo: safeGuide.bottomAnchor),
                    overlayView.leadingAnchor.constraint(equalTo: safeGuide.leadingAnchor),
                    overlayView.trailingAnchor.constraint(equalTo: safeGuide.trailingAnchor)
                ])
            }
        }
        
        // Setup touch intercept view with references to webview and overlay
        if let overlayView = self.overlayView {
            touchInterceptView.setupWithWebView(webView, overlayView: overlayView)
            // Insert touchInterceptView above webView
            parent.insertSubview(touchInterceptView, aboveSubview: webView)
        } else {
            // If overlayView is not present, just add on top of webView
            touchInterceptView.setupWithWebView(webView, overlayView: webView)
            parent.insertSubview(touchInterceptView, aboveSubview: webView)
        }
        
        // Setup constraints for touchInterceptView to cover the entire parent
        NSLayoutConstraint.activate([
            touchInterceptView.topAnchor.constraint(equalTo: parent.topAnchor),
            touchInterceptView.bottomAnchor.constraint(equalTo: parent.bottomAnchor),
            touchInterceptView.leadingAnchor.constraint(equalTo: parent.leadingAnchor),
            touchInterceptView.trailingAnchor.constraint(equalTo: parent.trailingAnchor)
        ])
    }
    
    private func createCallOverlayView() {
        guard let webView = self.webView,
              let parent = webView.superview,
              let callOverlayView = self.callViewModel else { return }
        
        // Check if we already have an overlay view - do nothing if it exists
        if let existingOverlayView = self.overlayView, existingOverlayView.superview != nil {
            print("Call overlay view already exists, making it visible")
            existingOverlayView.isHidden = false
            // Make webview transparent to see StreamCall UI underneath
            webView.isOpaque = false
            webView.backgroundColor = .clear
            webView.scrollView.backgroundColor = .clear
            return
        }
        
        print("Creating new call overlay view")
        
        // First, create the overlay view
        let overlayView = UIHostingController(rootView: CallOverlayView(viewModel: callOverlayView))
        overlayView.view.translatesAutoresizingMaskIntoConstraints = false
        overlayView.view.isHidden = false // Make visible during a call
        
        // Insert the overlay view below the webView in the view hierarchy
        parent.insertSubview(overlayView.view, belowSubview: webView)
        
        // Set constraints to fill the parent's safe area
        let safeGuide = parent.safeAreaLayoutGuide
        
        NSLayoutConstraint.activate([
            overlayView.view.topAnchor.constraint(equalTo: safeGuide.topAnchor),
            overlayView.view.bottomAnchor.constraint(equalTo: safeGuide.bottomAnchor),
            overlayView.view.leadingAnchor.constraint(equalTo: safeGuide.leadingAnchor),
            overlayView.view.trailingAnchor.constraint(equalTo: safeGuide.trailingAnchor)
        ])
        
        // Make webview transparent to see StreamCall UI underneath
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        
        // Store reference to the hosting controller
        self.hostingController = overlayView
        self.overlayView = overlayView.view
        
        // Ensure touch intercept view is on top
        if let touchInterceptView = parent.subviews.first(where: { $0 is TouchInterceptView }) {
            parent.bringSubviewToFront(touchInterceptView)
        } else {
            // Create touch intercept view if not already created
            let touchInterceptView = TouchInterceptView(frame: parent.bounds)
            touchInterceptView.translatesAutoresizingMaskIntoConstraints = false
            touchInterceptView.backgroundColor = .clear
            touchInterceptView.isOpaque = false
            touchInterceptView.setupWithWebView(webView, overlayView: overlayView.view)
            parent.addSubview(touchInterceptView)
            
            NSLayoutConstraint.activate([
                touchInterceptView.topAnchor.constraint(equalTo: parent.topAnchor),
                touchInterceptView.bottomAnchor.constraint(equalTo: parent.bottomAnchor),
                touchInterceptView.leadingAnchor.constraint(equalTo: parent.leadingAnchor),
                touchInterceptView.trailingAnchor.constraint(equalTo: parent.trailingAnchor)
            ])
        }
    }

    private func ensureViewRemoved() {
        // Check if we have an overlay view
        if let existingOverlayView = self.overlayView {
            print("Hiding call overlay view")
            
            // Hide the view instead of removing it
            existingOverlayView.isHidden = true
            
            // Reset opacity for webView
            self.webView?.isOpaque = true
            self.webView?.backgroundColor = nil
            self.webView?.scrollView.backgroundColor = nil
        } else {
            print("No call overlay view to hide")
        }
    }

    @objc func getCallStatus(_ call: CAPPluginCall) {
        // Use stored state rather than accessing SDK state directly
        if currentCallId.isEmpty || currentCallState == "left" {
            call.reject("Not in a call")
            return
        }

        call.resolve([
            "callId": currentCallId,
            "state": currentCallState
        ])
    }
}
