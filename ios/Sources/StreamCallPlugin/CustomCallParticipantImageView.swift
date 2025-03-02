import SwiftUI
import StreamVideo
import StreamVideoSwiftUI

struct CircledTitleView: View {
    
    @Injected(\.colors) var colors
    @Injected(\.fonts) var fonts
    
    var title: String
    var size: CGFloat = 172 //.expandedAvatarSize
    
    var body: some View {
        ZStack {
            Circle()
                .foregroundColor(colors.tintColor)
            Text(title)
                .foregroundColor(.white)
                .font(fonts.title)
                .minimumScaleFactor(0.4)
                .padding()
        }
        .frame(maxWidth: size, maxHeight: size)
        // .modifier(ShadowModifier())
    }
}

struct CustomCallParticipantImageView<Factory: ViewFactory>: View {
    var viewFactory: Factory
    var id: String
    var name: String
    var imageURL: URL?
    var size: CGFloat = 90
    // var frame: CGSize?
    
    @Injected(\.colors) var colors
    
    public var body: some View {
        StreamLazyImage(imageURL: imageURL) {
            Color(colors.participantBackground)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .blur(radius: 8)
        .clipped()
        .overlay(
            viewFactory.makeUserAvatar(
                .init(id: id, name: name, imageURL: imageURL),
                with: .init(size: size) {
                    AnyView(
                        CircledTitleView(
                            title: name.isEmpty ? id : String(name.uppercased().first!),
                            size: size
                        )
                    )
                }
            )
        )
        .clipShape(Rectangle())
    }
} 

