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
    @State private var mobileQuality = PlayerSettingsStore.shared.mobileQuality
    @State private var muteMinutes = PlayerSettingsStore.shared.muteMinutes
    @State private var tmdbKey = MovieMetadataStore.shared.apiKey
    @State private var infoCleared = false
    @State private var confirmClearWatch = false
    @ObservedObject private var startup = StartupStore.shared
    @State private var resumeLast = StartupStore.shared.resumeLast
    @State private var startTab = StartupStore.shared.startTab

    private let homeNamespaces = ["live", "movie", "series", "playlist"]

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
                    Picker("When the app opens", selection: $resumeLast) {
                        Text("Show the channel list").tag(false)
                        Text("Resume the last channel").tag(true)
                    }
                    .onChange(of: resumeLast) { _, newValue in
                        StartupStore.shared.resumeLast = newValue
                    }
                    Picker("Open on", selection: $startTab) {
                        Text("Live").tag(0)
                        Text("Movies").tag(1)
                        Text("Shows").tag(2)
                    }
                    .onChange(of: startTab) { _, newValue in
                        StartupStore.shared.startTab = newValue
                    }
                } header: {
                    Text("On startup")
                } footer: {
                    Text("Resume plays the live channel you last started, on the source that is loaded when the app opens. \"Open on\" is the tab shown first for an Xtream source.")
                }

                Section {
                    let saved = homeNamespaces.compactMap { ns in
                        startup.home(for: ns).map { (ns, $0) }
                    }
                    if saved.isEmpty {
                        Text("None set")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(saved, id: \.0) { entry in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(listLabel(entry.0))
                                Text(homeSummary(entry.1))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Clear", role: .destructive) {
                                StartupStore.shared.clearHome(for: entry.0)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                } header: {
                    Text("Starting points")
                } footer: {
                    Text("A starting point is the country and category a list opens on. Choose them in a list, then tap the \u{22EF} button beside them and \"Make this my starting point\". With none saved, lists open on United States when the source has it - save \"All Countries\" as the starting point to see everything instead.")
                }

                Section {
                    NavigationLink {
                        GuideSettingsView()
                    } label: {
                        Label("Program guide", systemImage: "list.bullet.rectangle")
                    }
                } header: {
                    Text("Guide")
                } footer: {
                    Text("What's on now beside every live channel, schedules, and extra listings such as Marquee Sports Network.")
                }

                Section {
                    TextField("TMDb API key or token", text: $tmdbKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                        .onChange(of: tmdbKey) { _, newValue in
                            MovieMetadataStore.shared.apiKey = newValue
                        }
                    if tmdbKey != MovieMetadataStore.builtInKey {
                        Button("Use the built-in key") {
                            MovieMetadataStore.shared.resetToBuiltInKey()
                            tmdbKey = MovieMetadataStore.shared.apiKey
                        }
                    }
                    if !tmdbKey.isEmpty {
                        Button("Remove key", role: .destructive) {
                            tmdbKey = ""
                        }
                    }
                    Button(infoCleared ? "Saved info cleared" : "Clear saved info") {
                        Task {
                            await MovieMetadataStore.shared.clearCache()
                            infoCleared = true
                        }
                    }
                } header: {
                    Text("Movie & show info")
                } footer: {
                    Text("Posters, synopses and ratings come from TMDb and show when you open a movie or a show. Get a free key at themoviedb.org/settings/api - either the API Key or the Read Access Token works. This product uses the TMDB API but is not endorsed or certified by TMDB.")
                }

                Section {
                    Button("Refresh channel list") {
                        // Forget the saved copy, then load the source again
                        // from the server.
                        CatalogueCache.clearAll()
                        if let current = sources.first(where: { $0.id == activeId }) {
                            onSelect(current)
                        }
                    }
                } header: {
                    Text("Library")
                } footer: {
                    Text("Your channels, movies and shows are saved on this iPad for a day, so the app opens quickly even with a poor connection. Refresh to download them again now, for example after your provider adds something.")
                }

                Section {
                    NavigationLink {
                        RemindersView()
                    } label: {
                        HStack {
                            Label("Reminders", systemImage: "bell")
                            Spacer()
                            Text("\(ReminderStore.shared.upcoming().count)")
                                .foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text("Get a notification when a programme you picked in a channel's schedule is about to start.")
                }

                Section {
                    NavigationLink {
                        SyncSettingsView()
                    } label: {
                        HStack {
                            Label("Sync with other devices", systemImage: "arrow.triangle.2.circlepath")
                            Spacer()
                            Text(SyncConfig.isConfigured ? "On" : "Off")
                                .foregroundStyle(.secondary)
                        }
                    }
                    Button("Clear watch history", role: .destructive) {
                        confirmClearWatch = true
                    }
                } header: {
                    Text("Watch history")
                } footer: {
                    Text("Films and episodes remember where you stopped, and show under Continue. Turn on sync to resume them on your Fire TV, and the other way round.")
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
                    Picker("Mobile data", selection: $mobileQuality) {
                        ForEach(MobileQuality.allCases) { option in
                            Text(option.label).tag(option)
                        }
                    }
                    .onChange(of: mobileQuality) { _, newValue in
                        PlayerSettingsStore.shared.mobileQuality = newValue
                    }
                    Picker("Commercial mute", selection: $muteMinutes) {
                        ForEach(PlayerSettingsStore.muteChoices, id: \.self) { m in
                            Text("\(m) min").tag(m)
                        }
                    }
                    .onChange(of: muteMinutes) { _, newValue in
                        PlayerSettingsStore.shared.muteMinutes = newValue
                    }
                } header: {
                    Text("Playback")
                } footer: {
                    Text("The mute button on live TV silences the channel for \(muteMinutes) \(muteMinutes == 1 ? "minute" : "minutes"), then the sound returns by itself. ")
                    + Text(buffer.description + " " + mobileQuality.description + " Takes effect the next time something is played. On mobile data the app always keeps at least 20 seconds of cushion, and reconnects by itself when you move between networks.")
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
            .confirmationDialog("Clear all watch history?", isPresented: $confirmClearWatch, titleVisibility: .visible) {
                Button("Clear", role: .destructive) { WatchStore.shared.clearAll() }
            }
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
