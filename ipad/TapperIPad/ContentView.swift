import SwiftUI
import TapperCore

/// The app shell, laid out like Fire TV: the app opens straight into the
/// active source (loading it automatically), browsing is the home screen, and
/// everything about sources lives under Settings - pick, add or remove a
/// source there, set buffering, read the logs. A source menu in the toolbar
/// switches source without leaving the browse screen.
///
/// Crash safety: loading a source writes a breadcrumb (see AppLog). If the app
/// dies mid-load, the next launch does NOT blindly load that same source again
/// (which would just crash-loop); it opens to a short explanation instead, and
/// Settings > Logs holds the details.
///
/// Live/movie/playlist rows stay plain buttons that open the player. Shows are
/// the exception: a series has no stream of its own, so its row is a
/// NavigationLink into EpisodeListView.
struct ContentView: View {
    @StateObject private var playlistLoader = PlaylistLoader()
    @StateObject private var xtreamLoader = XtreamLoader()
    @Environment(\.scenePhase) private var scenePhase

    @State private var sources: [TvSource] = SourceStore.shared.all()
    @State private var activeSource: TvSource = SourceStore.shared.active()

    @State private var showSettings = false
    @State private var showSearch = false
    @State private var showAddSource = false
    @State private var addSourceBusy = false
    @State private var addSourceError: String?
    @State private var pendingSourceSave: PendingSourceSave?

    @State private var startupDone = false
    @State private var startupNotice: String?
    @State private var loadEpoch = 0

    /// Tab shown first for an Xtream source (Settings > On startup), and the
    /// tab currently shown.
    @State private var selectedTab = StartupStore.shared.startTab
    /// True only for the load that opens the app - "resume the last channel"
    /// applies to that one load, never to a source the user switches to.
    @State private var launchResumePending = false

    /// Whatever row was tapped in a live/movie/episode list - drives the
    /// full-screen player. A series row never sets this directly (it has no
    /// stream of its own); only EpisodeListView's rows and this view's own
    /// channel rows do. See PlayerView.swift for Channel's Identifiable
    /// conformance, required for `.fullScreenCover(item:)` below.
    @State private var playingChannel: Channel?
    /// What the player is showing: live TV (custom overlay, zapping through
    /// `playingList`) or a film / episode (native controls).
    @State private var playingIsLive = false
    @State private var playingList: [Channel] = []

    /// The live channel whose schedule is open.
    @State private var guideChannel: Channel?
    @ObservedObject private var reminderCenter = ReminderCenter.shared

    /// What's waiting on the in-flight load started from AddSourceView, so
    /// the onChange handlers below know what to persist once it finishes -
    /// and can tell that load apart from an ordinary source switch, which
    /// never sets this.
    private enum PendingSourceSave {
        case xtream(name: String, host: String, user: String, pass: String)
        case m3u(name: String, url: String, epg: String?)
    }

    private var isLoading: Bool { playlistLoader.isLoading || xtreamLoader.isLoading }

    private var hasContent: Bool { !playlistLoader.channels.isEmpty || xtreamLoader.isLoggedIn }

    /// Everything the active source carries, grouped by kind, for the search
    /// screen.
    private var searchGroups: [SearchGroup] {
        if !playlistLoader.channels.isEmpty {
            return [SearchGroup(title: "Channels", channels: playlistLoader.channels, namespace: "playlist")]
        }
        return [
            SearchGroup(title: "Live TV", channels: xtreamLoader.liveChannels, namespace: "live"),
            SearchGroup(title: "Movies", channels: xtreamLoader.movies, namespace: "movie"),
            SearchGroup(title: "Shows", channels: xtreamLoader.series, namespace: "series"),
        ]
    }

    private var currentError: String? {
        activeSource.kind == .xtream ? xtreamLoader.errorMessage : playlistLoader.errorMessage
    }

