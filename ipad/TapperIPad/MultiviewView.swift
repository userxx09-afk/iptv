import AVKit
import SwiftUI
import TapperCore

/// Channels picked for multiview, from a channel's context menu.
@MainActor
final class MultiviewStore: ObservableObject {
    static let shared = MultiviewStore()
    /// The iPad decodes four streams comfortably.
    static let max = 4
    @Published var channels: [Channel] = []

    func add(_ channel: Channel) -> Bool {
        guard channels.count < Self.max, !channels.contains(where: { $0.id == channel.id }) else { return false }
        channels.append(channel)
        return true
    }
}

/// Up to four live channels on one screen. Tap a tile to give it the sound;
/// its menu watches it full screen or removes it.
struct MultiviewView: View {
    let onFullScreen: (Channel) -> Void
    let onClose: () -> Void

    @ObservedObject private var store = MultiviewStore.shared
    @State private var players: [String: TapperPlayer] = [:]
    @State private var audible: String?

    var body: some View {
        let channels = store.channels
        let columns = channels.count <= 1 ? 1 : 2
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: columns), spacing: 4) {
                ForEach(channels, id: \.id) { channel in
                    tile(channel)
                        .aspectRatio(16 / 9, contentMode: .fit)
                }
            }
            .frame(maxHeight: .infinity)
            Button(action: close) {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .foregroundStyle(.white.opacity(0.85))
                    .padding()
            }
        }
        .onAppear { sync() }
        .onChange(of: store.channels.map(\.id)) { sync() }
        .onDisappear { players.values.forEach { $0.release() } }
    }

    private func tile(_ channel: Channel) -> some View {
        let player = players[channel.id]
        return ZStack(alignment: .bottomLeading) {
            if let player {
                MultiviewPicture(controller: player)
            } else {
                Color.black
            }
            Text((audible == channel.id ? "🔊 " : "") + channel.name)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(6)
                .background(.black.opacity(0.6))
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .padding(8)
        }
        .overlay(RoundedRectangle(cornerRadius: 2).stroke(audible == channel.id ? Color.blue : .clear, lineWidth: 3))
        .contentShape(Rectangle())
        .onTapGesture { setAudible(channel.id) }
        .contextMenu {
            Button("Watch full screen") {
                let picked = channel
                close()
                onFullScreen(picked)
            }
            Button("Remove", role: .destructive) {
                store.channels.removeAll { $0.id == channel.id }
            }
        }
    }

    /// One player per channel on screen; the first one has the sound.
    private func sync() {
        let ids = Set(store.channels.map(\.id))
        for (id, p) in players where !ids.contains(id) { p.release(); players[id] = nil }
        for channel in store.channels where players[channel.id] == nil {
            let p = TapperPlayer()
            p.setAudible(false)
            p.play(channel)
            players[channel.id] = p
        }
        if audible == nil || !ids.contains(audible!) { setAudible(store.channels.first?.id) }
    }

    private func setAudible(_ id: String?) {
        audible = id
        for (key, p) in players { p.setAudible(key == id) }
    }

    private func close() {
        players.values.forEach { $0.release() }
        players = [:]
        onClose()
    }
}

/// One tile's picture; observes its player so the layer appears once the
/// stream has opened.
private struct MultiviewPicture: View {
    @ObservedObject var controller: TapperPlayer

    var body: some View {
        if let av = controller.player {
            PlayerLayerView(player: av)
        } else {
            ZStack { Color.black; ProgressView().tint(.white) }
        }
    }
}
