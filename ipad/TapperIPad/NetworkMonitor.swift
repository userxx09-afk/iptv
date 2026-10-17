import Foundation
import Network

/// Watches the device's connection - whether it is up, whether it is mobile
/// data or a hotspot (expensive) or in Low Data Mode (constrained), and which
/// kind of link it is on - and tells the player when it changes. A phone or
/// iPad on the move hands over between Wi-Fi and cellular all the time, and
/// a stream already open on the old link just stops; knowing about the
/// change is what lets playback reconnect straight away instead of sitting
/// on a frozen picture.
final class NetworkMonitor {
    static let shared = NetworkMonitor()

    struct State: Equatable {
        var online: Bool
        /// Mobile data, or a hotspot another device is sharing.
        var expensive: Bool
        /// Low Data Mode is on for this connection.
        var constrained: Bool
        /// "wifi", "cellular", "wired", "other" or "none".
        var interface: String
    }

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "io.tapper.network")
    private let lock = NSLock()
    private var current = State(online: true, expensive: false, constrained: false, interface: "unknown")
    private var primed = false
    private var observers: [UUID: (State, State) -> Void] = [:]

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            self?.update(from: path)
        }
        monitor.start(queue: queue)
    }

    /// The latest known state. Safe from any thread.
    var state: State {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    /// Calls [handler] on the main queue with the old and new state whenever
    /// something about the connection changes. Returns a token for [remove].
    @discardableResult
    func observe(_ handler: @escaping (State, State) -> Void) -> UUID {
        let token = UUID()
        lock.lock()
        observers[token] = handler
        lock.unlock()
        return token
    }

    func remove(_ token: UUID) {
        lock.lock()
        observers[token] = nil
        lock.unlock()
    }

    private func update(from path: NWPath) {
        let kind: String
        if path.status != .satisfied {
            kind = "none"
        } else if path.usesInterfaceType(.wifi) {
            kind = "wifi"
        } else if path.usesInterfaceType(.cellular) {
            kind = "cellular"
        } else if path.usesInterfaceType(.wiredEthernet) {
            kind = "wired"
        } else {
            kind = "other"
        }
        let new = State(
            online: path.status == .satisfied,
            expensive: path.isExpensive,
            constrained: path.isConstrained,
            interface: kind
        )

        lock.lock()
        let old = current
        current = new
        // The first report just says where things stand - nothing changed.
        let wasPrimed = primed
        primed = true
        let handlers = Array(observers.values)
        lock.unlock()

        guard wasPrimed, old != new else { return }
        DispatchQueue.main.async {
            for handler in handlers { handler(old, new) }
        }
    }
}