    var body: some View {
        NavigationStack {
            Group {
                if !playlistLoader.channels.isEmpty {
                    channelList(playlistLoader.channels, namespace: "playlist")
                } else if xtreamLoader.isLoggedIn {
                    xtreamTabs
                } else {
                    emptyState
                }
            }
            .navigationTitle(activeSource.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    sourceMenu
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
                if hasContent {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showSearch = true
                        } label: {
                            Image(systemName: "magnifyingglass")
                        }
                    }
                }
            }
        }
        .task { startupLoad() }
        .sheet(item: $guideChannel) { channel in
            ChannelGuideView(
                channel: channel,
                onPlay: { picked in
                    guideChannel = nil
                    // Let the sheet finish closing before the player opens.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        StartupStore.shared.recordLastLive(sourceId: activeSource.id, channelId: picked.id)
                        play(picked, live: true)
                    }
                },
                onClose: { guideChannel = nil }
            )
        }
        .sheet(isPresented: $showSearch) {
            SearchView(
                groups: searchGroups,
                loader: xtreamLoader,
                onClose: { showSearch = false }
            )
        }
        .sheet(isPresented: $showSettings) {
            SettingsView(
                sources: sources,
                activeId: activeSource.id,
                onSelect: { selectSource($0) },
                onRemove: { removeSource($0) },
                onAddTapped: {
                    addSourceError = nil
                    showAddSource = true
                },
                onDismiss: { showSettings = false }
            )
            .sheet(isPresented: $showAddSource) {
                AddSourceView(
                    busy: addSourceBusy,
                    error: addSourceError,
                    onSubmitXtream: { name, host, user, pass in
                        addSourceError = nil
                        addSourceBusy = true
                        pendingSourceSave = .xtream(name: name, host: host, user: user, pass: pass)
                        beginLoadBreadcrumb(id: "new-source", name: name.isEmpty ? host : name)
                        xtreamLoader.login(host: host, username: user, password: pass)
                        if !xtreamLoader.isLoading { failPendingSave(xtreamLoader.errorMessage) }
                    },
                    onSubmitM3u: { name, url, epg in
                        addSourceError = nil
                        addSourceBusy = true
                        pendingSourceSave = .m3u(name: name, url: url, epg: epg)
                        beginLoadBreadcrumb(id: "new-source", name: name.isEmpty ? "Playlist" : name)
                        playlistLoader.load(urlString: url)
                        if !playlistLoader.isLoading { failPendingSave(playlistLoader.errorMessage) }
                    },
                    onCancel: {
                        pendingSourceSave = nil
                        addSourceBusy = false
                        addSourceError = nil
                        showAddSource = false
                    }
                )
            }
        }
        // Reuses XtreamLoader's existing authenticate+load flow rather than
        // adding a separate auth-only entry point on the bridge - it already
        // validates the login and leaves the app ready to browse on success.
        // Guarded on pendingSourceSave so ordinary source switches are
        // untouched by the saving part.
        .onChange(of: xtreamLoader.isLoading) { _, loading in
            guard !loading else { return }
            loadSettled()
            if pendingSourceSave == nil {
                resumeLastIfWanted()
                openPendingReminder()
            }
            defer { DispatchQueue.main.async { activateGuide() } }
            guard case .xtream(let name, let host, let user, let pass)? = pendingSourceSave else { return }
            if xtreamLoader.isLoggedIn {
                let id = "xtream-" + String(UUID().uuidString.prefix(8))
                CredentialVault.put(sourceId: id, username: user, password: pass)
                finishAdding(
                    TvSource(id: id, name: name.isEmpty ? host : name, kind: .xtream, location: host, epgUrlOverride: nil, builtIn: false)
                )
                playlistLoader.reset()
            } else {
                failPendingSave(xtreamLoader.errorMessage ?? "Couldn't connect.")
            }
        }
        .onChange(of: playlistLoader.isLoading) { _, loading in
            guard !loading else { return }
            loadSettled()
            if pendingSourceSave == nil {
                resumeLastIfWanted()
                openPendingReminder()
            }
            defer { DispatchQueue.main.async { activateGuide() } }
            guard case .m3u(let name, let url, let epg)? = pendingSourceSave else { return }
            if !playlistLoader.channels.isEmpty {
                let id = "m3u-" + String(UUID().uuidString.prefix(8))
                finishAdding(
                    TvSource(id: id, name: name.isEmpty ? "Playlist" : name, kind: .m3u, location: url, epgUrlOverride: epg, builtIn: false)
                )
                xtreamLoader.reset()
            } else {
                failPendingSave(playlistLoader.errorMessage ?? "Couldn't load this playlist.")
            }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                AppLog.shared.log("SESSION", "Moved to background - \(AppLog.memoryDescription())")
                AppLog.shared.markBackgrounded()
                WatchStore.shared.flush()
            case .active:
                AppLog.shared.markForegrounded()
                EpgManager.shared.reloadNow()
                Task { await SyncManager.shared.syncIfStale(seconds: 20) }
            default:
                break
            }
        }
        .onChange(of: reminderCenter.pending) { _, target in
            if target != nil { openPendingReminder() }
        }
        .task {
            // Catch up with the other devices as soon as the app opens.
            await SyncManager.shared.syncIfStale(seconds: 0)
        }
        .fullScreenCover(item: $playingChannel) { channel in
            PlayerView(
                channel: channel,
                playlist: playingList,
                isLive: playingIsLive,
                onZap: { zapped in
                    StartupStore.shared.recordLastLive(sourceId: activeSource.id, channelId: zapped.id)
                }
            )
        }
    }

    // MARK: - Loading

    /// Opens straight into the active source - unless the previous run died
    /// while loading it, in which case it waits for the user instead of
    /// crash-looping.
    private func startupLoad() {
        guard !startupDone else { return }
        startupDone = true
        let active = SourceStore.shared.active()
        activeSource = active

        if let badId = AppLog.shared.interruptedLoadSourceId {
            let badName = SourceStore.shared.all().first { $0.id == badId }?.name ?? badId
            AppLog.shared.log("SESSION", "Auto-load skipped: last run died while loading \"\(badName)\"")
            if badId == active.id || badId == "new-source" {
                startupNotice = "The app closed unexpectedly while loading \"\(badName)\", so it was not loaded automatically this time. You can try it again, choose a different source, or open Settings > Logs to see what happened."
                return
            }
        }
        launchResumePending = true
        loadSource(active)
    }

    /// "On startup: resume the last channel". Runs when the launch load
    /// settles; plays the live channel last started from the lists if it
    /// belonged to this source and is still in it. Anything else (a failed
    /// load, a different source, the channel gone) just leaves the list.
    private func resumeLastIfWanted() {
        guard launchResumePending else { return }
        launchResumePending = false
        // A tapped reminder takes the place of resuming the last channel.
        guard reminderCenter.pending == nil else { return }
        guard StartupStore.shared.resumeLast,
              let last = StartupStore.shared.lastLive,
              last.sourceId == activeSource.id else { return }
        // The loaders publish isLoading a moment before the channels land.
        DispatchQueue.main.async {
            guard playingChannel == nil else { return }
            let pool = playlistLoader.channels.isEmpty ? xtreamLoader.liveChannels : playlistLoader.channels
            guard let channel = pool.first(where: { $0.id == last.channelId }), channel.isPlayable else {
                AppLog.shared.log("SESSION", "Resume: last channel not found in this source")
                return
            }
            AppLog.shared.log("SESSION", "Resuming \"\(channel.name)\"")
            play(channel, live: true, list: pool.filter { $0.isPlayable })
        }
    }

    /// A reminder was tapped: play that channel. If it belongs to another
    /// source, switch to it first and finish when its load settles; if the
    /// channels haven't arrived yet, wait for the next load to settle.
    private func openPendingReminder() {
        guard let target = reminderCenter.pending else { return }
        if target.sourceId != activeSource.id {
            guard let source = SourceStore.shared.all().first(where: { $0.id == target.sourceId }) else {
                reminderCenter.pending = nil
                return
            }
            selectSource(source)
            return
        }
        DispatchQueue.main.async {
            let pool = playlistLoader.channels.isEmpty ? xtreamLoader.liveChannels : playlistLoader.channels
            if pool.isEmpty { return }
            reminderCenter.pending = nil
            guard let channel = pool.first(where: { $0.id == target.channelId }), channel.isPlayable else {
                AppLog.shared.log("REMINDER", "Reminder channel not found in this source")
                return
            }
            AppLog.shared.log("REMINDER", "Opening \"\(channel.name)\" from a reminder")
            play(channel, live: true, list: pool.filter { $0.isPlayable })
        }
    }

    /// Opens the player. For live TV, `list` is what channel up / down and
    /// the in-player channel list cycle through.
    private func play(_ channel: Channel, live: Bool, list: [Channel] = []) {
        playingIsLive = live
        playingList = list
        playingChannel = channel
    }

    /// A source finished loading: hand its live channels to the guide, which
    /// shows what is already stored and refreshes it in the background if it
    /// is old.
    private func activateGuide() {
        let live: [Channel]
        if !playlistLoader.channels.isEmpty {
            live = playlistLoader.channels
        } else if xtreamLoader.isLoggedIn {
            live = xtreamLoader.liveChannels
        } else {
            return
        }
        EpgManager.shared.activate(
            source: activeSource,
            declaredEpgUrls: playlistLoader.declaredEpgUrls,
            liveChannels: live
        )
    }

    private func beginLoadBreadcrumb(id: String, name: String) {
        loadEpoch += 1
        AppLog.shared.loadStarted(sourceId: id, name: name)
    }

    private func loadSource(_ source: TvSource) {
        startupNotice = nil
        beginLoadBreadcrumb(id: source.id, name: source.name)
        switch source.kind {
        case .m3u:
            playlistLoader.load(urlString: source.location)
            if !playlistLoader.isLoading { AppLog.shared.loadFinished() }
        case .xtream:
            guard let creds = CredentialVault.get(sourceId: source.id) else {
                AppLog.shared.log("LOAD", "No saved login found for \"\(source.name)\"")
                AppLog.shared.loadFinished()
                xtreamLoader.errorMessage = "The saved login for this source is missing. Remove it in Settings and add it again."
                return
            }
            xtreamLoader.login(host: source.location, username: creds.username, password: creds.password)
            if !xtreamLoader.isLoading { AppLog.shared.loadFinished() }
        }
    }

    /// A load finished (well or badly). The breadcrumb is kept for a few more
    /// seconds: the heavy part for a big source is often drawing it, right
    /// after the data arrives, and a crash then should still be attributed to
    /// this load.
    private func loadSettled() {
        let epoch = loadEpoch
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
            if epoch == loadEpoch { AppLog.shared.loadFinished() }
        }
    }

    private func finishAdding(_ source: TvSource) {
        SourceStore.shared.add(source)
        SourceStore.shared.activeId = source.id
        sources = SourceStore.shared.all()
        activeSource = source
        pendingSourceSave = nil
        addSourceBusy = false
        addSourceError = nil
        startupNotice = nil
        showAddSource = false
        showSettings = false
    }

    private func failPendingSave(_ message: String?) {
        addSourceBusy = false
        addSourceError = message ?? "Couldn't connect."
        pendingSourceSave = nil
    }

    /// Switches the active saved source and loads it - M3U just needs its
    /// stored URL, Xtream needs its Keychain-held credentials alongside the
    /// stored host.
    private func selectSource(_ source: TvSource) {
        SourceStore.shared.activeId = source.id
        activeSource = source
        launchResumePending = false
        showSettings = false
        EpgManager.shared.deactivate()
        playlistLoader.reset()
        xtreamLoader.reset()
        loadSource(source)
    }

    private func removeSource(_ source: TvSource) {
        SourceStore.shared.remove(id: source.id)
        CredentialVault.delete(sourceId: source.id)
        sources = SourceStore.shared.all()
        if source.id == activeSource.id {
            let next = SourceStore.shared.active()
            selectSource(next)
        }
    }

    // MARK: - Screens

    private var sourceMenu: some View {
        Menu {
            ForEach(sources) { source in
                Button {
                    selectSource(source)
                } label: {
                    if source.id == activeSource.id {
                        Label(source.name, systemImage: "checkmark")
                    } else {
                        Text(source.name)
                    }
                }
            }
            Divider()
            Button {
                showSettings = true
            } label: {
                Label("Manage sources...", systemImage: "gearshape")
            }
        } label: {
            Label("Sources", systemImage: "tray.full")
        }
    }

    /// Shown while nothing is loaded: loading progress, or what went wrong,
    /// with the way forward either way.
    private var emptyState: some View {
        VStack(spacing: 18) {
            if isLoading {
                ProgressView()
                    .controlSize(.large)
                Text("Loading \(activeSource.name)...")
                    .font(.headline)
                Text("A large provider can take a minute or two.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if !xtreamLoader.warnings.isEmpty {
                    Text(xtreamLoader.warnings.joined(separator: "\n"))
                        .font(.caption2)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 520)
                }
            } else {
                Image(systemName: "tv")
                    .font(.system(size: 56))
                    .foregroundStyle(.secondary)
                Text("Tapper IPTV")
                    .font(.largeTitle.bold())

                if let startupNotice {
                    Text(startupNotice)
                        .font(.callout)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 520)
                } else if let error = currentError {
                    Text(error)
                        .font(.callout)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 520)
                } else {
                    Text("Choose a source to start watching.")
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 16) {
                    Button("Load \(activeSource.name)") {
                        loadSource(activeSource)
                    }
                    .buttonStyle(.borderedProminent)

                    Button("Sources & Logs") {
                        showSettings = true
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var xtreamTabs: some View {
        VStack(spacing: 8) {
            if let account = xtreamLoader.account {
                Text(account.summary(nowUtc: Int64(Date().timeIntervalSince1970 * 1000)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
            }
            if !xtreamLoader.warnings.isEmpty {
                Text(xtreamLoader.warnings.joined(separator: "\n"))
                    .font(.caption2)
                    .foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            TabView(selection: $selectedTab) {
                channelList(xtreamLoader.liveChannels, namespace: "live")
                    .tabItem { Label("Live", systemImage: "tv") }
                    .tag(0)
                channelList(xtreamLoader.movies, namespace: "movie")
                    .tabItem { Label("Movies", systemImage: "film") }
                    .tag(1)
                seriesList
                    .tabItem { Label("Shows", systemImage: "tv.badge.wifi") }
                    .tag(2)
                ContinueWatchingView(loader: xtreamLoader, onPlayMovie: { play($0, live: false) })
                    .tabItem { Label("Continue", systemImage: "clock.arrow.circlepath") }
                    .tag(3)
            }
        }
    }

    // A series row has no stream of its own (XtreamLoader.loadEpisodes
    // fetches its episodes on demand), so it stays a NavigationLink into
    // EpisodeListView rather than a play button - CategoryFilteredList only
    // takes over the category chips/grouping here, not the tap behavior.
    // FavoriteButton sits outside the NavigationLink in its own HStack slot
    // so starring a show doesn't also push EpisodeListView.
    private var seriesList: some View {
        CategoryFilteredList(channels: xtreamLoader.series, categoryNamespace: "series") { item, _ in
            HStack {
                NavigationLink {
                    EpisodeListView(loader: xtreamLoader, seriesItem: item)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name).font(.body)
                        if let group = item.group {
                            Text(group).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer()
                FavoriteButton(sourceId: item.sourceId, channelId: item.id)
            }
        }
    }

    // Live and movie rows do have a stream, so - unlike seriesList above -
    // each row is a plain Button that opens PlayerView directly rather than
    // navigating anywhere. isPlayable guards against the handful of
    // mms/mmsh/rtmp/rtsp entries M3uParser already flags as unplayable so
    // tapping one shows a greyed-out row instead of a silent no-op.
    // categoryNamespace is a plain String (not Channel.kind) because the
    // same function serves Live, Movies and the M3U playlist list, and this
    // avoids relying on how a Kotlin enum bridges into Swift for something
    // that's purely a UserDefaults storage key here.
    private func channelRowLabel(_ channel: Channel) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(channel.name)
                .font(.body)
                .foregroundStyle(channel.isPlayable ? Color.primary : Color.secondary)
            if let group = channel.group {
                Text(group)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func channelList(_ channels: [Channel], namespace: String) -> some View {
        CategoryFilteredList(channels: channels, categoryNamespace: namespace) { channel, shown in
            HStack {
                if namespace == "movie" {
                    // A film opens its detail screen (poster, synopsis,
                    // rating from TMDb) with Play on it, rather than
                    // starting at once.
                    NavigationLink {
                        MovieDetailView(channel: channel, onPlay: { play($0, live: false) })
                    } label: {
                        HStack(spacing: 10) {
                            channelRowLabel(channel)
                            Spacer()
                            WatchBadge(channelId: channel.id)
                        }
                    }
                } else {
                    let isLive = namespace == "live" || namespace == "playlist"
                    Button {
                        // Remembered for "Resume the last channel" - live
                        // channels only; a film or episode is not something
                        // to jump back into at launch.
                        if isLive {
                            StartupStore.shared.recordLastLive(sourceId: activeSource.id, channelId: channel.id)
                        }
                        play(channel, live: isLive, list: isLive ? shown : [])
                    } label: {
                        if isLive {
                            // What is on now, from the guide.
                            LiveRowLabel(channel: channel)
                        } else {
                            channelRowLabel(channel)
                        }
                    }
                    .disabled(!channel.isPlayable)
                    if isLive {
                        Button {
                            guideChannel = channel
                        } label: {
                            Image(systemName: "list.bullet.rectangle")
                                .foregroundStyle(Color.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                Spacer()
                FavoriteButton(sourceId: channel.sourceId, channelId: channel.id)
            }
        }
    }
}

#Preview {
    ContentView()
}
