// swift-tools-version: 5.9
import PackageDescription

// DO NOT MODIFY THIS FILE - managed by Capacitor CLI commands
let package = Package(
    name: "CapApp-SPM",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapApp-SPM",
            targets: ["CapApp-SPM"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.5.2"),
        .package(name: "CapacitorApp", path: "../../../node_modules/.bun/@capacitor+app@8.1.1+8c735c3c6e2ff3c1/node_modules/@capacitor/app"),
        .package(name: "CapacitorHaptics", path: "../../../node_modules/.bun/@capacitor+haptics@8.0.2+8c735c3c6e2ff3c1/node_modules/@capacitor/haptics"),
        .package(name: "CapacitorKeyboard", path: "../../../node_modules/.bun/@capacitor+keyboard@8.0.5+8c735c3c6e2ff3c1/node_modules/@capacitor/keyboard"),
        .package(name: "CapacitorStatusBar", path: "../../../node_modules/.bun/@capacitor+status-bar@8.0.3+8c735c3c6e2ff3c1/node_modules/@capacitor/status-bar"),
        .package(name: "CapgoCapacitorStreamCall", path: "../../../node_modules/.bun/@capgo+capacitor-stream-call@file+..+4e139b56dad1b80a/node_modules/@capgo/capacitor-stream-call")
    ],
    targets: [
        .target(
            name: "CapApp-SPM",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "CapacitorApp", package: "CapacitorApp"),
                .product(name: "CapacitorHaptics", package: "CapacitorHaptics"),
                .product(name: "CapacitorKeyboard", package: "CapacitorKeyboard"),
                .product(name: "CapacitorStatusBar", package: "CapacitorStatusBar"),
                .product(name: "CapgoCapacitorStreamCall", package: "CapgoCapacitorStreamCall")
            ]
        )
    ]
)
