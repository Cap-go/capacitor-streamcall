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
    private let pluginVersion: String = "8.0.23"
    public let identifier = "StreamCallPlugin"
    public let jsName = "StreamCall"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "loginMagicToken", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "login", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "logout", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "call", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "joinCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "toggleViews", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "endCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setMicrophoneEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setCameraEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "acceptCall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isCameraEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCallStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setSpeaker", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "switchCamera", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCallInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setDynamicStreamVideoApikey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDynamicStreamVideoApikey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCurrentUser", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise)
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
    private var speakerSubscription: AnyCancellable?
    private var lastVoIPToken: String?
    private var touchInterceptView: TouchInterceptView?
    private var needsTouchInterceptorSetup: Bool = false

    private var streamVideo: StreamVideo?

    private var pushNotificationsConfig: PushNotificationsConfig?

    // Store current call info for getCallStatus
    private var currentCallId: String = ""
    private var currentCallState: String = ""
    private var hasNotifiedCallJoined: Bool = false
    private var previousCallingState: CallingState?

    @Injected(\.callKitAdapter) var callKitAdapter
    @Injected(\.callKitPushNotificationAdapter) var callKitPushNotificationAdapter
    @Injected(\.utils) var utils

    private var webviewDelegate: WebviewNavigationDelegate?

    // Declare as optional and initialize in load() method
    private var callViewModel: CallViewModel?

    // Constants for UserDefaults keys
    private let dynamicApiKeyKey = "stream.video.dynamic.apikey"

    // Helper method to update call status and notify listeners
    private func updateCallStatusAndNotify(callId: String, state: String, userId: String? = nil, reason: String? = nil, caller: [String: Any]? = nil, members: [[String: Any]]? = nil) {
        print("updateCallStatusAndNotify: callId: \(callId), state: \(state)")
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

        if let caller = caller {
            data["caller"] = caller
        }

        if let members = members {
            data["members"] = members
        }

        // Notify listeners
        notifyListeners("callEvent", data: data)
    }

    override public func load() {
        print("StreamCallPlugin: load() called")

        // Read API key from Info.plist
        if let apiKey = Bundle.main.object(forInfoDictionaryKey: "CAPACITOR_STREAM_VIDEO_APIKEY") as? String {
            self.apiKey = apiKey
            print("StreamCallPlugin: API key loaded from Info.plist")
        }
        if self.apiKey == nil {
            fatalError("Cannot get apikey")
        }

        // Check if we have a logged in user for handling incoming calls
        if let credentials = SecureUserRepository.shared.loadCurrentUser() {
            print("StreamCallPlugin: Found stored credentials during load() for user: \(credentials.user.name)")
            DispatchQueue.global(qos: .userInitiated).async {
                print("StreamCallPlugin: Calling initializeStreamVideo() from load() for user: \(credentials.user.id)")
                self.initializeStreamVideo()
            }
        } else {
            print("StreamCallPlugin: No stored credentials found during load()")
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

            // Cancel existing subscriptions if any
            self.activeCallSubscription?.cancel()
            self.activeCallSubscription = nil
            self.speakerSubscription?.cancel()
            self.speakerSubscription = nil

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

            let callEvents = streamVideo
                .eventPublisher()
                .sink { [weak self] event in
                    guard let self else { return }
                    switch event {
                    case let .typeCallRejectedEvent(response):
                        let data: [String: Any] = [
                            "callId": response.callCid,
                            "state": "rejected"
                        ]
                        notifyListeners("callEvent", data: data)
                    case let .typeCallEndedEvent(response):
                        let data: [String: Any] = [
                            "callId": response.callCid,
                            "state": "left"
                        ]
                        notifyListeners("callEvent", data: data)

                    case let .typeCallSessionParticipantCountsUpdatedEvent(response):
                        let activeCall = streamVideo.state.activeCall
                        let callDropped = self.currentCallId == response.callCid && activeCall == nil
                        let onlyOneParticipant = activeCall?.cId == response.callCid && activeCall?.state.participantCount == 1
                        if onlyOneParticipant || callDropped {
                            self.endCallInternal()
                        } else {
                            print("""
                                onlyOneParticipant check:
                                - activeCall?.cId: \(String(describing: activeCall?.cId))
                                - response.callCid: \(response.callCid)
                                - activeCall?.state.participantCount: \(String(describing: activeCall?.state.participantCount))
                                - Result (onlyOneParticipant): \(onlyOneParticipant)
                                """)
                        }
                        if let count = activeCall?.state.participantCount {
                            let data: [String: Any] = [
                                "callId": response.callCid,
                                "state": "participant_counts",
                                "count": count
                            ]
                            notifyListeners("callEvent", data: data)
                        }
                    default:
                        break
                    }
                }

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
                        viewModel.update(participantsLayout: .grid)

                        if let callId = viewModel.call?.cId, !self.hasNotifiedCallJoined || callId != self.currentCallId {
                            print("Notifying call joined: \(callId)")
                            self.updateCallStatusAndNotify(callId: callId, state: "joined")
                            self.hasNotifiedCallJoined = true
                        }

                        // Subscribe to speaker status for this active call
                        self.speakerSubscription = activeCall.speaker.$status
                            .receive(on: DispatchQueue.main)
                            .sink { [weak self] speakerStatus in
                                guard let self = self else { return }

                                // Only emit if the current active call matches our current call ID
                                guard activeCall.cId == self.currentCallId else {
                                    return
                                }

                                print("Speaker status update: \(speakerStatus)")

                                let state: String
                                switch speakerStatus {
                                case .enabled:
                                    state = "speaker_enabled"
                                case .disabled:
                                    state = "speaker_disabled"
                                }

                                let data: [String: Any] = [
                                    "callId": self.currentCallId,
                                    "state": state
                                ]

                                self.notifyListeners("callEvent", data: data)
                            }
                    } else {
                        // Clean up speaker subscription when activeCall becomes nil
                        print("Active call became nil, cleaning up speaker subscription")
                        self.speakerSubscription?.cancel()
                        self.speakerSubscription = nil

                        print("Call actually ending: \(self.currentCallId)")

                        // Notify that call has ended - use the stored call ID
                        self.updateCallStatusAndNotify(callId: self.currentCallId, state: "left")

                        // Reset notification flag when call ends
                        self.hasNotifiedCallJoined = false

                        // Remove the call overlay view and touch intercept view when not in a call
                        self.ensureViewRemoved()
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
                        // Check if transitioning from outgoing to idle
                        if newState == .idle,
                           let previousState = self.previousCallingState,
                           case .outgoing = previousState {
                            print("Call state changed from outgoing to idle. Ending call with ID: \(self.currentCallId)")

                            // Stop ongoing sound when call is cancelled
                            self.utils.callSoundsPlayer.stopOngoingSound()

                            // End the call using the stored call ID and type
                            if !self.currentCallId.isEmpty {
                                Task {
                                    do {
                                        // Parse call type and id from currentCallId format "type:id"
                                        let components = self.currentCallId.components(separatedBy: ":")
                                        if components.count >= 2 {
                                            let callType = components[0]
                                            let callId = components[1]

                                            // Try to get the call and end it using the parsed call type
                                            if let streamVideo = self.streamVideo {
                                                let call = streamVideo.call(callType: callType, callId: callId)
                                                try await call.end()
                                                print("Successfully ended outgoing call: \(callId) with type: \(callType)")

                                                let data: [String: Any] = [
                                                    "callId": call.cId,
                                                    "state": "outgoing_call_ended"
                                                ]

                                                self.notifyListeners("callEvent", data: data)
                                            }
                                        } else {
                                            print("Invalid call ID format: \(self.currentCallId)")
                                        }
                                    } catch {
                                        print("Error ending outgoing call \(self.currentCallId): \(error)")
                                    }

                                    // Clean up stored call information
                                    DispatchQueue.main.async {
                                        self.currentCallId = ""
                                        print("Cleaned up call information after ending outgoing call")
                                    }
                                }
                            }
                        }

                        if newState == .inCall {
                            print("- In call state detected")
                            print("- All participants: \(String(describing: viewModel.participants))")

                            // Ensure views are set up first (important when accepting call from notification)
                            self.setupViews()

                            // Create/update overlay and make visible when there's an active call
                            self.createCallOverlayView()

                            // Stop ongoing sound when call is joined
                            self.utils.callSoundsPlayer.stopOngoingSound()

                            // Notify that a call has started - but only if we haven't notified for this call yet
                            if let callId = viewModel.call?.cId, !self.hasNotifiedCallJoined || callId != self.currentCallId {
                                print("Notifying call joined: \(callId)")
                                self.updateCallStatusAndNotify(callId: callId, state: "joined")
                                self.hasNotifiedCallJoined = true
                            }

                        } else if case .incoming(let incomingCall) = newState {
                            self.updateCallStatusAndNotify(callId: incomingCall.id, state: "ringing")
                            //                            }
                        }
                        // Update the previous state for next comparison
                        self.previousCallingState = newState
                    } catch {
                        log.error("Error handling call state update: \(String(describing: error))")
                    }
                }

            // Combine all publishers
            self.activeCallSubscription = AnyCancellable {
                callPublisher.cancel()
                statePublisher.cancel()
                callEvents.cancel()
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

        // Get push notifications config if provided
        if let pushConfig = call.getObject("pushNotificationsConfig") {
            let pushProviderName = pushConfig["pushProviderName"] as? String ?? "ios-apn"
            let voipProviderName = pushConfig["voipProviderName"] as? String ?? "ios-voip"

            self.pushNotificationsConfig = PushNotificationsConfig(
                pushProviderInfo: PushProviderInfo(name: pushProviderName, pushProvider: .apn),
                voipPushProviderInfo: PushProviderInfo(name: voipProviderName, pushProvider: .apn)
            )
        }

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
        speakerSubscription?.cancel()
        speakerSubscription = nil
        lastVoIPToken = nil
        state = .notInitialized

        SecureUserRepository.shared.removeCurrentUser()
        self.callViewModel = nil
        self.overlayView = nil

        // Clean up touch intercept view
        if let touchInterceptView = self.touchInterceptView {
            touchInterceptView.removeFromSuperview()
            self.touchInterceptView = nil
        }

        // Update the CallOverlayView with nil StreamVideo instance
        Task { @MainActor in
            // self.overlayViewModel?.updateCall(nil)
            // self.overlayViewModel?.updateStreamVideo(nil)
            self.overlayView?.isHidden = true
            self.webView?.isOpaque = true

            // Remove touch interceptor if it exists
            self.removeTouchInterceptor()
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

    @objc func toggleViews(_ call: CAPPluginCall) {
        Task { @MainActor in
            guard let viewModel = self.callViewModel else {
                call.reject("ViewModel is not initialized.")
                return
            }

            let layouts: [ParticipantsLayout] = [.spotlight, .fullScreen, .grid]
            let currentLayout = viewModel.participantsLayout

            // If currentLayout is not in layouts, default to .grid index
            let currentIndex = layouts.firstIndex(of: currentLayout) ?? layouts.firstIndex(of: .grid) ?? 0
            let nextIndex = (currentIndex + 1) % layouts.count
            let nextLayout = layouts[nextIndex]

            viewModel.update(participantsLayout: nextLayout)

            call.resolve([
                "newLayout": "\(nextLayout)"
            ])
        }
    }

    @objc func joinCall(_ call: CAPPluginCall) {
        guard let callId = call.getString("callId") else {
            call.reject("Missing required parameter: callId")
            return
        }

        guard let callType = call.getString("callType") else {
            call.reject("Missing required parameter: callType")
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

            Task {
                do {
                    print("Joining call:")
                    print("- Call ID: \(callId)")
                    print("- Call Type: \(callType)")

                    // Create the call object
                    await self.callViewModel?.joinCall(
                        callType: callType,
                        callId: callId
                    )

                    // Now send the created event with complete member data
                    self.updateCallStatusAndNotify(callId: callId, state: "joined")

                    // Update UI on main thread
                    await MainActor.run {
                        // self.overlayViewModel?.updateCall(streamCall)
                        self.overlayView?.isHidden = false
                        self.webView?.isOpaque = false
                    }

                    call.resolve([
                        "success": true
                    ])

                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
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
            let custom = call.getObject("custom")

            // Generate a unique call ID
            let callId = UUID().uuidString

            Task {
                do {
                    print("Creating call:")
                    print("- Call ID: \(callId)")
                    print("- Call Type: \(callType)")
                    print("- Users: \(members)")
                    print("- Should Ring: \(shouldRing)")
                    print("- Team: \(String(describing: team))")
                    // Play outgoing call sound
                    self.utils.callSoundsPlayer.playOutgoingCallSound()

                    // Create the call object
                    await self.callViewModel?.startCall(
                        callType: callType,
                        callId: callId,
                        members: members.map { Member(userId: $0, role: nil, customData: [:], updatedAt: nil) },
                        team: team,
                        ring: shouldRing,
                        customData: custom?.compactMapValues { jsValue in
                            switch jsValue {
                            case let str as String:
                                return .string(str)
                            case let bool as Bool:
                                return .bool(bool)
                            case let int as Int:
                                return .number(Double(int))
                            case let float as Float:
                                return .number(Double(float))
                            case let double as Double:
                                return .number(double)
                            case let number as NSNumber:
                                return .number(number.doubleValue)
                            case is NSNull:
                                return .nil
                            case let date as Date:
                                let formatter = ISO8601DateFormatter()
                                return .string(formatter.string(from: date))
                            case let array as [JSValue]:
                                let mappedArray = array.compactMap { item -> RawJSON? in
                                    // Recursive conversion for array elements
                                    switch item {
                                    case let str as String: return .string(str)
                                    case let bool as Bool: return .bool(bool)
                                    case let int as Int: return .number(Double(int))
                                    case let double as Double: return .number(double)
                                    case let number as NSNumber: return .number(number.doubleValue)
                                    case is NSNull: return .nil
                                    default: return .string(String(describing: item))
                                    }
                                }
                                return .array(mappedArray)
                            case let dict as [String: JSValue]:
                                let mappedDict = dict.compactMapValues { value -> RawJSON? in
                                    // Recursive conversion for dictionary values
                                    switch value {
                                    case let str as String: return .string(str)
                                    case let bool as Bool: return .bool(bool)
                                    case let int as Int: return .number(Double(int))
                                    case let double as Double: return .number(double)
                                    case let number as NSNumber: return .number(number.doubleValue)
                                    case is NSNull: return .nil
                                    default: return .string(String(describing: value))
                                    }
                                }
                                return .dictionary(mappedDict)
                            default:
                                return .string(String(describing: jsValue))
                            }
                        },
                        video: video
                    )

                    // Wait for call state to be populated by WebSocket events
                    let callStream = streamVideo!.call(callType: callType, callId: callId)

                    // Wait until we have member data - with timeout to prevent infinite loop
                    var allMembers: [[String: Any]] = []
                    var attempts = 0
                    let maxAttempts = 50 // 5 seconds max

                    while allMembers.isEmpty && attempts < maxAttempts {
                        let membersList = await callStream.state.members
                        if !membersList.isEmpty {
                            allMembers = membersList.map { member in
                                [
                                    "userId": member.user.id,
                                    "name": member.user.name,
                                    "imageURL": member.user.imageURL?.absoluteString ?? "",
                                    "role": member.user.role
                                ]
                            }
                        } else {
                            attempts += 1
                            // Wait a bit and try again
                            try? await Task.sleep(nanoseconds: 100_000_000) // 0.1 seconds
                        }
                    }

                    // If we still don't have members after timeout, use basic data
                    if allMembers.isEmpty {
                        allMembers = members.map { userId in
                            [
                                "userId": userId,
                                "name": "",
                                "imageURL": "",
                                "role": ""
                            ]
                        }
                    }

                    // Now send the created event with complete member data
                    let fullCallId = "\(callType):\(callId)"
                    self.updateCallStatusAndNotify(callId: fullCallId, state: "created", members: allMembers)

                    // Update UI on main thread
                    await MainActor.run {
                        // Add touch interceptor for the call
                        self.addTouchInterceptor()

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

    private func endCallInternal() {
        // Stop ongoing sound when call ends
        self.utils.callSoundsPlayer.stopOngoingSound()

        do {
            try requireInitialized()

            Task {
                // Check both active call and callViewModel's call state to handle outgoing calls
                let activeCall = streamVideo?.state.activeCall
                let viewModelCall = await callViewModel?.call

                // Helper function to determine if we should end or leave the call
                func shouldEndCall(for streamCall: Call) async throws -> Bool {
                    do {
                        let callInfo = try await streamCall.get()
                        let currentUserId = streamVideo?.user.id
                        let createdBy = callInfo.call.createdBy.id
                        let isCreator = createdBy == currentUserId

                        // Use call.state.participants.count to get participant count (as per StreamVideo iOS SDK docs)
                        //                        let totalParticipants = await streamCall.state.participants.count
                        let forceEnd = await (activeCall?.state.custom["type"]?.stringValue == "direct")
                        let shouldEnd = isCreator || forceEnd

                        print("Call \(streamCall.cId) - Creator: \(createdBy), CurrentUser: \(currentUserId ?? "nil"), IsCreator: \(isCreator), ShouldEnd: \(shouldEnd)")

                        return shouldEnd
                    } catch {
                        print("Error getting call info for \(streamCall.cId), defaulting to leave: \(error)")
                        return false // Fallback to leave if we can't determine
                    }
                }

                if let activeCall = activeCall {
                    // There's an active call, check if we should end or leave
                    do {
                        let shouldEnd = try await shouldEndCall(for: activeCall)

                        if shouldEnd {
                            print("Ending active call \(activeCall.cId) for all participants")
                            try await activeCall.end()
                        } else {
                            print("Leaving active call \(activeCall.cId)")
                            try await activeCall.leave()
                        }
                    } catch {
                        print("Error ending/leaving active call: \(error)")
                        try await activeCall.leave() // Fallback to leave
                    }

                    await MainActor.run {
                        self.overlayView?.isHidden = true
                        self.webView?.isOpaque = true
                    }

                } else if let viewModelCall = viewModelCall {
                    // There's a call in the viewModel (likely outgoing/ringing), check if we should end or leave
                    do {
                        let shouldEnd = try await shouldEndCall(for: viewModelCall)

                        if shouldEnd {
                            print("Ending viewModel call \(viewModelCall.cId) for all participants")
                            try await viewModelCall.end()
                        } else {
                            print("Leaving viewModel call \(viewModelCall.cId)")
                            try await viewModelCall.leave()
                        }
                    } catch {
                        print("Error ending/leaving viewModel call: \(error)")
                        try await viewModelCall.leave() // Fallback to leave
                    }

                    // Also hang up to reset the calling state
                    await callViewModel?.hangUp()

                    await MainActor.run {
                        self.overlayView?.isHidden = true
                        self.webView?.isOpaque = true
                    }
                }
            }
        } catch {
            log.error("StreamVideo not initialized")
        }
    }

    @objc func endCall(_ call: CAPPluginCall) {
        // Stop ongoing sound when call ends
        self.utils.callSoundsPlayer.stopOngoingSound()

        do {
            try requireInitialized()

            Task {
                // Check both active call and callViewModel's call state to handle outgoing calls
                let activeCall = streamVideo?.state.activeCall
                let viewModelCall = await callViewModel?.call

                // Helper function to determine if we should end or leave the call
                func shouldEndCall(for streamCall: Call) async throws -> Bool {
                    do {
                        let callInfo = try await streamCall.get()
                        let currentUserId = streamVideo?.user.id
                        let createdBy = callInfo.call.createdBy.id
                        let isCreator = createdBy == currentUserId
                        let forceEnd = await (activeCall?.state.custom["type"]?.stringValue == "direct")

                        // Use call.state.participants.count to get participant count (as per StreamVideo iOS SDK docs)
                        let totalParticipants = await streamCall.state.participants.count
                        let shouldEnd = isCreator || forceEnd

                        print("Call \(streamCall.cId) - Creator: \(createdBy), CurrentUser: \(currentUserId ?? "nil"), IsCreator: \(isCreator), TotalParticipants: \(totalParticipants), ShouldEnd: \(shouldEnd)")

                        return shouldEnd
                    } catch {
                        print("Error getting call info for \(streamCall.cId), defaulting to leave: \(error)")
                        return false // Fallback to leave if we can't determine
                    }
                }

                if let activeCall = activeCall {
                    // There's an active call, check if we should end or leave
                    do {
                        let shouldEnd = try await shouldEndCall(for: activeCall)

                        if shouldEnd {
                            print("Ending active call \(activeCall.cId) for all participants")
                            try await activeCall.end()
                        } else {
                            print("Leaving active call \(activeCall.cId)")
                            try await activeCall.leave()
                        }
                    } catch {
                        print("Error ending/leaving active call: \(error)")
                        try await activeCall.leave() // Fallback to leave
                    }

                    await MainActor.run {
                        self.overlayView?.isHidden = true
                        self.webView?.isOpaque = true

                        // Remove touch interceptor
                        self.removeTouchInterceptor()
                    }

                    call.resolve([
                        "success": true
                    ])
                } else if let viewModelCall = viewModelCall {
                    // There's a call in the viewModel (likely outgoing/ringing), check if we should end or leave
                    do {
                        let shouldEnd = try await shouldEndCall(for: viewModelCall)

                        if shouldEnd {
                            print("Ending viewModel call \(viewModelCall.cId) for all participants")
                            try await viewModelCall.end()
                        } else {
                            print("Leaving viewModel call \(viewModelCall.cId)")
                            try await viewModelCall.leave()
                        }
                    } catch {
                        print("Error ending/leaving viewModel call: \(error)")
                        try await viewModelCall.leave() // Fallback to leave
                    }

                    // Also hang up to reset the calling state
                    await callViewModel?.hangUp()

                    await MainActor.run {
                        self.overlayView?.isHidden = true
                        self.webView?.isOpaque = true

                        // Remove touch interceptor
                        self.removeTouchInterceptor()
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
                        if enabled && activeCall.microphone.status == .disabled {
                            try await activeCall.microphone.toggle()
                        } else if !enabled && activeCall.microphone.status == .enabled {
                            try await activeCall.microphone.toggle()
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
                        if enabled && activeCall.camera.status == .disabled {
                            try await activeCall.camera.toggle()
                        } else if !enabled && activeCall.camera.status == .enabled {
                            try await activeCall.camera.toggle()
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
                        print("acceptCall: Setting up UI for accepted call")

                        // Ensure views are set up first
                        self.setupViews()

                        // Add touch interceptor for the call
                        self.addTouchInterceptor()

                        // self.overlayViewModel?.updateCall(streamCall)
                        self.overlayView?.isHidden = false
                        self.webView?.isOpaque = false

                        print("acceptCall: UI setup complete - overlay visible: \(!self.overlayView!.isHidden), touch interceptor: \(self.touchInterceptView != nil)")
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
        if state == .initialized {
            print("initializeStreamVideo already initialized")
            // Try to get user credentials from repository
            guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser() else {
                print("Save credentials not found, skipping initialization")
                return
            }
            if savedCredentials.user.id == streamVideo?.user.id {
                print("Skipping initializeStreamVideo as user is already logged in")
                return
            }
        } else if state == .initializing {
            print("initializeStreamVideo rejected - already initializing")
            return
        }
        state = .initializing

        // Try to get user credentials from repository
        print("StreamCallPlugin: initializeStreamVideo - Checking for saved credentials...")
        guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser(),
              let apiKey = getEffectiveApiKey() else {
            print("StreamCallPlugin: initializeStreamVideo - No saved credentials or API key found, skipping initialization")
            state = .notInitialized
            return
        }
        print("StreamCallPlugin: initializeStreamVideo - Found saved credentials for user: \(savedCredentials.user.name), proceeding with initialization")

        LogConfig.level = .debug
        self.streamVideo = StreamVideo(
            apiKey: apiKey,
            user: savedCredentials.user,
            token: UserToken(stringLiteral: savedCredentials.tokenValue),
            pushNotificationsConfig: self.pushNotificationsConfig ?? .default,
            tokenProvider: {completion in
                print("StreamCallPlugin: Token provider called for token refresh")
                guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser() else {
                    print("StreamCallPlugin: Token provider - No saved credentials found, failing token refresh")

                    completion(.failure(NSError(domain: "No saved credentials or API key found, cannot refresh token", code: 0, userInfo: nil)))
                    return
                }
                print("StreamCallPlugin: Token provider - Successfully providing token for user: \(savedCredentials.user.id)")
                completion(.success(UserToken(stringLiteral: savedCredentials.tokenValue)))
            }
        )

        if self.callViewModel == nil {
            // Initialize on main thread with proper MainActor isolation
            DispatchQueue.main.async {
                Task { @MainActor in
                    self.callViewModel = CallViewModel(participantsLayout: .grid)
                    // self.callViewModel?.participantAutoLeavePolicy = LastParticipantAutoLeavePolicy()
                    // Setup subscriptions for new StreamVideo instance
                    self.setupActiveCallSubscription()
                }
            }
        }

        state = .initialized
        callKitAdapter.streamVideo = self.streamVideo
        callKitAdapter.availabilityPolicy = .regionBased

        setupTokenSubscription()

        // Register for incoming calls
        callKitAdapter.registerForIncomingCalls()
    }

    private func setupViews() {
        guard let webView = self.webView, let parent = webView.superview else { return }

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

        // Check if we have an active call and need to add touch interceptor
        if let activeCall = self.streamVideo?.state.activeCall {
            print("Active call detected during setupViews, ensuring touch interceptor is added")
            // Make overlay visible if there's an active call
            self.overlayView?.isHidden = false
            self.webView?.isOpaque = false
            // Add touch interceptor if not already present
            self.addTouchInterceptor()
        } else if self.needsTouchInterceptorSetup {
            // If we previously tried to add touch interceptor but webview wasn't ready
            print("Deferred touch interceptor setup detected, attempting to add now")
            self.addTouchInterceptor()
            // Reset the flag if successful
            if self.touchInterceptView != nil {
                self.needsTouchInterceptorSetup = false
            }
        }
    }

    private func addTouchInterceptor() {
        guard let webView = self.webView, let parent = webView.superview else {
            print("Cannot add touch interceptor - webView or parent not ready, marking for deferred setup")
            self.needsTouchInterceptorSetup = true
            return
        }

        // Check if touch interceptor already exists
        if self.touchInterceptView != nil {
            print("Touch interceptor already exists, skipping creation")
            return
        }

        // Create the touch intercept view as an overlay for touch passthrough
        let touchInterceptView = TouchInterceptView(frame: parent.bounds)
        touchInterceptView.translatesAutoresizingMaskIntoConstraints = false
        touchInterceptView.backgroundColor = .clear
        touchInterceptView.isOpaque = false

        // Setup touch intercept view with references to webview and overlay
        if let overlayView = self.overlayView {
            touchInterceptView.setupWithWebView(webView, overlayView: overlayView)
            // Add touchInterceptView as the topmost view
            parent.addSubview(touchInterceptView)
            parent.bringSubviewToFront(touchInterceptView)
        } else {
            // If overlayView is not present, just add on top of webView
            touchInterceptView.setupWithWebView(webView, overlayView: webView)
            parent.addSubview(touchInterceptView)
            parent.bringSubviewToFront(touchInterceptView)
        }

        // Set up active call check function
        touchInterceptView.setActiveCallCheck { [weak self] in
            return self?.streamVideo?.state.activeCall != nil
        }

        // Store reference to touch intercept view
        self.touchInterceptView = touchInterceptView

        // Setup constraints for touchInterceptView to cover the entire parent
        NSLayoutConstraint.activate([
            touchInterceptView.topAnchor.constraint(equalTo: parent.topAnchor),
            touchInterceptView.bottomAnchor.constraint(equalTo: parent.bottomAnchor),
            touchInterceptView.leadingAnchor.constraint(equalTo: parent.leadingAnchor),
            touchInterceptView.trailingAnchor.constraint(equalTo: parent.trailingAnchor)
        ])

        print("Touch interceptor added for active call - view hierarchy: \(parent.subviews.map { type(of: $0) })")
    }

    private func removeTouchInterceptor() {
        guard let touchInterceptView = self.touchInterceptView else { return }

        // Remove touch interceptor from view hierarchy
        touchInterceptView.removeFromSuperview()
        self.touchInterceptView = nil
        self.needsTouchInterceptorSetup = false

        print("Touch interceptor removed after call ended")
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

            // Add touch intercept view when making existing overlay visible
            addTouchInterceptView(webView: webView, parent: parent, overlayView: existingOverlayView)
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

        // Add touch intercept view for the new overlay
        addTouchInterceptView(webView: webView, parent: parent, overlayView: overlayView.view)
    }

    private func addTouchInterceptView(webView: WKWebView, parent: UIView, overlayView: UIView) {
        // Create touch intercept view
        let touchInterceptView = TouchInterceptView(frame: parent.bounds)
        touchInterceptView.translatesAutoresizingMaskIntoConstraints = false
        touchInterceptView.backgroundColor = .clear
        touchInterceptView.isOpaque = false
        touchInterceptView.setupWithWebView(webView, overlayView: overlayView)

        // Add to parent view
        parent.addSubview(touchInterceptView)

        // Set up active call check function
        touchInterceptView.setActiveCallCheck { [weak self] in
            return self?.streamVideo?.state.activeCall != nil
        }

        // Store reference and set call active
        self.touchInterceptView = touchInterceptView

        // Setup constraints for touchInterceptView to cover the entire parent
        NSLayoutConstraint.activate([
            touchInterceptView.topAnchor.constraint(equalTo: parent.topAnchor),
            touchInterceptView.bottomAnchor.constraint(equalTo: parent.bottomAnchor),
            touchInterceptView.leadingAnchor.constraint(equalTo: parent.leadingAnchor),
            touchInterceptView.trailingAnchor.constraint(equalTo: parent.trailingAnchor)
        ])

        print("Touch intercept view added and activated for call")
    }

    // MARK: - Dynamic API Key Management

    func saveDynamicApiKey(_ apiKey: String) {
        UserDefaults.standard.set(apiKey, forKey: dynamicApiKeyKey)
    }

    func getDynamicApiKey() -> String? {
        return UserDefaults.standard.string(forKey: dynamicApiKeyKey)
    }

    func getEffectiveApiKey() -> String? {
        // A) Check if the key exists in UserDefaults
        if let dynamicApiKey = getDynamicApiKey(), !dynamicApiKey.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).isEmpty {
            print("Using dynamic API key")
            return dynamicApiKey
        } else {
            // B) If not, use static API key from Info.plist
            print("Using static API key from Info.plist")
            return self.apiKey
        }
    }

    func ensureViewRemoved() {
        print("Removing call views and touch intercept")

        // Remove touch intercept view
        if let touchInterceptView = self.touchInterceptView {
            print("Removing touch intercept view")
            touchInterceptView.removeFromSuperview()
            self.touchInterceptView = nil
        }

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

        // Remove touch interceptor
        self.removeTouchInterceptor()
    }

    @objc func getCallStatus(_ call: CAPPluginCall) {
        // If not in a call, reject
        if currentCallId.isEmpty || currentCallState == "left" {
            call.reject("Not in a call")
            return
        }

        let result: [String: Any] = [
            "callId": currentCallId,
            "state": currentCallState
        ]

        call.resolve(result)
    }

    func getCallInfo(callId: String, activeCall: Call) async -> [String: Any] {
        do {
            let customRaw = await MainActor.run { activeCall.state.custom }

            // Convert RawJSON dictionary to [String: Any] via JSONSerialization
            let custom: [String: Any]
            let jsonData = try JSONEncoder().encode(customRaw)
            custom = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any] ?? [:]

            let result: [String: Any] = [
                "callId": callId,
                "custom": custom
            ]

            print("Debug: Resolving getCallInfo with custom =", custom)
            return result
        } catch {
            print("Error resolving getCallInfo:", error)
            return [
                "callId": callId,
                "custom": [:]
            ]
        }
    }

    @objc func enableBluetooth(call: CAPPluginCall) {
        Task {
            do {

                let policy = DefaultAudioSessionPolicy()
                try await self.callViewModel?.call?.updateAudioSessionPolicy(policy)
                call.resolve([
                    "success": true
                ])
            } catch {
                print("Failed to update policy: \(error)")
                call.reject("Unable to set bluetooth policy")
            }
        }
    }

    @objc func setSpeaker(_ call: CAPPluginCall) {
        guard let name = call.getString("name") else {
            call.reject("Missing required parameter: name")
            return
        }

        do {
            try requireInitialized()

            Task {
                do {
                    if let activeCall = streamVideo?.state.activeCall {
                        if name == "speaker" {
                            try await activeCall.speaker.enableSpeakerPhone()
                        } else {
                            try await activeCall.speaker.disableSpeakerPhone()
                        }
                    }
                    call.resolve([
                        "success": true
                    ])
                } catch {
                    log.error("Error setting speaker: \(String(describing: error))")
                    call.reject("Failed to set speaker: \(error.localizedDescription)")
                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
        }
    }

    @objc func switchCamera(_ call: CAPPluginCall) {
        guard let camera = call.getString("camera") else {
            call.reject("Missing required parameter: camera")
            return
        }

        do {
            try requireInitialized()

            Task {
                do {
                    if let activeCall = streamVideo?.state.activeCall {
                        if (camera == "front" && activeCall.camera.direction != .front) ||
                            (camera == "back" && activeCall.camera.direction != .back) {
                            try await activeCall.camera.flip()
                        }
                    }
                    call.resolve([
                        "success": true
                    ])
                } catch {
                    log.error("Error switching camera: \(String(describing: error))")
                    call.reject("Failed to switch camera: \(error.localizedDescription)")
                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
        }
    }

    @objc func setDynamicStreamVideoApikey(_ call: CAPPluginCall) {
        guard let apiKey = call.getString("apiKey") else {
            call.reject("Missing required parameter: apiKey")
            return
        }

        do {
            saveDynamicApiKey(apiKey)
            print("Dynamic API key saved successfully")
            call.resolve([
                "success": true
            ])
        } catch {
            print("Error saving dynamic API key: \(error)")
            call.reject("Failed to save API key: \(error.localizedDescription)")
        }
    }

    @objc func getDynamicStreamVideoApikey(_ call: CAPPluginCall) {
        do {
            let apiKey = getDynamicApiKey()
            call.resolve([
                "apiKey": apiKey as Any,
                "hasDynamicKey": apiKey != nil && !apiKey!.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).isEmpty
            ])
        } catch {
            print("Error getting dynamic API key: \(error)")
            call.reject("Failed to get API key: \(error.localizedDescription)")
        }
    }

    @objc func getCurrentUser(_ call: CAPPluginCall) {
        print("StreamCallPlugin: getCurrentUser called")
        print("StreamCallPlugin: getCurrentUser: Current state: \(state), StreamVideo initialized: \(streamVideo != nil)")

        do {
            if let savedCredentials = SecureUserRepository.shared.loadCurrentUser() {
                print("StreamCallPlugin: getCurrentUser: Found saved credentials for user: \(savedCredentials.user.id)")

                // Check if StreamVideo session matches the stored credentials
                let isStreamVideoActive = streamVideo != nil && state == .initialized
                let streamVideoUserId = streamVideo?.user.id
                let credentialsMatch = streamVideoUserId == savedCredentials.user.id

                print("StreamCallPlugin: getCurrentUser: StreamVideo active: \(isStreamVideoActive), StreamVideo user: \(streamVideoUserId ?? "nil"), Credentials match: \(credentialsMatch)")

                // If credentials exist but StreamVideo session is not active, try to reinitialize
                if !isStreamVideoActive || !credentialsMatch {
                    print("StreamCallPlugin: getCurrentUser: StreamVideo session not active or user mismatch, attempting to reinitialize...")
                    DispatchQueue.global(qos: .userInitiated).async {
                        self.initializeStreamVideo()
                    }
                }

                let result: [String: Any] = [
                    "userId": savedCredentials.user.id,
                    "name": savedCredentials.user.name,
                    "imageURL": savedCredentials.user.imageURL?.absoluteString ?? "",
                    "isLoggedIn": true
                ]
                print("StreamCallPlugin: getCurrentUser: Returning \(result)")
                call.resolve(result)
            } else {
                print("StreamCallPlugin: getCurrentUser: No saved credentials found")
                let result: [String: Any] = [
                    "userId": "",
                    "name": "",
                    "imageURL": "",
                    "isLoggedIn": false
                ]
                print("StreamCallPlugin: getCurrentUser: Returning \(result)")
                call.resolve(result)
            }
        } catch {
            print("StreamCallPlugin: getCurrentUser: Failed to get current user - \(error)")
            call.reject("Failed to get current user: \(error.localizedDescription)")
        }
    }

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": self.pluginVersion])
    }

}
