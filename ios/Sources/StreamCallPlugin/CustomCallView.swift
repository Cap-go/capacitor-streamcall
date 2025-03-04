//
// Copyright © 2025 Stream.io Inc. All rights reserved.
//

import StreamVideo
import StreamWebRTC
import SwiftUI
import StreamVideoSwiftUI

// Custom class to hold bindable settings
class BindableCallSettings: ObservableObject {
    @Published var settings: CallSettings

    init(settings: CallSettings) {
        self.settings = settings
    }
}

public struct CustomCallView<Factory: ViewFactory>: View {

    @Injected(\.streamVideo) var streamVideo
    @Injected(\.images) var images
    @Injected(\.colors) var colors

    var viewFactory: Factory
    @ObservedObject var viewModel: CallViewModel
    @StateObject private var bindableSettings: BindableCallSettings

    public init(
        viewFactory: Factory = DefaultViewFactory.shared,
        viewModel: CallViewModel
    ) {
        self.viewFactory = viewFactory
        self.viewModel = viewModel
        self._bindableSettings = StateObject(wrappedValue: BindableCallSettings(settings: viewModel.callSettings))
    }

    public var body: some View {
        VStack {
            GeometryReader { videoFeedProxy in
                ZStack {
                    contentView(videoFeedProxy.frame(in: .global))

                    cornerDraggableView(videoFeedProxy)
                }
            }
            .padding([.leading, .trailing], 8)
        }
        .background(Color(colors.callBackground).edgesIgnoringSafeArea(.all))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .presentParticipantListView(viewModel: viewModel, viewFactory: viewFactory)
        .onChange(of: viewModel.callSettings) { newSettings in
            bindableSettings.settings = newSettings
        }
    }

    @ViewBuilder
    private func contentView(_ availableFrame: CGRect) -> some View {
        if viewModel.localVideoPrimary, viewModel.participantsLayout == .grid {
            localVideoView(bounds: availableFrame)
                .accessibility(identifier: "localVideoView")
        } else if
            let screenSharingSession = viewModel.call?.state.screenSharingSession,
            viewModel.call?.state.isCurrentUserScreensharing == false {
            viewFactory.makeScreenSharingView(
                viewModel: viewModel,
                screensharingSession: screenSharingSession,
                availableFrame: availableFrame
            )
        } else {
            participantsView(bounds: availableFrame)
        }
    }

    private var shouldShowDraggableView: Bool {
        let participantsCount = viewModel.participants.count
        return (viewModel.call?.state.screenSharingSession == nil || viewModel.call?.state.isCurrentUserScreensharing == true)
            && viewModel.participantsLayout == .grid
            && participantsCount > 0
            && participantsCount <= 3
    }

    @ViewBuilder
    private func cornerDraggableView(_ proxy: GeometryProxy) -> some View {
        if shouldShowDraggableView {
            CornerDraggableView(
                content: { cornerDraggableViewContent($0) },
                proxy: proxy,
                onTap: {
                    withAnimation {
                        if participants.count == 1 {
                            viewModel.localVideoPrimary.toggle()
                        }
                    }
                }
            )
            .accessibility(identifier: "cornerDraggableView")
            .opacity(viewModel.hideUIElements ? 0 : 1)
            .padding()
        } else {
            EmptyView()
        }
    }

    @ViewBuilder
    private func cornerDraggableViewContent(_ bounds: CGRect) -> some View {
        if viewModel.localVideoPrimary {
            minimizedView(bounds: bounds)
        } else {
            localVideoView(bounds: bounds)
        }
    }

    @ViewBuilder
    private func minimizedView(bounds: CGRect) -> some View {
        if let firstParticipant = viewModel.participants.first {
            viewFactory.makeVideoParticipantView(
                participant: firstParticipant,
                id: firstParticipant.id,
                availableFrame: bounds,
                contentMode: .scaleAspectFit,
                customData: [:],
                call: viewModel.call
            )
            .modifier(
                viewFactory.makeVideoCallParticipantModifier(
                    participant: firstParticipant,
                    call: viewModel.call,
                    availableFrame: bounds,
                    ratio: bounds.width / bounds.height,
                    showAllInfo: true
                )
            )
            .accessibility(identifier: "minimizedParticipantView")
        } else {
            EmptyView()
        }
    }

    @ViewBuilder
    private func localVideoView(bounds: CGRect) -> some View {
        if let localParticipant = viewModel.localParticipant {
            CustomLocalVideoView(
                viewFactory: viewFactory,
                participant: localParticipant,
                callSettings: bindableSettings.settings,
                call: viewModel.call,
                availableFrame: bounds
            )
            .modifier(viewFactory.makeLocalParticipantViewModifier(
                localParticipant: localParticipant,
                callSettings: $bindableSettings.settings,
                call: viewModel.call
            ))
        } else {
            EmptyView()
        }
    }

    private func participantsView(bounds: CGRect) -> some View {
        viewFactory.makeVideoParticipantsView(
            viewModel: viewModel,
            availableFrame: bounds,
            onChangeTrackVisibility: viewModel.changeTrackVisibility(for:isVisible:)
        )
    }

    public func makeVideoParticipantsView(
        viewModel: CallViewModel,
        availableFrame: CGRect,
        onChangeTrackVisibility: @escaping @MainActor(CallParticipant, Bool) -> Void
    ) -> some View {
        VideoParticipantsView(
            viewFactory: self.viewFactory,
            viewModel: viewModel,
            availableFrame: availableFrame,
            onChangeTrackVisibility: onChangeTrackVisibility
        )
    }

    private var participants: [CallParticipant] {
        viewModel.participants
    }
}

// Custom modifier that doesn't require binding
struct CustomLocalParticipantViewModifier: ViewModifier {
    let localParticipant: CallParticipant
    let callSettings: CallSettings
    let call: Call?

    func body(content: Content) -> some View {
        content
            .overlay(
                VStack {
                    HStack {
                        Text(localParticipant.name)
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.black.opacity(0.5))
                            .cornerRadius(8)
                        Spacer()
                    }
                    Spacer()
                }
                .padding(8)
            )
    }
}

public struct CustomLocalVideoView<Factory: ViewFactory>: View {

    @Injected(\.streamVideo) var streamVideo

    private let callSettings: CallSettings
    private var viewFactory: Factory
    private var participant: CallParticipant
    private var idSuffix: String
    private var call: Call?
    private var availableFrame: CGRect

    public init(
        viewFactory: Factory = DefaultViewFactory.shared,
        participant: CallParticipant,
        idSuffix: String = "local",
        callSettings: CallSettings,
        call: Call?,
        availableFrame: CGRect
    ) {
        self.viewFactory = viewFactory
        self.participant = participant
        self.idSuffix = idSuffix
        self.callSettings = callSettings
        self.call = call
        self.availableFrame = availableFrame
    }

    public var body: some View {
        viewFactory.makeVideoParticipantView(
            participant: participant,
            id: "\(streamVideo.user.id)-\(idSuffix)",
            availableFrame: availableFrame,
            contentMode: .scaleAspectFit,
            customData: ["videoOn": .bool(callSettings.videoOn)],
            call: call
        )
        .adjustVideoFrame(to: availableFrame.width, ratio: availableFrame.width / availableFrame.height)
    }
}
