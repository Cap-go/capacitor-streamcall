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
        CAPPluginMethod(name: "getCallStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setSpeaker", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "switchCamera", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCallInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setDynamicStreamVideoApikey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDynamicStreamVideoApikey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearDynamicStreamVideoApikey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hasDynamicStreamVideoApikey", returnType: CAPPluginReturnPromise)
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
    private var touchInterceptView: TouchInterceptView?

    private var streamVideo: StreamVideo?

    private var pushNotificationsConfig: PushNotificationsConfig?

    // Store current call info for getCallStatus
    private var currentCallId: String = ""
    private var currentCallState: String = ""
    private var hasNotifiedCallJoined: Bool = false

    @Injected(\.callKitAdapter) var callKitAdapter
    @Injected(\.callKitPushNotificationAdapter) var callKitPushNotificationAdapter
    private var webviewDelegate: WebviewNavigationDelegate?

    // Declare as optional and initialize in load() method
    private var callViewModel: CallViewModel?

    // Constants for UserDefaults keys
    private let dynamicApiKeyKey = "stream.video.dynamic.apikey"

    // Helper method to update call status and notify listeners
    private func updateCallStatusAndNotify(callId: String, state: String, userId: String? = nil, reason: String? = nil, caller: [String: Any]? = nil, members: [[String: Any]]? = nil) {
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

                            // Enable touch interceptor when call becomes active
                            self.touchInterceptView?.setCallActive(true)

                            // Create/update overlay and make visible when there's an active call
                            self.createCallOverlayView()

                            // Notify that a call has started - but only if we haven't notified for this call yet
                            if let callId = viewModel.call?.cId, !self.hasNotifiedCallJoined || callId != self.currentCallId {
                                print("Notifying call joined: \(callId)")
                                self.updateCallStatusAndNotify(callId: callId, state: "joined")
                                self.hasNotifiedCallJoined = true
                            }
                        } else if case .incoming(let incomingCall) = newState {
                            // Extract caller information
                            Task {
                                var caller: [String: Any]?
                                var members: [[String: Any]]?

                                do {
                                    // Get the call from StreamVideo to access detailed information
                                    if let streamVideo = self.streamVideo {
                                        let call = streamVideo.call(callType: incomingCall.type, callId: incomingCall.id)
                                        let callInfo = try await call.get()

                                        // Extract caller information
                                        let createdBy = callInfo.call.createdBy
                                        var callerData: [String: Any] = [:]
                                        callerData["userId"] = createdBy.id
                                        callerData["name"] = createdBy.name
                                        callerData["imageURL"] = createdBy.image
                                        callerData["role"] = createdBy.role
                                        caller = callerData

                                        // Extract members information from current participants if available
                                        var membersArray: [[String: Any]] = []
                                        if let activeCall = streamVideo.state.activeCall {
                                            let participants = activeCall.state.participants
                                            for participant in participants {
                                                var memberData: [String: Any] = [:]
                                                memberData["userId"] = participant.userId
                                                memberData["name"] = participant.name
                                                memberData["imageURL"] = participant.profileImageURL?.absoluteString ?? ""
                                                memberData["role"] = participant.roles.first ?? ""
                                                membersArray.append(memberData)
                                            }
                                        }
                                        members = membersArray.isEmpty ? nil : membersArray
                                    }
                                } catch {
                                    print("Failed to get call info for caller details: \(error)")
                                }

                                // Notify with caller information
                                self.updateCallStatusAndNotify(callId: incomingCall.id, state: "ringing", caller: caller, members: members)
                            }
                        } else if newState == .idle {
                            print("Call state changed to idle. CurrentCallId: \(self.currentCallId), ActiveCall: \(String(describing: self.streamVideo?.state.activeCall?.cId))")

                            // Disable touch interceptor when call becomes inactive
                            self.touchInterceptView?.setCallActive(false)

                            // Only notify about call ending if we have a valid stored call ID and there's truly no active call
                            // This prevents false "left" events during normal state transitions
                            if !self.currentCallId.isEmpty && self.streamVideo?.state.activeCall == nil {
                                print("Call actually ending: \(self.currentCallId)")

                                // Notify that call has ended - use the stored call ID
                                self.updateCallStatusAndNotify(callId: self.currentCallId, state: "left")

                                // Reset notification flag when call ends
                                self.hasNotifiedCallJoined = false

                                // Remove the call overlay view when not in a call
                                self.ensureViewRemoved()
                            } else {
                                print("Not sending left event - CurrentCallId: \(self.currentCallId), ActiveCall exists: \(self.streamVideo?.state.activeCall != nil)")
                            }
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
        lastVoIPToken = nil

        SecureUserRepository.shared.removeCurrentUser()

        // Update the CallOverlayView with nil StreamVideo instance
        Task { @MainActor in
            // self.overlayViewModel?.updateCall(nil)
            // self.overlayViewModel?.updateStreamVideo(nil)
            self.touchInterceptView?.setCallActive(false)
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
                    self.updateCallStatusAndNotify(callId: callId, state: "created", members: allMembers)

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
                        let totalParticipants = await streamCall.state.participants.count
                        let shouldEnd = isCreator || totalParticipants <= 2

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
                        self.touchInterceptView?.setCallActive(false)
                        self.overlayView?.isHidden = true
                        self.webView?.isOpaque = true
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
                        self.touchInterceptView?.setCallActive(false)
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
        guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser(),
              let apiKey = getEffectiveApiKey() else {
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
            pushNotificationsConfig: self.pushNotificationsConfig ?? .default,
            tokenProvider: {completion in
                guard let savedCredentials = SecureUserRepository.shared.loadCurrentUser() else {
                    print("No saved credentials or API key found, cannot refresh token")

                    completion(.failure(NSError(domain: "No saved credentials or API key found, cannot refresh token", code: 0, userInfo: nil)))
                    return
                }
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
        callKitAdapter.availabilityPolicy = .always

        setupTokenSubscription()

        // Register for incoming calls
        callKitAdapter.registerForIncomingCalls()
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
            // Update reference and set call active
            self.touchInterceptView = touchInterceptView as? TouchInterceptView

            // Set up active call check function
            self.touchInterceptView?.setActiveCallCheck { [weak self] in
                return self?.streamVideo?.state.activeCall != nil
            }

            self.touchInterceptView?.setCallActive(true)
        } else {
            // Create touch intercept view if not already created
            let touchInterceptView = TouchInterceptView(frame: parent.bounds)
            touchInterceptView.translatesAutoresizingMaskIntoConstraints = false
            touchInterceptView.backgroundColor = .clear
            touchInterceptView.isOpaque = false
            touchInterceptView.setupWithWebView(webView, overlayView: overlayView.view)
            parent.addSubview(touchInterceptView)

            // Set up active call check function
            touchInterceptView.setActiveCallCheck { [weak self] in
                return self?.streamVideo?.state.activeCall != nil
            }

            // Store reference and set call active
            self.touchInterceptView = touchInterceptView
            self.touchInterceptView?.setCallActive(true)

            NSLayoutConstraint.activate([
                touchInterceptView.topAnchor.constraint(equalTo: parent.topAnchor),
                touchInterceptView.bottomAnchor.constraint(equalTo: parent.bottomAnchor),
                touchInterceptView.leadingAnchor.constraint(equalTo: parent.leadingAnchor),
                touchInterceptView.trailingAnchor.constraint(equalTo: parent.trailingAnchor)
            ])
        }
    }

    // MARK: - Dynamic API Key Management

    func saveDynamicApiKey(_ apiKey: String) {
        UserDefaults.standard.set(apiKey, forKey: dynamicApiKeyKey)
    }

    func getDynamicApiKey() -> String? {
        return UserDefaults.standard.string(forKey: dynamicApiKeyKey)
    }

    func clearDynamicApiKey() {
        UserDefaults.standard.removeObject(forKey: dynamicApiKeyKey)
    }

    func hasDynamicApiKey() -> Bool {
        guard let apiKey = getDynamicApiKey() else { return false }
        return !apiKey.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).isEmpty
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
        // Disable touch interceptor when overlay is removed
        self.touchInterceptView?.setCallActive(false)

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
        // If not in a call, reject
        if currentCallId.isEmpty || currentCallState == "left" {
            call.reject("Not in a call")
            return
        }

        call.resolve([
            "callId": currentCallId,
            "state": currentCallState
        ])
    }

    @objc func getCallInfo(_ call: CAPPluginCall) {
        guard let callId = call.getString("callId") else {
            call.reject("Missing required parameter: callId")
            return
        }

        do {
            try requireInitialized()

            guard let activeCall = streamVideo?.state.activeCall, activeCall.cId == callId else {
                call.reject("Call ID does not match active call")
                return
            }

            Task {
                do {
                    // Get detailed call information
                    let callInfo = try await activeCall.get()

                    // Extract caller information
                    var caller: [String: Any]?
                    let createdBy = callInfo.call.createdBy
                    var callerData: [String: Any] = [:]
                    callerData["userId"] = createdBy.id
                    callerData["name"] = createdBy.name
                    callerData["imageURL"] = createdBy.image
                    callerData["role"] = createdBy.role
                    caller = callerData

                    // Extract members information
                    var membersArray: [[String: Any]] = []
                    let participants = await activeCall.state.participants
                    for participant in participants {
                        var memberData: [String: Any] = [:]
                        memberData["userId"] = participant.userId
                        memberData["name"] = participant.name
                        memberData["imageURL"] = participant.profileImageURL
                        memberData["role"] = participant.roles.first ?? ""
                        membersArray.append(memberData)
                    }
                    let members = membersArray

                    // Determine call state based on current calling state
                    let state: String
                    let callingState = await self.callViewModel?.callingState
                    switch callingState {
                    case .idle:
                        state = "idle"
                    case .incoming:
                        state = "ringing"
                    case .outgoing:
                        state = "ringing"
                    case .inCall:
                        state = "joined"
                    case .lobby:
                        state = "lobby"
                    case .joining:
                        state = "joining"
                    case .reconnecting:
                        state = "reconnecting"
                    case .none:
                        state = "unknown"
                    }

                    var result: [String: Any] = [:]
                    result["callId"] = callId
                    result["state"] = state

                    if let caller = caller {
                        result["caller"] = caller
                    }

                    result["members"] = members

                    call.resolve(result)
                } catch {
                    call.reject("Failed to get call info: \(error.localizedDescription)")
                }
            }
        } catch {
            call.reject("StreamVideo not initialized")
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

    @objc func clearDynamicStreamVideoApikey(_ call: CAPPluginCall) {
        do {
            clearDynamicApiKey()
            print("Dynamic API key cleared successfully")
            call.resolve(["success": true])
        } catch {
            print("Error clearing dynamic API key: \(error)")
            call.reject("Failed to clear API key: \(error.localizedDescription)")
        }
    }

    @objc func hasDynamicStreamVideoApikey(_ call: CAPPluginCall) {
        do {
            let hasDynamicKey = hasDynamicApiKey()
            call.resolve(["hasDynamicKey": hasDynamicKey])
        } catch {
            print("Error checking dynamic API key: \(error)")
            call.reject("Failed to check API key: \(error.localizedDescription)")
        }
    }

}
