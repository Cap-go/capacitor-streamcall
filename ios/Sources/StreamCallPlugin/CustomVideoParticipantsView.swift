import StreamVideo
import StreamVideoSwiftUI
import StreamWebRTC
import SwiftUI

public struct CustomVideoCallParticipantView<Factory: ViewFactory>: View {

    @Injected(\.images) var images
    @Injected(\.streamVideo) var streamVideo

    var viewFactory: Factory
    let participant: CallParticipant
    var id: String
    var availableFrame: CGRect
    var contentMode: UIView.ContentMode
    var edgesIgnoringSafeArea: Edge.Set
    var customData: [String: RawJSON]
    var call: Call?

    @State private var isUsingFrontCameraForLocalUser: Bool = false

    public init(
        viewFactory: Factory = DefaultViewFactory.shared,
        participant: CallParticipant,
        id: String? = nil,
        availableFrame: CGRect,
        contentMode: UIView.ContentMode,
        edgesIgnoringSafeArea: Edge.Set = .all,
        customData: [String: RawJSON],
        call: Call?
    ) {
        StreamCallPlugin.dualprint("size: \(availableFrame.size)")
        self.viewFactory = viewFactory
        self.participant = participant
        self.id = id ?? participant.id
        self.availableFrame = availableFrame
        self.contentMode = contentMode
        self.edgesIgnoringSafeArea = edgesIgnoringSafeArea
        self.customData = customData
        self.call = call
    }

    public var body: some View {
        withCallSettingsObservation {
            VideoRendererView(
                id: id,
                size: availableFrame.size,
                contentMode: contentMode,
                showVideo: showVideo,
                handleRendering: { [weak call, participant] view in
                    guard call != nil else { return }
                    view.handleViewRendering(for: participant) { [weak call] size, participant in
                        Task { [weak call] in
                            await call?.updateTrackSize(size, for: participant)
                        }
                    }
                }
            )
        }
        .opacity(showVideo ? 1 : 0)
        .edgesIgnoringSafeArea(edgesIgnoringSafeArea)
        .accessibility(identifier: "callParticipantView")
        .streamAccessibility(value: showVideo ? "1" : "0")
        .overlay(
            //            CustomCallParticipantImageView(
            //                viewFactory: viewFactory,
            //                id: participant.id,
            //                name: participant.name,
            //                imageURL: participant.profileImageURL
            //                // frame: availableFrame.size
            //            )
            Color.green
                .frame(width: availableFrame.width, height: availableFrame.height)
                .opacity(showVideo ? 0 : 1)
        )
    }

    private var showVideo: Bool {
        participant.shouldDisplayTrack || customData["videoOn"]?.boolValue == true
    }

    @MainActor
    @ViewBuilder
    private func withCallSettingsObservation(
        @ViewBuilder _ content: () -> some View
    ) -> some View {
        if participant.id == streamVideo.state.activeCall?.state.localParticipant?.id {
            content()
                .rotation3DEffect(.degrees(180), axis: (x: 0, y: 1, z: 0))
                .onReceive(call?.state.$callSettings) { self.isUsingFrontCameraForLocalUser = $0.cameraPosition == .front }
        } else {
            content()
        }
    }
}

extension Color {
    init(hex: UInt, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255,
            green: Double((hex >> 8) & 0xff) / 255,
            blue: Double(hex & 0xff) / 255,
            opacity: alpha
        )
    }
}
