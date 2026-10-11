import SwiftUI
import TapperCore

/// A small mark on a film or episode row: a tick once watched, or a thin bar
/// showing how far through it is.
struct WatchBadge: View {
    let channelId: String
    @ObservedObject private var watch = WatchStore.shared

    var body: some View {
        if let entry = watch.entry(channelId: channelId) {
            if entry.watched {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Color.green)
                    .accessibilityLabel("Watched")
            } else if entry.positionSec >= 30, !entry.isComplete {
                ProgressBar(fraction: entry.fraction, width: 50)
                    .accessibilityLabel("In progress")
            }
        }
    }
}

/// The Continue tab: films and shows that were stopped part-way, most recent
/// first. A film opens its detail screen (where Resume picks up where it
/// stopped); a show opens its episode list.
struct ContinueWatchingView: View {
    @ObservedObject var loader: XtreamLoader
    let onPlayMovie: (Channel) -> Void

    @ObservedObject private var watch = WatchStore.shared
    @State private var movieMap: [String: Channel] = [:]
    @State private var seriesMap: [String: Channel] = [:]

    private var entries: [WatchEntry] { watch.inProgress() }

    private var movieEntries: [WatchEntry] {
        entries.filter { $0.seriesId == nil }
    }

    /// One row per show - the episode watched most recently.
    private var showEntries: [WatchEntry] {
        var seen = Set<String>()
        var out: [WatchEntry] = []
        for entry in entries {
            guard let sid = entry.seriesId, seen.insert(sid).inserted else { continue }
            out.append(entry)
        }
        return out
    }

    /// Re-resolves the rows when the progress or the catalogue changes.
    private struct ResolveKey: Equatable {
        let revision: Int
        let movies: Int
        let shows: Int
    }

    var body: some View {
        List {
            if entries.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Nothing in progress")
                        .font(.headline)
                    Text("Films and episodes you stop part-way through show up here, ready to resume.")
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
            }

            if !movieEntries.isEmpty {
                Section("Movies") {
                    ForEach(movieEntries) { entry in
                        if let channel = movieMap[entry.channelId] {
                            NavigationLink {
                                MovieDetailView(channel: channel, onPlay: onPlayMovie)
                            } label: {
                                row(title: entry.title, subtitle: "\(entry.minutesLeft) min left", fraction: entry.fraction)
                            }
                            .swipeActions { removeAction(entry) }
                        } else {
                            row(title: entry.title, subtitle: "Loading...", fraction: entry.fraction)
                                .foregroundStyle(.secondary)
                                .swipeActions { removeAction(entry) }
                        }
                    }
                }
            }

            if !showEntries.isEmpty {
                Section("Shows") {
                    ForEach(showEntries) { entry in
                        if let sid = entry.seriesId, let series = seriesMap[sid] {
                            NavigationLink {
                                EpisodeListView(loader: loader, seriesItem: series)
                            } label: {
                                row(
                                    title: entry.seriesTitle ?? series.name,
                                    subtitle: "\(entry.title) - \(entry.minutesLeft) min left",
                                    fraction: entry.fraction
                                )
                            }
                            .swipeActions { removeAction(entry) }
                        } else {
                            row(title: entry.seriesTitle ?? entry.title, subtitle: "Loading...", fraction: entry.fraction)
                                .foregroundStyle(.secondary)
                                .swipeActions { removeAction(entry) }
                        }
                    }
                }
            }
        }
        .task(id: ResolveKey(revision: watch.revision, movies: loader.movies.count, shows: loader.series.count)) {
            await resolve()
        }
    }

    private func row(title: String, subtitle: String, fraction: Double) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.body)
                .lineLimit(1)
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            ProgressBar(fraction: fraction, width: 160)
        }
    }

    private func removeAction(_ entry: WatchEntry) -> some View {
        Button("Remove", role: .destructive) {
            watch.remove(id: entry.id)
        }
    }

    /// Finds the catalogue items the saved entries refer to. One pass over
    /// the catalogue off the main thread, checking only the few ids needed.
    private func resolve() async {
        let movieIds = Set(movieEntries.map { $0.channelId })
        let seriesIds = Set(showEntries.compactMap { $0.seriesId })
        if movieIds.isEmpty && seriesIds.isEmpty { return }
        let movies = loader.movies
        let series = loader.series
        let found = await Task.detached(priority: .userInitiated) { () -> ([String: Channel], [String: Channel]) in
            var m: [String: Channel] = [:]
            var s: [String: Channel] = [:]
            if !movieIds.isEmpty {
                for channel in movies where movieIds.contains(channel.id) {
                    m[channel.id] = channel
                    if m.count == movieIds.count { break }
                }
            }
            if !seriesIds.isEmpty {
                for channel in series where seriesIds.contains(channel.id) {
                    s[channel.id] = channel
                    if s.count == seriesIds.count { break }
                }
            }
            return (m, s)
        }.value
        movieMap = found.0
        seriesMap = found.1
    }
}
