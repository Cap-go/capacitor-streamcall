import Foundation
import Capacitor
import StreamVideo
import StreamVideoSwiftUI
import SwiftUI
import Combine

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
        CAPPluginMethod(name: "setCameraEnabled", returnType: CAPPluginReturnPromise)
    ]
    
    private enum State {
        case notInitialized
        case initializing
        case initialized
    }
    private var apiKey: String?
    private var state: State = .notInitialized
    private static let tokenRefreshQueue = DispatchQueue(label: "stream.call.token.refresh")
    private static let tokenRefreshSemaphore = DispatchSemaphore(value: 1)
    private var currentToken: String?
    private var tokenWaitSemaphore: DispatchSemaphore?
    
    private var overlayView: UIView?
    private var hostingController: UIHostingController<CallOverlayView>?
    private var overlayViewModel: CallOverlayViewModel?
    private var tokenSubscription: AnyCancellable?
    private var activeCallSubscription: AnyCancellable?
    private var lastVoIPToken: String?
    
    @Injected(\.streamVideo) var streamVideo {
        willSet {
            // Clean up existing subscriptions when streamVideo changes
            tokenSubscription?.cancel()
            activeCallSubscription?.cancel()
        }
    }
    
    @Injected(\.callKitAdapter) var callKitAdapter
    @Injected(\.callKitPushNotificationAdapter) var callKitPushNotificationAdapter
    
    private var refreshTokenURL: String?
    private var refreshTokenHeaders: [String: String]?
    
    override public func load() {
        // Check if we have a logged in user for handling incoming calls
        if let credentials = SecureUserRepository.shared.loadCurrentUser() {
            print("Loading user for StreamCallPlugin: \(credentials.user.name)")
            DispatchQueue.global(qos: .userInitiated).async {
                self.initializeStreamVideo()
            }
        }
    }
    
    private func cleanupStreamVideo() {
        // Cancel subscriptions
        tokenSubscription?.cancel()
        tokenSubscription = nil
        activeCallSubscription?.cancel()
        activeCallSubscription = nil
        lastVoIPToken = nil
        
        // Cleanup UI
        Task { @MainActor in
            self.overlayViewModel?.updateCall(nil)
            self.overlayViewModel?.updateStreamVideo(nil)
            self.overlayView?.removeFromSuperview()
            self.overlayView = nil
            self.hostingController = nil
            self.overlayViewModel = nil
            
            // Reset webview
            self.webView?.isOpaque = true
            self.webView?.backgroundColor = .white
            self.webView?.scrollView.backgroundColor = .white
        }
        
        state = .notInitialized
    }
    
    private func requireInitialized() throws {
        guard state == .initialized else {
            throw NSError(domain: "StreamCallPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "StreamVideo not initialized"])
        }
    }
    
    private func refreshToken() throws -> UserToken {
        // Acquire the semaphore in a thread-safe way
        StreamCallPlugin.tokenRefreshQueue.sync {
            StreamCallPlugin.tokenRefreshSemaphore.wait()
        }
        
        defer {
            // Always release the semaphore when we're done, even if we throw an error
            StreamCallPlugin.tokenRefreshSemaphore.signal()
        }
        
        // Clear current token
        currentToken = nil
        
        // Create a local semaphore for waiting on the token
        let localSemaphore = DispatchSemaphore(value: 0)
        tokenWaitSemaphore = localSemaphore
        
        // Capture webView before async context
        guard let webView = self.webView else {
            print("WebView not available")
            tokenWaitSemaphore = nil
            throw NSError(domain: "StreamCallPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "WebView not available"])
        }
        
        guard let refreshURL = self.refreshTokenURL else {
            print("Refresh URL not configured")
            tokenWaitSemaphore = nil
            throw NSError(domain: "StreamCallPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "Refresh URL not configured"])
        }
        
        let headersJSON = (self.refreshTokenHeaders ?? [:]).reduce(into: "") { result, pair in
            result += "\n'\(pair.key)': '\(pair.value)',"
        }
        
        let script = """
            (function() {
                console.log('Starting token refresh...');
                fetch('\(refreshURL)', {
                    headers: {
                        \(headersJSON)
                    }
                })
                .then(response => {
                    console.log('Got response:', response.status);
                    return response.json();
                })
                .then(data => {
                    console.log('Got data, token length:', data.token?.length);
                    const tokenA = data.token;
                    window.Capacitor.Plugins.StreamCall.loginMagicToken({
                        token: tokenA
                    });
                })
                .catch(error => {
                    console.error('Token refresh error:', error);
                });
            })();
        """
        
        if Thread.isMainThread {
            print("Executing script on main thread")
            webView.evaluateJavaScript(script) { result, error in
                if let error = error {
                    print("JavaScript evaluation error: \(error)")
                } else {
                    print("JavaScript executed successfully: \(String(describing: result))")
                }
            }
        } else {
            print("Executing script from background thread")
            DispatchQueue.main.sync {
                webView.evaluateJavaScript(script) { result, error in
                    if let error = error {
                        print("JavaScript evaluation error: \(error)")
                    } else {
                        print("JavaScript executed successfully: \(String(describing: result))")
                    }
                }
            }
        }
        
        // Set up a timeout
        let timeoutQueue = DispatchQueue.global()
        let timeoutWork = DispatchWorkItem {
            print("Token refresh timed out")
            self.tokenWaitSemaphore?.signal()
            self.tokenWaitSemaphore = nil
        }
        timeoutQueue.asyncAfter(deadline: .now() + 10.0, execute: timeoutWork) // 10 second timeout
        
        // Wait for token to be set via loginMagicToken or timeout
        localSemaphore.wait()
        timeoutWork.cancel()
        tokenWaitSemaphore = nil
        
        guard let token = currentToken else {
            print("Failed to get token")
            throw NSError(domain: "StreamCallPlugin", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to get token or timeout occurred"])
        }
        
        // Save the token
        SecureUserRepository.shared.save(token: token)
        
        print("Got the token!!!")
        return UserToken(stringLiteral: token)
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
                    try self.requireInitialized()
                    if let lastVoIPToken = self.lastVoIPToken, !lastVoIPToken.isEmpty {
                        try await self.streamVideo.deleteDevice(id: lastVoIPToken)
                    }
                    if !updatedDeviceToken.isEmpty {
                        try await self.streamVideo.setVoipDevice(id: updatedDeviceToken)
                        // Save the token to our secure storage
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
        // Cancel existing subscription if any
        activeCallSubscription?.cancel()
        
        // Create new subscription
        activeCallSubscription = streamVideo.state.$activeCall.sink { [weak self] newState in
            guard let self = self else { return }
            Task { @MainActor in
                do {
                    try self.requireInitialized()
                    print("Call State Update:")
                    print("- Call is nil: \(newState == nil)")
                    if let state = newState?.state {
                        print("- state: \(state)")
                        print("- Session ID: \(state.sessionId)")
                        print("- All participants: \(String(describing: state.participants))")
                        print("- Remote participants: \(String(describing: state.remoteParticipants))")
                        
                        // Notify that a call has started
                        self.notifyListeners("callEvent", data: [
                            "callId": newState?.cId ?? "",
                            "state": "joined"
                        ])
                    } else {
                        // If newState is nil, it means the call has ended
                        self.notifyListeners("callEvent", data: [
                            "callId": newState?.cId ?? "",
                            "state": "left"
                        ])
                    }
                    
                    self.overlayViewModel?.updateCall(newState)
                    self.overlayView?.isHidden = newState == nil
                } catch {
                    log.error("Error handling call state update: \(String(describing: error))")
                }
            }
        }
    }
    
    @objc func login(_ call: CAPPluginCall) {
        guard let token = call.getString("token"),
              let userId = call.getString("userId"),
              let name = call.getString("name"),
              let apiKey = call.getString("apiKey") else {
            call.reject("Missing required parameters")
            return
        }
        
        let imageURL = call.getString("imageURL")
        let refreshTokenConfig = call.getObject("refreshToken")
        let refreshTokenURL = refreshTokenConfig?["url"] as? String
        let refreshTokenHeaders = refreshTokenConfig?["headers"] as? [String: String]
        
        let user = User(
            id: userId,
            name: name,
            imageURL: imageURL.flatMap { URL(string: $0) },
            customData: [:]
        )
        
        let credentials = UserCredentials(user: user, tokenValue: token)
        SecureUserRepository.shared.save(user: credentials)
        
        // Store API key and refresh config for later use
        self.apiKey = apiKey
        self.refreshTokenURL = refreshTokenURL
        self.refreshTokenHeaders = refreshTokenHeaders
        
        // Initialize Stream Video with new credentials
        initializeStreamVideo()
        
        if state != .initialized {
            call.reject("Failed to initialize StreamVideo")
            return
        }
        
        // Update the CallOverlayView with new StreamVideo instance
        Task { @MainActor in
            self.overlayViewModel?.updateStreamVideo(self.streamVideo)
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
                    try await streamVideo.deleteDevice(id: lastVoIPToken)
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
            self.overlayViewModel?.updateStreamVideo(nil)
        }
        
        call.resolve([
            "success": true
        ])
    }
    
    @objc func call(_ call: CAPPluginCall) {
        guard let userId = call.getString("userId") else {
            call.reject("Missing required parameter: userId")
            return
        }
        
        // Initialize if needed
        if state == .notInitialized {
            guard let credentials = SecureUserRepository.shared.loadCurrentUser() else {
                call.reject("No user credentials found")
                return
            }
            initializeStreamVideo()
            if state != .initialized {
                call.reject("Failed to initialize StreamVideo")
                return
            }
        }
        
        // Setup UI components if needed
        if overlayView == nil {
            Task { @MainActor in
                // Make webview transparent
                self.webView?.isOpaque = false
                self.webView?.backgroundColor = .clear
                self.webView?.scrollView.backgroundColor = .clear
                
                // Create SwiftUI view with view model
                let (hostingController, viewModel) = CallOverlayView.create(streamVideo: self.streamVideo)
                hostingController.view.backgroundColor = .clear
                
                self.hostingController = hostingController
                self.overlayViewModel = viewModel
                self.overlayView = hostingController.view
                
                if let overlayView = self.overlayView {
                    overlayView.isHidden = true
                    self.webView?.superview?.addSubview(overlayView)
                    self.webView?.superview?.bringSubviewToFront(self.webView!)
                    
                    overlayView.translatesAutoresizingMaskIntoConstraints = false
                    NSLayoutConstraint.activate([
                        overlayView.topAnchor.constraint(equalTo: overlayView.superview!.topAnchor),
                        overlayView.bottomAnchor.constraint(equalTo: overlayView.superview!.bottomAnchor),
                        overlayView.leadingAnchor.constraint(equalTo: overlayView.superview!.leadingAnchor),
                        overlayView.trailingAnchor.constraint(equalTo: overlayView.superview!.trailingAnchor)
                    ])
                }
            }
        }
        
        do {
            try requireInitialized()
            
            let callType = call.getString("type") ?? "default"
            let shouldRing = call.getBool("ring") ?? true
            
            // Generate a unique call ID
            let callId = UUID().uuidString
            
            Task {
                do {
                    print("Creating call:")
                    print("- Call ID: \(callId)")
                    print("- Call Type: \(callType)")
                    print("- User ID: \(userId)")
                    print("- Should Ring: \(shouldRing)")
                    
                    // Create the call object
                    let streamCall = streamVideo.call(callType: callType, callId: callId)
                    
                    // Start the call with the member
                    print("Creating call with member...")
                    try await streamCall.create(
                        memberIds: [userId],
                        custom: [:],
                        ring: shouldRing
                    )
                    
                    // Join the call
                    print("Joining call...")
                    try await streamCall.join(create: false)
                    print("Successfully joined call")
                    
                    // Update the CallOverlayView with the active call
                    await MainActor.run {
                        self.overlayViewModel?.updateCall(streamCall)
                        self.overlayView?.isHidden = false
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
                do {
                    if let activeCall = streamVideo.state.activeCall {
                        try await activeCall.leave()
                        cleanupStreamVideo()
                        call.resolve([
                            "success": true
                        ])
                    } else {
                        call.reject("No active call to end")
                    }
                } catch {
                    log.error("Error ending call: \(String(describing: error))")
                    call.reject("Failed to end call: \(error.localizedDescription)")
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
                    if let activeCall = streamVideo.state.activeCall {
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
                    if let activeCall = streamVideo.state.activeCall {
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
    
    private func initializeStreamVideo() {
        state = .initializing
        
        // Try to get user credentials from repository
        guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser(),
              let apiKey = self.apiKey else {
            print("No saved credentials or API key found, skipping initialization")
            state = .notInitialized
            return
        }
        print("Initializing with saved credentials for user: \(savedCredentials.user.name)")
        
        // Create a local reference to refreshToken to avoid capturing self
        let refreshTokenFn = self.refreshToken
        
        self.streamVideo = StreamVideo(
            apiKey: apiKey,
            user: savedCredentials.user,
            token: UserToken(stringLiteral: savedCredentials.tokenValue),
            tokenProvider: { result in
                print("attempt to refresh")
                DispatchQueue.global().async {
                    do {
                        let newToken = try refreshTokenFn()
                        print("Refresh successful")
                        result(.success(newToken))
                    } catch {
                        print("Refresh fail")
                        result(.failure(error))
                    }
                }
            }
        )
        
        state = .initialized
        callKitAdapter.streamVideo = self.streamVideo
        
        // Setup subscriptions for new StreamVideo instance
        setupActiveCallSubscription()
        setupTokenSubscription()
        
        // Register for incoming calls
        callKitAdapter.registerForIncomingCalls()
    }
}
