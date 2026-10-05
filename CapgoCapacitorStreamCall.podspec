require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'CapgoCapacitorStreamCall'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['repository']['url']
  s.author = package['author']
  s.source = { :git => package['repository']['url'], :tag => s.version.to_s }
  s.source_files = 'ios/Sources/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target = '15.0'
  s.dependency 'Capacitor'
  # Stream 1.52+ (Xcode 27) is SPM-only; Capacitor 8 iOS uses Package.swift for Stream.
  # '~> 1.49' is the latest range resolvable from CocoaPods trunk.
  s.dependency 'StreamVideo', '~> 1.49'
  s.dependency 'StreamVideoSwiftUI', '~> 1.49'
  s.swift_version = '5.1'
end
