import AVKit
import SwiftUI
import TapperCore

/// Lets a Kotlin Channel back a SwiftUI `.fullScreenCover(item:)`/`List`
/// selection - pure Swift-side protocol conformance (Channel already has a
/// matching `id: String`), no Kotlin/Native interop involved.
extension Channel: @retroactive Identifiable {}

/// Full-screen playback. Basic tier: AVPlayer wrapper with the same
/// per-stream headers, multi-feed failover and failure diagnosis as Fire
/// TV's TapperPlayer (see TapperPlayer.swift), using AVKit's native
/// VideoPlayer for transport controls rather than hand-building Fire TV's
/// custom overlay. No in-player channel guide/zapping yet - that's later
/// phase work once this foundation is confirmed working on-device.
struct PlayerView: View {
    let channel: Channel

    @Environment(\.dismiss) private var dismiss
    @StateObject private var controller = TapperPlayer()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let player = controller.player {
                VideoPlayer(player: player)
                    .ignoresSafeArea()
            }

            if controller.isBuffering && controller.diagnosisMessage == nil {
                ProgressView()
                    .tint(.white)
                    .scaleEffect(1.4)
            }

            if let message = controller.diagnosisMessage {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle)
                        .foregroundStyle(.white)
                    Text(message)
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                    Button("Close") { dismiss() }
                        .buttonStyle(.borderedProminent)
                }
                .padding(32)
                .background(.black.opacity(0.75))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(40)
            }

            VStack {
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .black.opacity(0.4))
                    }
                    .padding()
                    Spacer()
                }
                Spacer()
            }
        }
        .onAppear { controller.play(channel) }
        .onDisappear { controller.release() }
        .statusBarHidden()
    }
}
