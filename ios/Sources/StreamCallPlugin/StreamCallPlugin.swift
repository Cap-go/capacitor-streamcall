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
    private var touchInterceptView: TouchInterceptView?

    private var streamVideo: StreamVideo?

    // Track the current active call ID
    private var currentActiveCallId: String?

    // Store current call info for getCallStatus
    private var currentCallId: String = ""
    private var currentCallState: String = ""

    @Injected(\.callKitAdapter) var callKitAdapter
    @Injected(\.callKitPushNotificationAdapter) var callKitPushNotificationAdapter
    private var webviewDelegate: WebviewNavigationDelegate?

    // Add class property to store call states
    private var callStates: [String: (members: [MemberResponse], participantResponses: [String: String], createdAt: Date, timer: Timer?)] = [:]

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

    //    private func cleanupStreamVideo() {
    //        // Cancel subscriptions
    //        tokenSubscription?.cancel()
    //        tokenSubscription = nil
    //        activeCallSubscription?.cancel()
    //        activeCallSubscription = nil
    //        lastVoIPToken = nil
    //
    //        // Cleanup UI
    //        Task { @MainActor in
    //            self.overlayViewModel?.updateCall(nil)
    //            self.overlayViewModel?.updateStreamVideo(nil)
    //            self.overlayView?.removeFromSuperview()
    //            self.overlayView = nil
    //            self.hostingController = nil
    //            self.overlayViewModel = nil
    //
    //            // Reset webview
    //            self.webView?.isOpaque = true
    //            self.webView?.backgroundColor = .white
    //            self.webView?.scrollView.backgroundColor = .white
    //        }
    //
    //        state = .notInitialized
    //    }

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
        if let streamVideo = streamVideo {
            Task {
                for await event in streamVideo.subscribe() {
                    // print("Event", event)
                    if let ringingEvent = event.rawValue as? CallRingEvent {
                        updateCallStatusAndNotify(callId: ringingEvent.callCid, state: "ringing")
                        continue
                    }

                    if let callCreatedEvent = event.rawValue as? CallCreatedEvent {
                        print("CallCreatedEvent \(String(describing: userId))")

                        let callCid = callCreatedEvent.callCid
                        let members = callCreatedEvent.members

                        // Create timer on main thread
                        await MainActor.run {
                            // Store in the combined callStates map
                            self.callStates[callCid] = (
                                members: members,
                                participantResponses: [:],
                                createdAt: Date(),
                                timer: nil
                            )

                            // Start timer to check for timeout every second
                            // Use @objc method as timer target to avoid sendable closure issues
                            let timer = Timer.scheduledTimer(timeInterval: 1.0, target: self, selector: #selector(self.checkCallTimeoutTimer(_:)), userInfo: callCid, repeats: true)

                            // Update timer in callStates
                            self.callStates[callCid]?.timer = timer
                        }

                        updateCallStatusAndNotify(callId: callCid, state: "created")
                    }

                    if let rejectedEvent = event.rawValue as? CallRejectedEvent {
                        let userId = rejectedEvent.user.id
                        let callCid = rejectedEvent.callCid

                        // Operate on callStates on the main thread
                        await MainActor.run {
                            // Update the combined callStates map
                            if var callState = self.callStates[callCid] {
                                callState.participantResponses[userId] = "rejected"
                                self.callStates[callCid] = callState
                            }
                        }

                        print("CallRejectedEvent \(userId)")
                        updateCallStatusAndNotify(callId: callCid, state: "rejected", userId: userId)

                        await checkAllParticipantsResponded(callCid: callCid)
                        continue
                    }

                    if let missedEvent = event.rawValue as? CallMissedEvent {
                        let userId = missedEvent.user.id
                        let callCid = missedEvent.callCid

                        // Operate on callStates on the main thread
                        await MainActor.run {
                            // Update the combined callStates map
                            if var callState = self.callStates[callCid] {
                                callState.participantResponses[userId] = "missed"
                                self.callStates[callCid] = callState
                            }
                        }

                        print("CallMissedEvent \(userId)")
                        updateCallStatusAndNotify(callId: callCid, state: "missed", userId: userId)

                        await checkAllParticipantsResponded(callCid: callCid)
                        continue
                    }

                    if let acceptedEvent = event.rawValue as? CallAcceptedEvent {
                        let userId = acceptedEvent.user.id
                        let callCid = acceptedEvent.callCid

                        // Operate on callStates on the main thread
                        await MainActor.run {
                            // Update the combined callStates map
                            if var callState = self.callStates[callCid] {
                                callState.participantResponses[userId] = "accepted"

                                // If someone accepted, invalidate the timer as we don't need to check anymore
                                callState.timer?.invalidate()
                                callState.timer = nil

                                self.callStates[callCid] = callState
                            }
                        }

                        print("CallAcceptedEvent \(userId)")
                        updateCallStatusAndNotify(callId: callCid, state: "accepted", userId: userId)
                        continue
                    }

                    updateCallStatusAndNotify(callId: streamVideo.state.activeCall?.callId ?? "", state: event.type)
                }
            }
        }
        // Cancel existing subscription if any
        activeCallSubscription?.cancel()
        // Create new subscription
        activeCallSubscription = streamVideo?.state.$activeCall.sink { [weak self] newState in
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

                        // Store the active call ID when a call becomes active
                        self.currentActiveCallId = newState?.cId
                        print("Updated current active call ID: \(String(describing: self.currentActiveCallId))")

                        // Update overlay and make visible when there's an active call
                        self.overlayViewModel?.updateCall(newState)
                        self.overlayView?.isHidden = false
                        self.webView?.isOpaque = false

                        // Notify that a call has started
                        self.updateCallStatusAndNotify(callId: newState?.cId ?? "", state: "joined")
                    } else {
                        // Get the call ID that was active before the state changed to nil
                        let endingCallId = self.currentActiveCallId
                        print("Call ending: \(String(describing: endingCallId))")

                        // If newState is nil, hide overlay and clear call
                        self.overlayViewModel?.updateCall(nil)
                        self.overlayView?.isHidden = true
                        self.webView?.isOpaque = true

                        // Notify that call has ended - use the properly tracked call ID
                        self.updateCallStatusAndNotify(callId: endingCallId ?? "", state: "left")

                        // Clean up any resources for this call
                        if let callCid = endingCallId {
                            // Invalidate and remove the timer
                            self.callStates[callCid]?.timer?.invalidate()

                            // Remove call from callStates
                            self.callStates.removeValue(forKey: callCid)

                            print("Cleaned up resources for ended call: \(callCid)")
                        }

                        // Clear the active call ID
                        self.currentActiveCallId = nil
                    }
                } catch {
                    log.error("Error handling call state update: \(String(describing: error))")
                }
            }
        }
    }

    @objc private func checkCallTimeoutTimer(_ timer: Timer) {
        guard let callCid = timer.userInfo as? String else { return }

        Task { [weak self] in
            guard let self = self else { return }
            await self.checkCallTimeout(callCid: callCid)
        }
    }

    private func checkCallTimeout(callCid: String) async {
        // Get a local copy of the call state from the main thread
        let callState: (members: [MemberResponse], participantResponses: [String: String], createdAt: Date, timer: Timer?)? = await MainActor.run {
            return self.callStates[callCid]
        }

        guard let callState = callState else { return }

        // Calculate time elapsed since call creation
        let now = Date()
        let elapsedSeconds = now.timeIntervalSince(callState.createdAt)

        // Check if 30 seconds have passed
        if elapsedSeconds >= 30.0 {

            // Check if anyone has accepted
            let hasAccepted = callState.participantResponses.values.contains { $0 == "accepted" }

            if !hasAccepted {
                print("Call \(callCid) has timed out after \(elapsedSeconds) seconds")
                print("No one accepted call \(callCid), marking all non-responders as missed")

                // Mark all members who haven't responded as "missed"
                for member in callState.members {
                    let memberId = member.userId
                    let needsToBeMarkedAsMissed = await MainActor.run {
                        return self.callStates[callCid]?.participantResponses[memberId] == nil
                    }

                    if needsToBeMarkedAsMissed {
                        // Update callStates map on main thread
                        await MainActor.run {
                            var updatedCallState = self.callStates[callCid]
                            updatedCallState?.participantResponses[memberId] = "missed"
                            if let updatedCallState = updatedCallState {
                                self.callStates[callCid] = updatedCallState
                            }
                        }

                        // Notify listeners
                        await MainActor.run {
                            self.updateCallStatusAndNotify(callId: callCid, state: "missed", userId: memberId)
                        }
                    }
                }

                // End the call
                if let call = streamVideo?.state.activeCall, call.cId == callCid {
                    call.leave()
                }

                // Clean up timer on main thread
                await MainActor.run {
                    self.callStates[callCid]?.timer?.invalidate()
                    var updatedCallState = self.callStates[callCid]
                    updatedCallState?.timer = nil
                    if let updatedCallState = updatedCallState {
                        self.callStates[callCid] = updatedCallState
                    }

                    // Remove from callStates
                    self.callStates.removeValue(forKey: callCid)
                }

                // Update UI
                await MainActor.run {
                    self.overlayViewModel?.updateCall(nil)
                    self.overlayView?.isHidden = true
                    self.webView?.isOpaque = true
                    self.updateCallStatusAndNotify(callId: callCid, state: "ended", reason: "timeout")
                }
            }
        }
    }

    private func checkAllParticipantsResponded(callCid: String) async {
        // Get a local copy of the call state from the main thread
        let callState: (members: [MemberResponse], participantResponses: [String: String], createdAt: Date, timer: Timer?)? = await MainActor.run {
            return self.callStates[callCid]
        }

        guard let callState = callState else {
            print("Call state not found for cId: \(callCid)")
            return
        }

        let totalParticipants = callState.members.count
        let responseCount = callState.participantResponses.count

        print("Total participants: \(totalParticipants), Responses: \(responseCount)")

        let allResponded = responseCount >= totalParticipants
        let allRejectedOrMissed = allResponded &&
            callState.participantResponses.values.allSatisfy { $0 == "rejected" || $0 == "missed" }

        if allResponded && allRejectedOrMissed {
            print("All participants have rejected or missed the call")

            // End the  call
            if let call = streamVideo?.state.activeCall, call.cId == callCid {
                call.leave()
            }

            // Clean up timer and remove from callStates on main thread
            await MainActor.run {
                // Clean up timer
                self.callStates[callCid]?.timer?.invalidate()

                // Remove from callStates
                self.callStates.removeValue(forKey: callCid)

                self.overlayViewModel?.updateCall(nil)
                self.overlayView?.isHidden = true
                self.webView?.isOpaque = true
                self.updateCallStatusAndNotify(callId: callCid, state: "ended", reason: "all_rejected_or_missed")
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
            self.overlayViewModel?.updateCall(nil)
            self.overlayViewModel?.updateStreamVideo(nil)
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

                    // Create the call object
                    let streamCall = streamVideo?.call(callType: callType, callId: callId)

                    // Start the call with the members
                    print("Creating call with members...")
                    try await streamCall?.create(
                        memberIds: members,
                        custom: [:],
                        team: team, ring: shouldRing
                    )

                    // Join the call
                    print("Joining call...")
                    try await streamCall?.join(create: false)
                    print("Successfully joined call")

                    // Update the CallOverlayView with the active call
                    await MainActor.run {
                        self.overlayViewModel?.updateCall(streamCall)
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
                        self.overlayViewModel?.updateCall(nil)
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
                    try await streamCall?.accept()
                    try await streamCall?.join(create: false)
                    try await streamCall?.get()
                    print("Successfully joined call")

                    // Update the CallOverlayView with the active call
                    await MainActor.run {
                        self.overlayViewModel?.updateCall(streamCall)
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
        )

        state = .initialized
        callKitAdapter.streamVideo = self.streamVideo
        callKitAdapter.availabilityPolicy = .always

        // Setup subscriptions for new StreamVideo instance
        setupActiveCallSubscription()
        setupTokenSubscription()

        // Register for incoming calls
        callKitAdapter.registerForIncomingCalls()
    }

    private func setupViews() {
        guard let webView = self.webView,
              let parent = webView.superview else { return }

        // Create TouchInterceptView
        let touchInterceptView = TouchInterceptView(frame: parent.bounds)
        touchInterceptView.translatesAutoresizingMaskIntoConstraints = false
        self.touchInterceptView = touchInterceptView

        // Remove webView from its parent
        webView.removeFromSuperview()

        // Add TouchInterceptView to the parent
        parent.addSubview(touchInterceptView)

        // Setup TouchInterceptView constraints
        NSLayoutConstraint.activate([
            touchInterceptView.topAnchor.constraint(equalTo: parent.topAnchor),
            touchInterceptView.bottomAnchor.constraint(equalTo: parent.bottomAnchor),
            touchInterceptView.leadingAnchor.constraint(equalTo: parent.leadingAnchor),
            touchInterceptView.trailingAnchor.constraint(equalTo: parent.trailingAnchor)
        ])

        // Configure webview for transparency
        webView.isOpaque = true
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.translatesAutoresizingMaskIntoConstraints = false

        // Create SwiftUI view with view model
        let (hostingController, viewModel) = CallOverlayView.create(streamVideo: self.streamVideo)
        hostingController.view.backgroundColor = .clear
        hostingController.view.translatesAutoresizingMaskIntoConstraints = false

        self.hostingController = hostingController
        self.overlayViewModel = viewModel
        self.overlayView = hostingController.view

        if let overlayView = self.overlayView {
            // Setup the views in TouchInterceptView
            touchInterceptView.setupWithWebView(webView, overlayView: overlayView)

            // Setup constraints for webView
            NSLayoutConstraint.activate([
                webView.topAnchor.constraint(equalTo: touchInterceptView.topAnchor),
                webView.bottomAnchor.constraint(equalTo: touchInterceptView.bottomAnchor),
                webView.leadingAnchor.constraint(equalTo: touchInterceptView.leadingAnchor),
                webView.trailingAnchor.constraint(equalTo: touchInterceptView.trailingAnchor)
            ])

            // Setup constraints for overlayView
            let safeGuide = touchInterceptView.safeAreaLayoutGuide
            NSLayoutConstraint.activate([
                overlayView.topAnchor.constraint(equalTo: safeGuide.topAnchor),
                overlayView.bottomAnchor.constraint(equalTo: safeGuide.bottomAnchor),
                overlayView.leadingAnchor.constraint(equalTo: safeGuide.leadingAnchor),
                overlayView.trailingAnchor.constraint(equalTo: safeGuide.trailingAnchor)
            ])
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
