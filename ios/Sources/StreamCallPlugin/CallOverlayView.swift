import SwiftUI
import StreamVideo
import StreamVideoSwiftUI
import Combine

class CallOverlayViewModel: ObservableObject {
    @Published var streamVideo: StreamVideo?
    @Published var call: Call?
    @Published var callState: CallState?
    @Published var viewModel: CallViewModel?
    @Published var participants: [CallParticipant] = []

    private var participantsSubscription: AnyCancellable?

    init(streamVideo: StreamVideo?) {
        self.streamVideo = streamVideo
    }

    @MainActor
    func updateCall(_ call: Call?) {
        self.call = call
        // Clean up previous subscription if any
        participantsSubscription?.cancel()

        if let call = call {
            participantsSubscription = call.state.$participants.sink { [weak self] participants in
                print("Participants update \(participants.map { $0.name })")
                self?.participants = participants
            }
            self.callState = call.state
            participantsSubscription = call.state.$callSettings.sink { [weak self] callSettings in
                print("Call settings update")
                self?.viewModel = CallViewModel(callSettings: callSettings)
                self?.viewModel?.participantAutoLeavePolicy = LastParticipantAutoLeavePolicy()
                self?.viewModel?.setActiveCall(call)
            }
        } else {
            // Clear participants when call ends
            self.participants = []
            self.callState = nil
        }
    }

    @MainActor
    func updateStreamVideo(_ streamVideo: StreamVideo?) {
        self.streamVideo = streamVideo
        if streamVideo == nil {
            self.call = nil
            self.callState = nil
        }
    }
}

class CallOverlayViewFactory: ViewFactory {
    // ... existing ViewFactory methods ...
    func makeVideoParticipantView(
        participant: CallParticipant,
        id: String,
        availableFrame: CGRect,
        contentMode: UIView.ContentMode,
        customData: [String: RawJSON],
        call: Call?
    ) -> some View {
        VideoCallParticipantView(
            viewFactory: self,
            participant: participant,
            id: id,
            availableFrame: availableFrame,
            contentMode: .scaleAspectFit,
            customData: customData,
            call: call
        )
    }
}

struct CallOverlayView: View {
    @ObservedObject var viewModel: CallOverlayViewModel
    @State private var safeAreaInsets: EdgeInsets = .init()
    private let viewFactory: CallOverlayViewFactory

    init(viewModel: CallOverlayViewModel) {
        self.viewModel = viewModel
        self.viewFactory = CallOverlayViewFactory()
    }

    var body: some View {
        VStack(spacing: 0) {
            if let viewModelStandard = viewModel.viewModel {
                ZStack {
                    CustomCallView(viewFactory: viewFactory, viewModel: viewModelStandard)
                }
                .padding(.top, safeAreaInsets.top)
                .padding(.bottom, safeAreaInsets.bottom)
            } else {
                Color.white
            }
        }
        .edgesIgnoringSafeArea(.all)
        .overlay(
            GeometryReader { geometry in
                Color.clear
                    .preference(key: SafeAreaInsetsKey.self, value: geometry.safeAreaInsets)
            }
        )
        .onPreferenceChange(SafeAreaInsetsKey.self) { value in
            safeAreaInsets = value
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func changeTrackVisibility(_ participant: CallParticipant?, isVisible: Bool) {
        print("changeTrackVisibility for \(participant?.userId), visible: \(isVisible)")
        guard let participant = participant,
              let call = viewModel.call else { return }
        Task {
            await call.changeTrackVisibility(for: participant, isVisible: isVisible)
        }
    }
}

extension CallOverlayView {
    static func create(streamVideo: StreamVideo?) -> (UIHostingController<CallOverlayView>, CallOverlayViewModel) {
        let viewModel = CallOverlayViewModel(streamVideo: streamVideo)
        let view = CallOverlayView(viewModel: viewModel)
        let hostingController = UIHostingController(rootView: view)
        hostingController.view.backgroundColor = .clear

        // Make sure we respect safe areas
        hostingController.view.insetsLayoutMarginsFromSafeArea = true

        return (hostingController, viewModel)
    }
}

#if DEBUG
struct CallOverlayView_Previews: PreviewProvider {
    static var previews: some View {
        CallOverlayView(viewModel: CallOverlayViewModel(streamVideo: nil))
    }
}
#endif

struct SafeAreaInsetsKey: PreferenceKey {
    static var defaultValue: EdgeInsets = .init()
    static func reduce(value: inout EdgeInsets, nextValue: () -> EdgeInsets) {
        value = nextValue()
    }
}
