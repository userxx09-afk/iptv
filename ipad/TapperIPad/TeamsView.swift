import SwiftUI
import TapperCore

/// The teams picked for My Teams, kept on this iPad - the counterpart to
/// Fire TV's TeamsStore (same fields, same limit of 12).
final class TeamsStore: ObservableObject {
    static let shared = TeamsStore()
    static let max = 12

    private struct Saved: Codable {
        let league: String
        let id: String
        let name: String
        let short: String
        let abbr: String
        let logo: String?
    }

    private let key = "tapper.teams"
    @Published private(set) var teams: [Team] = []

    private init() { teams = load() }

    private func load() -> [Team] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let saved = try? JSONDecoder().decode([Saved].self, from: data) else { return [] }
        return saved.compactMap { s in
            guard let league = SportsBridge.shared.leagueNamed(name: s.league) else { return nil }
            return Team(league: league, id: s.id, name: s.name, shortName: s.short, abbr: s.abbr, logoUrl: s.logo)
        }
    }

    private func save() {
        let saved = teams.map {
            Saved(league: $0.league.name, id: $0.id, name: $0.name, short: $0.shortName, abbr: $0.abbr, logo: $0.logoUrl)
        }
        if let data = try? JSONEncoder().encode(saved) { UserDefaults.standard.set(data, forKey: key) }
    }

    func has(_ team: Team) -> Bool { teams.contains { $0.league == team.league && $0.id == team.id } }

    /// False when the limit is reached.
    @discardableResult
    func toggle(_ team: Team) -> Bool {
        if has(team) {
            teams.removeAll { $0.league == team.league && $0.id == team.id }
        } else {
            guard teams.count < Self.max else { return false }
            teams.append(team)
        }
        save()
        return true
    }
}

/// One game and the channels that probably carry it.
private struct MatchedGame: Identifiable {
    let game: Game
    let hits: [ChannelMatcher.Hit]
    var id: String { game.id }
}

/// My Teams: your teams' next games (two weeks ahead, from ESPN) and which
/// of your channels has each one - from the guide, and from the TV network
/// the game is on. The iPad version of Fire TV's TeamsScreen.
struct TeamsView: View {
    let channels: [Channel]
    let sourceId: String
    let onWatch: (Channel) -> Void
    let onClose: () -> Void

