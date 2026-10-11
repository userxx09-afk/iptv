import SwiftUI

/// Settings, in the spirit of Fire TV's SettingsScreen: pick (or add / remove)
/// the source, set playback buffering, and look at the logs - which is how a
/// problem such as "the app dies while loading a source" gets diagnosed
/// without a Mac attached.
struct SettingsView: View {
    let sources: [TvSource]
    let activeId: String
    let onSelect: (TvSource) -> Void
    let onRemove: (TvSource) -> Void
    let onAddTapped: () -> Void
    let onDismiss: () -> Void

    @State private var buffer = PlayerSettingsStore.shared.bufferSize

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(sources) { source in
                        Button {
                            onSelect(source)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(source.name)
                                    Text(source.kind == .xtream ? "Xtream" : "M3U playlist")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if source.id == activeId {
                                    Image(systemName: "checkmark").foregroundStyle(.blue)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                        .swipeActions {
                            if !source.builtIn {
                                Button("Remove", role: .destructive) { onRemove(source) }
                            }
                        }
                    }
                    Button {
                        onAddTapped()
                    } label: {
                        Label("Add a source", systemImage: "plus")
                    }
                } header: {
                    Text("Sources")
                } footer: {
                    Text("Tap a source to load it. Swipe left on one to remove it.")
                }

                Section {
                    Picker("Buffering", selection: $buffer) {
                        ForEach(BufferSize.allCases) { size in
                            Text(size.label).tag(size)
                        }
                    }
                    .onChange(of: buffer) { _, newValue in
                        PlayerSettingsStore.shared.bufferSize = newValue
                    }
                } header: {
                    Text("Playback")
                } footer: {
                    Text(buffer.description + " Takes effect the next time something is played.")
                }

                Section {
                    NavigationLink {
                        LogViewerView(mode: .app)
                    } label: {
                        Label("App log", systemImage: "doc.text")
                    }
                    NavigationLink {
                        LogViewerView(mode: .crash)
                    } label: {
                        Label("Crash reports", systemImage: "exclamationmark.triangle")
                    }
                } header: {
                    Text("Logs")
                } footer: {
                    if AppLog.shared.previousSessionEndedUnexpectedly {
                        Text("The last run of the app ended unexpectedly. Open the App log and look at the last lines before the break.")
                    } else {
                        Text("Loading, errors and memory use are recorded here, and survive a crash.")
                    }
                }

                Section("About") {
                    HStack {
                        Text("Tapper IPTV")
                        Spacer()
                        Text(AppLog.appVersionString()).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDismiss)
                }
            }
        }
    }
}

/// Shows one of the two logs, with copy / share / clear.
struct LogViewerView: View {
    enum Mode {
        case app
        case crash
    }

    let mode: Mode

    @State private var text = ""
    @State private var exportURL: URL?
    @State private var confirmClear = false
    @State private var copied = false

    /// A very long log is shown as its most recent part - rendering hundreds
    /// of KB in one Text is slow, and the end is what matters. Copy and Share
    /// always use the full thing.
    private let displayLimit = 60_000

    private var title: String { mode == .app ? "App log" : "Crash reports" }

    private var shownText: String {
        if text.isEmpty {
            return mode == .app
                ? "Nothing logged yet."
                : "No crash reports yet.\n\nAfter a crash, iOS hands a crash report to the app the next time it is opened (it can take up to a day to arrive). It will appear here.\n\nThe App log also records a stack trace at the moment of most crashes."
        }
        if text.count > displayLimit {
            return "... (older lines hidden - Copy or Share includes everything)\n" + String(text.suffix(displayLimit))
        }
        return text
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                Text(shownText)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                Color.clear.frame(height: 1).id("end")
            }
            .onAppear {
                reload()
                proxy.scrollTo("end", anchor: .bottom)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    reload()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                Button {
                    UIPasteboard.general.string = text
                    copied = true
                } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                }
                if let exportURL {
                    ShareLink(item: exportURL)
                }
                Button(role: .destructive) {
                    confirmClear = true
                } label: {
                    Image(systemName: "trash")
                }
            }
        }
        .confirmationDialog("Clear this log?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) {
                if mode == .app { AppLog.shared.clearLog() } else { AppLog.shared.clearCrashReports() }
                reload()
            }
        }
    }

    private func reload() {
        text = mode == .app ? AppLog.shared.readLog() : AppLog.shared.readCrashReports()
        exportURL = AppLog.shared.exportFileURL()
        copied = false
    }
}
