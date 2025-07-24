// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "StreamCall",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "StreamCall",
            targets: ["StreamCallPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.0.0"),
        .package(url: "https://github.com/GetStream/stream-video-swift.git", exact: "1.29.0")
    ],
    targets: [
        .target(
            name: "StreamCallPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "StreamVideo", package: "stream-video-swift"),
                .product(name: "StreamVideoSwiftUI", package: "stream-video-swift")
            ],
            path: "ios/Sources/StreamCallPlugin"),
        .testTarget(
            name: "StreamCallPluginTests",
            dependencies: ["StreamCallPlugin"],
            path: "ios/Tests/StreamCallPluginTests")
    ]
)
