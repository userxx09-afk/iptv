import Foundation

/// Buffer depth for playback, configurable from the toolbar's Buffering
/// menu - the iPad counterpart to Fire TV's BufferSize (data/PlayerSettings.kt).
///
/// AVFoundation only exposes one knob here (AVPlayerItem.preferredForward-
/// BufferDuration - seconds of media to keep buffered ahead of the
/// playhead), not ExoPlayer's four separate min/max/playback/rebuffer
/// values, so this is a simpler mapping than Fire TV's - same four tiers,
/// same reasoning, one number each instead of four.
///
/// Medium is the default - tuned for fast channel changes on a decent
/// connection, since live streams can't seek backwards and a deep buffer
/// only delays the first frame. Small asks AVPlayer to decide for itself
/// (its own adaptive default, typically the smallest of the four) for the
/// fastest possible zap on a strong connection; Large and Very Large trade
/// zap speed and a little memory for more cushion against a weak or
/// congested one, for anyone whose stream keeps stalling or rebuffering
/// rather than failing outright.
enum BufferSize: String, CaseIterable, Identifiable {
    case small, medium, large, veryLarge

    var id: String { rawValue }

    var label: String {
        switch self {
        case .small: return "Small"
        case .medium: return "Medium (default)"
        case .large: return "Large"
        case .veryLarge: return "Very Large"
        }
    }

    var description: String {
        switch self {
        case .small: return "Fastest channel changes. Best on a strong, stable connection."
        case .medium: return "A balance of quick zapping and some cushion against brief hiccups."
        case .large: return "More cushion for a slower or shared connection. Channel changes take a bit longer."
        case .veryLarge: return "Maximum cushion for a weak or congested connection. Channel changes are noticeably slower."
        }
    }

    /// Seconds of forward buffer AVPlayer tries to maintain ahead of the
    /// playhead. 0 means "let AVPlayer decide" - its own default, which
    /// already favors a fast start over a deep buffer.
    var forwardBufferSeconds: Double {
        switch self {
        case .small: return 0
        case .medium: return 5
        case .large: return 15
        case .veryLarge: return 30
        }
    }
}

/// Plain UserDefaults, same shape as Fire TV's PlayerSettingsStore
/// (SharedPreferences-backed). Read once per stream start (TapperPlayer.swift),
/// so a value changed mid-playback takes effect the next time something is
/// played, not on the stream already running - same as Fire TV.
final class PlayerSettingsStore {
    static let shared = PlayerSettingsStore()

    private let defaults = UserDefaults.standard
    private let key = "tapper.player.bufferSize"

    private init() {}

    var bufferSize: BufferSize {
        get { BufferSize(rawValue: defaults.string(forKey: key) ?? "") ?? .medium }
        set { defaults.set(newValue.rawValue, forKey: key) }
    }
}
