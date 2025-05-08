import SwiftUI
import StreamVideo
import StreamVideoSwiftUI
import Combine

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
    @ObservedObject var viewModel: CallViewModel
    @State private var safeAreaInsets: EdgeInsets = .init()
    private let viewFactory: CallOverlayViewFactory

    init(viewModel: CallViewModel) {
        self.viewModel = viewModel
        self.viewFactory = CallOverlayViewFactory()
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                CustomCallView(viewFactory: viewFactory, viewModel: viewModel)
            }
            .padding(.top, safeAreaInsets.top)
            .padding(.bottom, safeAreaInsets.bottom)
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
        StreamCallPlugin.dualprint("changeTrackVisibility for \(String(describing: participant?.userId)), visible: \(isVisible)")
        guard let participant = participant,
              let call = viewModel.call else { return }
        Task {
            await call.changeTrackVisibility(for: participant, isVisible: isVisible)
        }
    }
}

extension CallOverlayView {
    static func create(callViewModel: CallViewModel) -> UIHostingController<CallOverlayView> {
        let view = CallOverlayView(viewModel: callViewModel)
        let hostingController = UIHostingController(rootView: view)
        hostingController.view.backgroundColor = .clear

        // Make sure we respect safe areas
        hostingController.view.insetsLayoutMarginsFromSafeArea = true

        return (hostingController)
    }
}

struct SafeAreaInsetsKey: PreferenceKey {
    static var defaultValue: EdgeInsets = .init()
    static func reduce(value: inout EdgeInsets, nextValue: () -> EdgeInsets) {
        value = nextValue()
    }
}
