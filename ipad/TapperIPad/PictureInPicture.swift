import AVKit
import SwiftUI

/// Picture in picture for live TV, which plays through a bare AVPlayerLayer
/// (see PlayerLayerView) and so doesn't get AVKit's own PiP button. Films and
/// series play through VideoPlayer, which offers PiP by itself once the app
/// has the audio background mode (project.yml).
///
/// Going to the Home screen while a channel plays also starts PiP, so the
/// game keeps going in a corner while you check something else.
final class PictureInPicture: NSObject, ObservableObject, AVPictureInPictureControllerDelegate {
    static let shared = PictureInPicture()

    @Published private(set) var active = false
    @Published private(set) var possible = false

    private var controller: AVPictureInPictureController?
    private var observation: NSKeyValueObservation?
    private weak var layer: AVPlayerLayer?

    var supported: Bool { AVPictureInPictureController.isPictureInPictureSupported() }

    /// Called by the live player's layer view once it has a layer.
    func attach(_ layer: AVPlayerLayer) {
        guard supported, self.layer !== layer else { return }
        self.layer = layer
        let c = AVPictureInPictureController(playerLayer: layer)
        c?.delegate = self
        c?.canStartPictureInPictureAutomaticallyFromInline = true
        observation = c?.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] c, _ in
            DispatchQueue.main.async { self?.possible = c.isPictureInPicturePossible }
        }
        controller = c
    }

    func detach(_ layer: AVPlayerLayer) {
        guard self.layer === layer, !active else { return }
        observation = nil
        controller = nil
        self.layer = nil
        possible = false
    }

    func toggle() {
        guard let c = controller else { return }
        if c.isPictureInPictureActive { c.stopPictureInPicture() } else { c.startPictureInPicture() }
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) {
        active = true
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        active = false
    }

    func pictureInPictureController(_ c: AVPictureInPictureController,
                                    failedToStartPictureInPictureWithError error: Error) {
        active = false
        AppLog.shared.log("PIP", "Couldn't start picture in picture: \(error.localizedDescription)")
    }
}

/// The PiP button in the live overlay; hidden on devices without PiP.
struct PictureInPictureButton: View {
    @ObservedObject private var pip = PictureInPicture.shared

    var body: some View {
        if pip.supported {
            Button { pip.toggle() } label: {
                Image(systemName: pip.active ? "pip.exit" : "pip.enter")
                    .font(.title3)
                    .foregroundStyle(pip.possible ? .white : .white.opacity(0.4))
                    .frame(width: 44, height: 44)
            }
            .disabled(!pip.possible)
            .accessibilityLabel(pip.active ? "Exit picture in picture" : "Picture in picture")
        }
    }
}
