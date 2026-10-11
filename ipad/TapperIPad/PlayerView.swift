import AVKit
import SwiftUI
import TapperCore

/// Lets a Kotlin Channel back a SwiftUI `.fullScreenCover(item:)`/`List`
/// selection - pure Swift-side protocol conformance (Channel already has a
/// matching `id: String`), no Kotlin/Native interop involved.
extension Channel: @retroactive Identifiable {}

private func timeText(_ date: Date) -> String {
    date.formatted(date: .omitted, time: .shortened)
}

/// 83 -> "1:23", 4000 -> "1:06:40".
private func playbackClock(_ seconds: Double) -> String {
    let total = max(Int(seconds), 0)
    let h = total / 3600
    let m = (total % 3600) / 60
    let s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

/// Full-screen playback.
///
/// Live channels get their own overlay, like Fire TV's player: tap the video
/// to show or hide it. It carries what is on now (with progress) and what
/// follows, previous / next channel buttons (or swipe up / down on the video),
/// and a Channels button that opens the list you came from, so you can zap
/// without going back. Films and episodes keep AVKit's native transport
/// controls, which already do scrubbing properly.
///
/// Playback itself - per-stream headers, failover across alternate feeds,
/// failure diagnosis - is TapperPlayer's, whichever channel is showing.
struct PlayerView: View {
    /// The list the channel was picked from (as filtered on screen), for
    /// channel up / down and the Channels panel. Empty = no zapping.
    let playlist: [Channel]
    /// True for live TV: custom overlay and guide info. False for films and
    /// episodes: native controls.
    let isLive: Bool
    /// Called when zapping lands on another channel, so the app can remember
    /// it as the last-watched one.
    let onZap: ((Channel) -> Void)?
    /// For an episode: the show it belongs to, so progress is filed under it.
    let seriesItem: Channel?

    @Environment(\.dismiss) private var dismiss
    @StateObject private var controller = TapperPlayer()
    @StateObject private var recorder = WatchRecorder()
    /// Where to jump to once a film / episode is ready (saved progress).
    @State private var resumeAt: Double?
    @State private var resumeToast: String?
    @ObservedObject private var now = EpgNowStore.shared

    @State private var current: Channel
    @State private var controlsVisible = true
    @State private var showChannels = false
    @State private var upcoming: [EpgProgramme] = []
    @State private var hideTask: Task<Void, Never>?

    init(channel: Channel, playlist: [Channel] = [], isLive: Bool = false,
         seriesItem: Channel? = nil, onZap: ((Channel) -> Void)? = nil) {
        self.playlist = playlist
        self.isLive = isLive
        self.seriesItem = seriesItem
        self.onZap = onZap
        _current = State(initialValue: channel)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let player = controller.player {
                if isLive {
                    PlayerLayerView(player: player)
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture { toggleControls() }
                        .gesture(
                            DragGesture(minimumDistance: 50).onEnded { value in
                                // A mostly-vertical swipe zaps: up for the
                                // next channel, down for the previous.
                                if abs(value.translation.height) > abs(value.translation.width) {
                                    zap(value.translation.height < 0 ? 1 : -1)
                                }
                            }
                        )
                } else {
                    VideoPlayer(player: player)
                        .ignoresSafeArea()
                }
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
                    HStack(spacing: 14) {
                        if isLive && playlist.count > 1 {
                            Button("Next channel") { zap(1) }
                                .buttonStyle(.bordered)
                                .tint(.white)
                        }
                        Button("Close") { dismiss() }
                            .buttonStyle(.borderedProminent)
                    }
                }
                .padding(32)
                .background(.black.opacity(0.75))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(40)
            }

            if isLive {
                if controlsVisible { liveOverlay.transition(.opacity) }
                if showChannels { channelPanel.transition(.move(edge: .trailing)) }
            } else {
                VStack {
                    HStack {
                        closeButton
                        Spacer()
                    }
                    if let toast = resumeToast {
                        HStack(spacing: 12) {
                            Text(toast)
                                .font(.subheadline)
                            Button("Start over") { startOver() }
                                .buttonStyle(.bordered)
                                .tint(.white)
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.black.opacity(0.7))
                        .clipShape(Capsule())
                        .transition(.opacity)
                    }
                    Spacer()
                }
            }
        }
        .onAppear {
            controller.play(current)
            scheduleHide()
            startWatchTracking()
        }
        .onDisappear {
            hideTask?.cancel()
            recorder.stop()
            controller.release()
        }
        .onChange(of: controller.isBuffering) { _, buffering in
            guard !buffering, let target = resumeAt, let player = controller.player else { return }
            resumeAt = nil
            player.seek(
                to: CMTime(seconds: target, preferredTimescale: 600),
                toleranceBefore: .zero, toleranceAfter: .zero
            ) { _ in
                DispatchQueue.main.async { recorder.arm() }
            }
            withAnimation { resumeToast = "Resumed from \(playbackClock(target))" }
            DispatchQueue.main.asyncAfter(deadline: .now() + 8) {
                withAnimation { resumeToast = nil }
            }
        }
        .task(id: current.id) {
            guard isLive else { return }
            upcoming = []
            upcoming = await EpgManager.shared.schedule(for: current)
        }
        .statusBarHidden()
    }

    private var closeButton: some View {
        Button {
            dismiss()
        } label: {
            Image(systemName: "xmark.circle.fill")
                .font(.title)
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .black.opacity(0.4))
        }
        .padding()
    }

    // MARK: - Live overlay

    private var nowProgramme: EpgProgramme? { now.programme(for: current) }

    private var nextProgramme: EpgProgramme? {
        let nowSeconds = Int(Date().timeIntervalSince1970)
        let after = nowProgramme?.end ?? nowSeconds
        return upcoming.first { $0.start >= after }
    }

    private var liveOverlay: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                closeButton
                Spacer()
                AirPlayButton()
                    .frame(width: 44, height: 44)
                if playlist.count > 1 {
                    Button {
                        withAnimation(.easeOut(duration: 0.2)) { showChannels.toggle() }
                        if !showChannels { scheduleHide() }
                    } label: {
                        Image(systemName: "list.bullet")
                            .font(.title3)
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                    }
                }
            }
            .padding(.trailing, 8)
            .background(
                LinearGradient(colors: [.black.opacity(0.65), .clear], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
            )

            Spacer()

            HStack(alignment: .bottom, spacing: 18) {
                if playlist.count > 1 {
                    zapButton(systemName: "chevron.down.circle.fill", delta: -1)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(current.name)
                        .font(.title3.bold())
                        .lineLimit(1)
                    if let programme = nowProgramme {
                        HStack(spacing: 8) {
                            Text("NOW")
                                .font(.caption2.weight(.bold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.blue)
                                .clipShape(Capsule())
                            Text(programme.title)
                                .font(.headline)
                                .lineLimit(1)
                            Text("\(timeText(programme.startDate)) - \(timeText(programme.endDate))")
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.7))
                        }
                        ProgressBar(fraction: programme.progress(at: Int(Date().timeIntervalSince1970)), width: 280)
                    } else {
                        Text("No guide information for this channel.")
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                    if let next = nextProgramme {
                        HStack(spacing: 8) {
                            Text("NEXT")
                                .font(.caption2.weight(.bold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.white.opacity(0.25))
                                .clipShape(Capsule())
                            Text(next.title)
                                .font(.subheadline)
                                .lineLimit(1)
                            Text(timeText(next.startDate))
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.7))
                        }
                    }
                }
                Spacer(minLength: 0)
                if playlist.count > 1 {
                    zapButton(systemName: "chevron.up.circle.fill", delta: 1)
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 24)
            .padding(.vertical, 18)
            .background(
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
            )
        }
    }

    private func zapButton(systemName: String, delta: Int) -> some View {
        Button {
            zap(delta)
        } label: {
            Image(systemName: systemName)
                .font(.system(size: 38))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .white.opacity(0.25))
        }
    }

    /// The channel list you came from, on the right edge. Tap one to switch.
    private var channelPanel: some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            VStack(spacing: 0) {
                HStack {
                    Text("Channels")
                        .font(.headline)
                    Spacer()
                    Button {
                        withAnimation(.easeOut(duration: 0.2)) { showChannels = false }
                        scheduleHide()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }
                .padding()

                ScrollViewReader { proxy in
                    List(playlist, id: \.id) { channel in
                        Button {
                            switchTo(channel)
                            withAnimation(.easeOut(duration: 0.2)) { showChannels = false }
                        } label: {
                            HStack {
                                LiveRowLabel(channel: channel)
                                Spacer()
                                if channel.id == current.id {
                                    Image(systemName: "play.fill")
                                        .font(.caption)
                                        .foregroundStyle(Color.blue)
                                }
                            }
                        }
                        .disabled(!channel.isPlayable)
                        .listRowBackground(channel.id == current.id ? Color.white.opacity(0.12) : Color.clear)
                    }
                    .listStyle(.plain)
                    .onAppear {
                        proxy.scrollTo(current.id, anchor: .center)
                    }
                }
            }
            .frame(width: 380)
            .background(.ultraThinMaterial)
            .environment(\.colorScheme, .dark)
        }
        .ignoresSafeArea(edges: .vertical)
    }

    // MARK: - Watch progress

    /// Films and episodes: pick up where the viewer stopped last time, and
    /// keep saving progress while it plays.
    private func startWatchTracking() {
        guard !isLive, let player = controller.player else { return }
        if let saved = WatchStore.shared.entry(channelId: current.id),
           !saved.isComplete, saved.positionSec > 30 {
            resumeAt = saved.positionSec
        }
        recorder.start(player: player, channel: current, series: seriesItem, armed: resumeAt == nil)
    }

    private func startOver() {
        controller.player?.seek(to: .zero)
        WatchStore.shared.setWatched(channel: current, series: seriesItem, watched: false)
        withAnimation { resumeToast = nil }
    }

    // MARK: - Actions

    private func zap(_ delta: Int) {
        guard playlist.count > 1,
              let start = playlist.firstIndex(where: { $0.id == current.id }) else { return }
        var i = start
        for _ in 0..<playlist.count {
            i = (i + delta + playlist.count) % playlist.count
            let candidate = playlist[i]
            if candidate.isPlayable {
                switchTo(candidate)
                return
            }
        }
    }

    private func switchTo(_ channel: Channel) {
        current = channel
        controller.play(channel)
        onZap?(channel)
        withAnimation(.easeOut(duration: 0.2)) { controlsVisible = true }
        scheduleHide()
    }

    private func toggleControls() {
        withAnimation(.easeOut(duration: 0.2)) { controlsVisible.toggle() }
        if controlsVisible { scheduleHide() }
    }

    /// Hides the live overlay a few seconds after the last touch (never while
    /// the channel panel is open).
    private func scheduleHide() {
        hideTask?.cancel()
        guard isLive else { return }
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            if Task.isCancelled || showChannels { return }
            withAnimation(.easeOut(duration: 0.3)) { controlsVisible = false }
        }
    }
}

// MARK: - UIKit bridges

/// A bare AVPlayerLayer, with none of AVKit's own controls - the live overlay
/// above replaces them.
private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    final class Container: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> Container {
        let view = Container()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: Container, context: Context) {
        if view.playerLayer.player !== player {
            view.playerLayer.player = player
        }
    }
}

/// The system AirPlay picker, which a bare player layer doesn't bring with it.
private struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = .white
        view.activeTintColor = .systemBlue
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
