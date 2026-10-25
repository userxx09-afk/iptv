import CoreGraphics
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

/// How hard to hold the picture quality down while on mobile data, a hotspot
/// or in Low Data Mode - the situations where a stream is most likely to
/// stall. Only matters for streams that offer more than one quality (most
/// HLS channels do); a single-quality stream is unaffected. Wi-Fi and wired
/// connections are never limited.
enum MobileQuality: String, CaseIterable, Identifiable {
    case auto, saver, full

    var id: String { rawValue }

    var label: String {
        switch self {
        case .auto: return "Automatic (default)"
        case .saver: return "Data saver"
        case .full: return "Full quality"
        }
    }

    var description: String {
        switch self {
        case .auto: return "On mobile data, cap the picture at a level that holds up while moving."
        case .saver: return "On mobile data, use a low quality (720p or less). Best for weak signal or a data limit."
        case .full: return "Never limit quality. Can stall on a weak mobile connection."
        }
    }

    /// Peak bits per second AVPlayer is asked to stay under; 0 means no cap.
    var peakBitRate: Double {
        switch self {
        case .auto: return 4_000_000
        case .saver: return 1_500_000
        case .full: return 0
        }
    }

    var maxResolution: CGSize? {
        self == .saver ? CGSize(width: 1280, height: 720) : nil
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

    /// How long the commercial-break mute lasts, in minutes - the iPad
    /// counterpart to Fire TV's muteMinutes. Sound comes back by itself.
    static let muteChoices = [1, 2, 3, 4, 5, 8, 10]

    var muteMinutes: Int {
        get {
            let v = defaults.integer(forKey: "tapper.player.muteMinutes")
            return v > 0 ? min(v, 15) : 3
        }
        set { defaults.set(max(1, min(newValue, 15)), forKey: "tapper.player.muteMinutes") }
    }

    var mobileQuality: MobileQuality {
        get { MobileQuality(rawValue: defaults.string(forKey: "tapper.player.mobileQuality") ?? "") ?? .auto }
        set { defaults.set(newValue.rawValue, forKey: "tapper.player.mobileQuality") }
    }
}
