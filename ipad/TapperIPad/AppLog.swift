import Foundation
import MetricKit
import os

/// A small on-device log that survives a crash, so a problem like "the app
/// dies while loading a source" can be diagnosed without a Mac attached.
///
/// Everything is written straight to a file in Application Support the moment
/// it happens (synchronously, one short append per line), so the last lines
/// before a crash or a memory kill are still there on the next launch. The
/// in-app viewer (Settings > Logs) shows it, and it can be copied or shared.
///
/// Four things feed it:
///  1. Plain events from the app (source load start/finish, counts, timings).
///  2. A memory reading every couple of seconds while a source is loading -
///     a memory kill leaves no crash trace at all, but a climbing "mem" line
///     right before the log stops is the giveaway.
///  3. A fatal-signal / uncaught-exception handler that records the stack
///     trace just before the process dies.
///  4. MetricKit, which hands the app Apple's own crash report on the next
///     launch (kept separately, shown as "Crash reports").
final class AppLog: ObservableObject {
    static let shared = AppLog()

    private let queue = DispatchQueue(label: "io.tapper.applog")
    private let logURL: URL
    private let diagURL: URL
    private let maxBytes = 200 * 1024
    private let osLogger = Logger(subsystem: "io.tapper.ipad", category: "app")

    private let cleanExitKey = "tapper.cleanExit"
    private let loadInProgressKey = "tapper.loadInProgress"

    /// The source id that was mid-load when the previous run died, if any.
    /// Read once at launch (before the breadcrumb is overwritten).
    private(set) var interruptedLoadSourceId: String?
    /// True when the previous run did not end by being sent to the background
    /// (a crash, a memory kill, or a force-quit while it was open).
    private(set) var previousSessionEndedUnexpectedly = false