    @ObservedObject private var store = TeamsStore.shared
    @ObservedObject private var reminders = ReminderStore.shared
    @State private var games: [MatchedGame]?
    @State private var errors: [String] = []
    @State private var picking = false
    @State private var selected: MatchedGame?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        picking = true
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Edit my teams")
                            Text(store.teams.isEmpty ? "No teams yet" : store.teams.map(\.name).joined(separator: ", "))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Section("Next games") {
                    if store.teams.isEmpty {
                        Text("Pick your teams to see their games here.").foregroundStyle(.secondary)
                    } else if let games {
                        if games.isEmpty {
                            Text("No games in the next two weeks." + (errors.first.map { " (\($0))" } ?? ""))
                                .foregroundStyle(.secondary)
                        }
                        ForEach(games) { m in
                            Button { selected = m } label: { row(m) }
                                .foregroundStyle(.primary)
                        }
                    } else {
                        HStack { ProgressView(); Text("Getting the schedules...").foregroundStyle(.secondary) }
                    }
                }
                if !errors.isEmpty, !(games ?? []).isEmpty {
                    Section { Text(errors.joined(separator: "\n")).font(.caption).foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("My Teams")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: onClose) }
                ToolbarItem(placement: .topBarLeading) {
                    Button { load() } label: { Image(systemName: "arrow.clockwise") }
                }
            }
            .sheet(isPresented: $picking, onDismiss: load) { TeamPickerView() }
            .confirmationDialog(selected?.game.title ?? "", isPresented: Binding(
                get: { selected != nil }, set: { if !$0 { selected = nil } }
            ), titleVisibility: .visible) {
                if let m = selected { actions(m) }
            } message: {
                if let m = selected { Text(details(m)) }
            }
        }
        .task { load() }
    }

    private func row(_ m: MatchedGame) -> some View {
        HStack(spacing: 12) {
            ForEach([m.game.away.logoUrl, m.game.home.logoUrl].compactMap { $0 }, id: \.self) { logo in
                AsyncImage(url: URL(string: logo)) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                    .frame(width: 28, height: 28)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(m.game.title).font(.body.weight(.semibold))
                    if m.game.state == GameState.live {
                        Text("LIVE").font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.red.opacity(0.2)).foregroundStyle(.red).clipShape(Capsule())
                    }
                }
                Text(whenText(m.game)).font(.caption).foregroundStyle(.secondary)
                Text(whereText(m)).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func actions(_ m: MatchedGame) -> some View {
        let soon = m.game.state == GameState.live ||
            Double(m.game.startUtc) / 1000 - Date().timeIntervalSince1970 < 15 * 60
        ForEach(Array(m.hits.enumerated()), id: \.offset) { _, hit in
            if soon {
                Button("Watch on \(hit.channel.name)") { onWatch(hit.channel) }
            } else {
                Button("Remind me on \(hit.channel.name)") { remind(m.game, hit) }
            }
        }
        Button("Cancel", role: .cancel) {}
    }

    private func details(_ m: MatchedGame) -> String {
        let tv = m.game.broadcasts.filter { !$0.streaming }.map(\.name).joined(separator: ", ")
        let web = m.game.broadcasts.filter { $0.streaming }.map(\.name).joined(separator: ", ")
        var lines = ["\(m.game.league.label) · \(whenText(m.game))",
                     tv.isEmpty ? "TV network not announced yet" : "On TV: \(tv)"]
        if !web.isEmpty { lines.append("Streaming: \(web) (not a channel here)") }
        if m.hits.isEmpty { lines.append("None of your channels look like they have this game.") }
        return lines.joined(separator: "\n")
    }

    private func remind(_ game: Game, _ hit: ChannelMatcher.Hit) {
        let start = Int((hit.match.listing?.startUtc ?? game.startUtc) / 1000)
        let end = Int((hit.match.listing?.endUtc ?? game.endGuessUtc) / 1000)
        let programme = EpgProgramme(channelId: hit.channel.epgChannelId ?? hit.channel.id, start: start, end: end,
                                     title: hit.match.listing?.title ?? "\(game.league.label): \(game.title)",
                                     description: nil)
        Task { _ = await reminders.toggle(channel: hit.channel, programme: programme) }
    }

    private func whenText(_ g: Game) -> String {
        let date = Date(timeIntervalSince1970: Double(g.startUtc) / 1000)
        var score = ""
        if let h = g.home.score, let a = g.away.score { score = "\(g.away.abbr) \(a), \(g.home.abbr) \(h)" }
        if g.state == GameState.live { return "Live · \(g.detail)" + (score.isEmpty ? "" : " · \(score)") }
        if g.state != GameState.pre { return (g.detail.isEmpty ? "Final" : g.detail) + (score.isEmpty ? "" : " · \(score)") }
        if g.timeTbd { return date.formatted(.dateTime.weekday().day().month()) + ", time to be announced" }
        return date.formatted(.dateTime.weekday().day().month().hour().minute())
    }

    private func whereText(_ m: MatchedGame) -> String {
        let tv = m.game.broadcasts.map(\.name).joined(separator: ", ")
        if let best = m.hits.first, best.match.score >= 70 {
            return best.channel.name + (tv.isEmpty ? "" : " · \(tv)")
        }
        return tv.isEmpty ? "TV network not announced yet" : tv
    }

    private func load() {
        let teams = store.teams
        guard !teams.isEmpty else { games = []; return }
        games = nil
        let live = channels.filter { $0.kind == ContentKind.live }
        let source = sourceId
        let epgIds = Dictionary(live.map { ($0.id, EpgDatabase.normalizeId($0.epgChannelId)) }, uniquingKeysWith: { a, _ in a })
        SportsBridge.shared.games(teams: teams, nowUtcMs: Int64(Date().timeIntervalSince1970 * 1000)) { schedule, error in
            let found = schedule?.games ?? []
            let matched: [MatchedGame] = found.map { g in
                let from = Int(g.startUtc / 1000) - 2 * 3600
                let rows = EpgDatabase.shared.startingBetween(source, from: from, to: Int(g.startUtc / 1000) + 75 * 60)
                let listings = rows.mapValues { list in
                    list.map { Listing(startUtc: Int64($0.start) * 1000, endUtc: Int64($0.end) * 1000,
                                       title: $0.title, description: $0.description) }
                }
                let hits = ChannelMatcher.shared.matchChannels(game: g, channels: live, epgIds: epgIds, listings: listings)
                return MatchedGame(game: g, hits: hits)
            }
            let errs = schedule?.errors ?? (error.map { [$0] } ?? [])
            DispatchQueue.main.async {
                games = matched
                errors = errs
            }
        }
    }
}

/// Pick teams, league by league.
private struct TeamPickerView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = TeamsStore.shared
    @State private var league: League = SportsBridge.shared.leagues().first!
    @State private var teams: [Team]?
    @State private var error: String?
    @State private var full = false

    var body: some View {
        NavigationStack {
            List {
                Picker("League", selection: $league) {
                    ForEach(SportsBridge.shared.leagues(), id: \.name) { l in Text(l.label).tag(l) }
                }
                if let teams {
                    ForEach(teams, id: \.id) { team in
                        Button {
                            if !store.toggle(team) { full = true }
                        } label: {
                            HStack {
                                if let logo = team.logoUrl, let url = URL(string: logo) {
                                    AsyncImage(url: url) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                                        .frame(width: 28, height: 28)
                                }
                                Text(team.name)
                                Spacer()
                                if store.has(team) { Image(systemName: "checkmark").foregroundStyle(.blue) }
                            }
                        }
                        .foregroundStyle(.primary)
                    }
                } else if let error {
                    Text(error).foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("My teams")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .alert("Up to \(TeamsStore.max) teams", isPresented: $full) { Button("OK", role: .cancel) {} }
        }
        .task(id: league.name) {
            teams = nil
            error = nil
            let picked = league
            SportsBridge.shared.teams(league: picked, nowUtcMs: Int64(Date().timeIntervalSince1970 * 1000)) { list, err in
                DispatchQueue.main.async {
                    guard picked == league else { return }
                    teams = list
                    error = err
                }
            }
        }
    }
}
