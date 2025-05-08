//
//  ParticipantsView.swift
//  Pods
//
//  Created by Michał Tremblay on 05/02/2025.
//

import SwiftUI
import StreamVideo
import StreamVideoSwiftUI

// Data structure to hold a label and a frame.
struct ViewFramePreferenceData: Equatable {
    let label: String
    let frame: CGRect
}

// PreferenceKey to collect frames.
struct ViewFramePreferenceKey: PreferenceKey {
    static var defaultValue: [ViewFramePreferenceData] = []

    static func reduce(value: inout [ViewFramePreferenceData], nextValue: () -> [ViewFramePreferenceData]) {
        value.append(contentsOf: nextValue())
    }
}

// An extension to attach a label and record the view's frame.
extension View {
    func labelFrame(_ label: String) -> some View {
        self.background(
            GeometryReader { geo in
                Color.clear
                    .preference(key: ViewFramePreferenceKey.self,
                                value: [ViewFramePreferenceData(label: label,
                                                                frame: geo.frame(in: .global))])
                    .onAppear {
                        StreamCallPlugin.dualprint("ParticipantsView - Collecting frame for label: \(label)")
                        StreamCallPlugin.dualprint("Frame: \(geo.frame(in: .global))")
                    }
            }
        )
    }
}

struct ParticipantsView: View {

    var call: Call
    var participants: [CallParticipant]
    var onChangeTrackVisibility: (CallParticipant?, Bool) -> Void
    var localParticipant: CallParticipant
    @State private var labeledFrames: [ViewFramePreferenceData] = []

    var body: some View {
        GeometryReader { proxy in
            if !participants.isEmpty {
                ZStack {
                    if participants.count >= 5 && participants.count <= 6 {
                        let nonLocalParticipants = participants.filter { $0.userId != localParticipant.userId }
                        let hStackSpacing: CGFloat = 8
                        let vStackSpacing: CGFloat = 8
                        let columnWidth = (proxy.size.width - hStackSpacing) / 2  // Account for spacing between columns

                        HStack(spacing: hStackSpacing) {
                            VStack(spacing: vStackSpacing) {
                                ForEach(nonLocalParticipants.prefix(3)) { participant in
                                    let frame = CGRect(
                                        x: proxy.frame(in: .global).origin.x,
                                        y: proxy.frame(in: .global).origin.y,
                                        width: columnWidth,
                                        height: (proxy.size.height - (vStackSpacing * 2)) / 3  // Account for 2 spaces between 3 rows
                                    )
                                    makeCallParticipantView(participant, frame: frame)
                                        .frame(width: frame.width, height: frame.height)
                                }
                            }
                            .frame(width: columnWidth, height: proxy.size.height)

                            VStack(spacing: vStackSpacing) {
                                ForEach(nonLocalParticipants.dropFirst(3)) { participant in
                                    let frame = CGRect(
                                        x: proxy.frame(in: .global).origin.x + columnWidth + hStackSpacing,
                                        y: proxy.frame(in: .global).origin.y,
                                        width: columnWidth,
                                        height: (proxy.size.height - (vStackSpacing * 2)) / 3  // Account for 2 spaces between 3 rows
                                    )
                                    makeCallParticipantView(participant, frame: frame)
                                        .frame(width: frame.width, height: frame.height)
                                }
                                let localFrame = CGRect(
                                    x: proxy.frame(in: .global).origin.x + columnWidth + hStackSpacing,
                                    y: proxy.frame(in: .global).origin.y,
                                    width: columnWidth,
                                    height: (proxy.size.height - (vStackSpacing * 2)) / 3  // Account for 2 spaces between 3 rows
                                )
                                makeCallParticipantView(localParticipant, frame: localFrame)
                                    .frame(width: localFrame.width, height: localFrame.height)
                            }
                            .frame(width: columnWidth, height: proxy.size.height)
                        }
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .padding(4)
                    } else {
                        ScrollView {
                            LazyVStack {
                                if participants.count == 1, let participant = participants.first {
                                    let frame = CGRect(
                                        x: proxy.frame(in: .global).origin.x,
                                        y: proxy.frame(in: .global).origin.y,
                                        width: proxy.size.width,
                                        height: proxy.size.height
                                    )

                                    makeCallParticipantView(participant, frame: frame)
                                        .frame(width: frame.width, height: frame.height)
                                } else {
                                    ForEach(participants.filter { $0.userId != localParticipant.userId }) { participant in
                                        let frame = CGRect(
                                            x: proxy.frame(in: .global).origin.x,
                                            y: proxy.frame(in: .global).origin.y,
                                            width: proxy.size.width,
                                            height: participants.count == 4 ? proxy.size.height / 3 : (participants.count == 2 ? proxy.size.height : proxy.size.height / 2)
                                        )

                                        makeCallParticipantView(participant, frame: frame)
                                            .frame(width: frame.width, height: frame.height)
                                    }
                                }
                            }
                        }

                        if participants.count >= 2 && participants.count <= 4 {
                            CornerDraggableView(
                                content: { availableFrame in
                                    LocalVideoView(
                                        viewFactory: DefaultViewFactory.shared,
                                        participant: localParticipant,
                                        callSettings: call.state.callSettings,
                                        call: call,
                                        availableFrame: CGRect(
                                            x: availableFrame.origin.x,
                                            y: availableFrame.origin.y,
                                            width: availableFrame.width,
                                            height: availableFrame.height * 0.8
                                        )
                                    )
                                    .frame(width: availableFrame.width, height: availableFrame.height * 0.8)
                                    .cornerRadius(12)
                                    .labelFrame("abc")
                                },
                                proxy: proxy
                            ) {
                                withAnimation {
                                    if participants.count == 1 {
                                        // call.state.callSettings.localVideoPrimary.toggle()
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Color.gray
            }
        }
        .edgesIgnoringSafeArea(.all)
    }

    @ViewBuilder
    private func makeCallParticipantView(_ participant: CallParticipant, frame: CGRect) -> some View {
        CustomVideoCallParticipantView(
            participant: participant,
            availableFrame: frame,
            contentMode: .scaleAspectFit,
            customData: [:],
            call: call
        )
        //        .onAppear { onChangeTrackVisibility(participant, true) }
        //        .onDisappear{ onChangeTrackVisibility(participant, false) }
    }
}