    private init() {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                appropriateFor: nil, create: true))
            ?? fm.temporaryDirectory
        let dir = base.appendingPathComponent("Logs", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        logURL = dir.appendingPathComponent("tapper.log")
        diagURL = dir.appendingPathComponent("crash-reports.txt")
    }

    // MARK: - Writing

    func log(_ tag: String, _ message: String) {
        let line = "\(Self.timestamp()) [\(tag)] \(message)"
        osLogger.log("\(line, privacy: .public)")
        queue.sync { append(line + "\n", to: logURL, trim: true) }
    }

    private func append(_ text: String, to url: URL, trim: Bool) {
        guard let data = text.data(using: .utf8) else { return }
        if let handle = try? FileHandle(forWritingTo: url) {
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
            try? handle.close()
        } else {
            try? data.write(to: url, options: .atomic)
        }
        if trim { trimIfNeeded(url) }
    }

    /// Keeps the file bounded: once past the cap, drops the oldest half
    /// (cut on a line boundary).
    private func trimIfNeeded(_ url: URL) {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? NSNumber, size.intValue > maxBytes,
              let data = try? Data(contentsOf: url) else { return }
        var tail = data.suffix(maxBytes / 2)
        if let nl = tail.firstIndex(of: 0x0A) { tail = tail.suffix(from: tail.index(after: nl)) }
        try? Data(tail).write(to: url, options: .atomic)
    }

    private static let timestampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        return f
    }()

    private static func timestamp() -> String {
        timestampFormatter.string(from: Date())
    }

    // MARK: - Reading

    func readLog() -> String {
        queue.sync { (try? String(contentsOf: logURL, encoding: .utf8)) ?? "" }
    }

    func readCrashReports() -> String {
        queue.sync { (try? String(contentsOf: diagURL, encoding: .utf8)) ?? "" }
    }

    func clearLog() {
        queue.sync { try? FileManager.default.removeItem(at: logURL) }
    }

    func clearCrashReports() {
        queue.sync { try? FileManager.default.removeItem(at: diagURL) }
    }

    /// One file with both logs, for the share sheet.
    func exportFileURL() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("tapper-ipad-logs.txt")
        let text = "TAPPER IPAD LOG - \(AppLog.appVersionString())\n\n" +
            "===== APP LOG =====\n" + readLog() +
            "\n===== CRASH REPORTS =====\n" + readCrashReports()
        try? text.data(using: .utf8)?.write(to: url, options: .atomic)
        return url
    }

    fileprivate func storeCrashReport(_ text: String) {
        queue.sync { append(text, to: diagURL, trim: false) }
    }

    // MARK: - Session / crash tracking

    func beginSession() {
        let d = UserDefaults.standard
        let wasClean = (d.object(forKey: cleanExitKey) as? Bool) ?? true
        previousSessionEndedUnexpectedly = !wasClean
        interruptedLoadSourceId = d.string(forKey: loadInProgressKey)
        d.removeObject(forKey: loadInProgressKey)
        d.set(false, forKey: cleanExitKey)

        log("SESSION", "Launch - \(AppLog.appVersionString()) - iOS \(ProcessInfo.processInfo.operatingSystemVersionString) - \(AppLog.memoryDescription())")
        if previousSessionEndedUnexpectedly {
            log("SESSION", "The previous run ended unexpectedly (crash, memory kill, or force-quit while open).")
        }
        if let id = interruptedLoadSourceId {
            log("SESSION", "The previous run died while loading source \"\(id)\".")
        }
        installCrashHandlers()
    }

    /// Called when the app goes to the background - the normal way a session
    /// ends, so anything else shows up as "ended unexpectedly" next launch.
    func markBackgrounded() {
        UserDefaults.standard.set(true, forKey: cleanExitKey)
    }

    func markForegrounded() {
        UserDefaults.standard.set(false, forKey: cleanExitKey)
    }

    /// Breadcrumb for "a source load is under way". If the app dies before
    /// [loadFinished], the next launch knows which source was involved and
    /// does not blindly retry it (which would just crash again).
    func loadStarted(sourceId: String, name: String) {
        UserDefaults.standard.set(sourceId, forKey: loadInProgressKey)
        log("LOAD", "Started loading \"\(name)\" (\(sourceId)) - \(AppLog.memoryDescription())")
    }

    func loadFinished() {
        UserDefaults.standard.removeObject(forKey: loadInProgressKey)
    }

    // MARK: - Memory

    /// Resident footprint in MB (the figure iOS uses when deciding to kill
    /// an app), or -1 if unavailable.
    static func footprintMB() -> Int {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size) / 4
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Int(info.phys_footprint / 1_048_576) : -1
    }

    static func memoryDescription() -> String {
        let avail = os_proc_available_memory()
        let availText = avail > 0 ? "\(avail / 1_048_576)MB left before the limit" : "limit n/a"
        return "mem \(footprintMB())MB (\(availText))"
    }

    /// Logs memory every 2 seconds until the returned closure is called.
    func startMemoryTicker(_ label: String) -> () -> Void {
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + 2, repeating: 2)
        timer.setEventHandler { [weak self] in
            self?.log("MEM", "\(label): \(AppLog.memoryDescription())")
        }
        timer.resume()
        return { timer.cancel() }
    }

    // MARK: - Helpers

    static func appVersionString() -> String {
        let info = Bundle.main.infoDictionary
        let v = info?["CFBundleShortVersionString"] as? String ?? "?"
        let b = info?["CFBundleVersion"] as? String ?? "?"
        return "v\(v) (build \(b))"
    }

    /// Host only - a playlist URL can carry credentials in its query string.
    static func hostOnly(_ urlString: String) -> String {
        URL(string: urlString.trimmingCharacters(in: .whitespacesAndNewlines))?.host ?? "invalid url"
    }

    // MARK: - Crash handlers

    private func installCrashHandlers() {
        crashLogFD = open(logURL.path, O_WRONLY | O_APPEND | O_CREAT, 0o644)

        NSSetUncaughtExceptionHandler { exception in
            let text = "\n!!! UNCAUGHT EXCEPTION \(exception.name.rawValue): \(exception.reason ?? "")\n" +
                exception.callStackSymbols.prefix(25).joined(separator: "\n") + "\n"
            writeToCrashLog(text)
        }

        for sig in [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGTRAP, SIGFPE] {
            signal(sig) { s in
                let text = "\n!!! FATAL SIGNAL \(s)\n" +
                    Thread.callStackSymbols.prefix(30).joined(separator: "\n") + "\n"
                writeToCrashLog(text)
                signal(s, SIG_DFL)
                raise(s)
            }
        }
    }
}

// Globals, not members: the signal handlers above are plain C callbacks and
// cannot capture anything.
private var crashLogFD: Int32 = -1

private func writeToCrashLog(_ text: String) {
    guard crashLogFD >= 0 else { return }
    let bytes = Array(text.utf8)
    _ = bytes.withUnsafeBufferPointer { write(crashLogFD, $0.baseAddress, $0.count) }
}

/// Receives Apple's own crash/hang reports (delivered to the app on a later
/// launch) and keeps them for Settings > Logs > Crash reports.
final class MetricKitCollector: NSObject, MXMetricManagerSubscriber {
    static let shared = MetricKitCollector()

    func start() {
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let crashes = payload.crashDiagnostics?.count ?? 0
            let hangs = payload.hangDiagnostics?.count ?? 0
            AppLog.shared.log("CRASHKIT", "Received report: \(crashes) crash(es), \(hangs) hang(s)")
            let json = String(data: payload.jsonRepresentation(), encoding: .utf8) ?? "(unreadable)"
            let header = "\n===== \(Date()) - \(crashes) crash(es), \(hangs) hang(s) =====\n"
            AppLog.shared.storeCrashReport(header + json + "\n")
        }
    }

    func didReceive(_ payloads: [MXMetricPayload]) {
        // Usage metrics - not needed here, but the delegate should accept them.
    }
}
