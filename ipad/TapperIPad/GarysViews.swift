import SwiftUI
import TapperCore

// The GarysTV interface (Settings > Interface > GarysTV): the GarysTV DVR's
// screens rebuilt for the iPad - its top bar, Home tiles, DISH-style guide
// grid, and a list + detail layout for Movies and Shows. Sizes follow
// GarysTV's styles.css, in rem (see G.rem). Fire TV's version is
// ui/GarysScreens.kt.

enum G {
    static let rem: CGFloat = 14

    static let bg = GarysPalette.bg
    static let bg2 = GarysPalette.bg2
    static let panel = GarysPalette.panel
    static let panel2 = Color(red: 0x13 / 255, green: 0x23 / 255, blue: 0x3d / 255)
    static let cell = Color(red: 0x12 / 255, green: 0x21 / 255, blue: 0x3a / 255)
    static let cellAlt = Color(red: 0x0f / 255, green: 0x1c / 255, blue: 0x32 / 255)
    static let line = Color(red: 0x22 / 255, green: 0x38 / 255, blue: 0x5c / 255)
    static let text = Color(red: 0xee / 255, green: 0xf3 / 255, blue: 0xfb / 255)
    static let muted = Color(red: 0x93 / 255, green: 0xa6 / 255, blue: 0xc4 / 255)
    static let dim = Color(red: 0x5d / 255, green: 0x71 / 255, blue: 0x94 / 255)
    static let sub = Color(red: 0xcf / 255, green: 0xe0 / 255, blue: 0xff / 255)
    static let desc = Color(red: 0xc4 / 255, green: 0xd1 / 255, blue: 0xe6 / 255)
    static let focusBg = Color(red: 0xe9 / 255, green: 0xf0 / 255, blue: 0xfb / 255)
    static let focusText = Color(red: 0x08 / 255, green: 0x11 / 255, blue: 0x1f / 255)
    static let focusSub = Color(red: 0x3b / 255, green: 0x4a / 255, blue: 0x63 / 255)
    static let red = GarysPalette.ring
    static let red2 = GarysPalette.red
    static let yellow = Color(red: 0xf5 / 255, green: 0xc5 / 255, blue: 0x18 / 255)

    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size * rem, weight: weight)
    }

    static let slot = 30 * 60
    static let span = 3 * slot
    static func align(_ t: Int) -> Int { t / slot * slot }
    static var now: Int { Int(Date().timeIntervalSince1970) }

    static func time(_ t: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(t)).formatted(date: .omitted, time: .shortened)
    }

    static func day(_ t: Int) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(t))
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "Today" }
        if cal.isDateInTomorrow(date) { return "Tomorrow" }
        return date.formatted(.dateTime.weekday(.wide))
    }
}

/// The GarysTV logo in its red ring.
struct GLogo: View {
    let size: CGFloat
    var ring: CGFloat = 2

    var body: some View {
        Image("GarysLogo")
            .resizable()
            .scaledToFill()
            .frame(width: size, height: size)
            .clipShape(Circle())
            .overlay(Circle().stroke(G.red, lineWidth: ring))
            .shadow(color: G.red.opacity(0.35), radius: size * 0.25)
            .accessibilityHidden(true)
    }
}

/// GarysTV's `.nav` pill and `.chip`: white while pressed, red when on.
struct GPillStyle: ButtonStyle {
    var on = false

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        configuration.label
            .font(G.font(0.95, .semibold))
            .lineLimit(1)
            .foregroundStyle(pressed ? G.focusText : G.text)
            .padding(.horizontal, G.rem * 0.9)
            .padding(.vertical, G.rem * 0.35)
            .background(Capsule().fill(pressed ? G.focusBg : (on ? G.red : G.panel)))
            .overlay(Capsule().stroke(pressed ? G.focusBg : (on ? G.red : G.line), lineWidth: 1.5))
    }
}

/// The pill look for things that aren't Buttons (a Menu's label).
struct GPillLabel: View {
    let text: String
    var on = false

    var body: some View {
        Text(text)
            .font(G.font(0.95, .semibold))
            .lineLimit(1)
            .foregroundStyle(G.text)
            .padding(.horizontal, G.rem * 0.9)
            .padding(.vertical, G.rem * 0.35)
            .background(Capsule().fill(on ? G.red : G.panel))
            .overlay(Capsule().stroke(on ? G.red : G.line, lineWidth: 1.5))
    }
}

/// A Home tile or card: lifts and turns white while pressed.
struct GPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .environment(\.gPressed, configuration.isPressed)
            .scaleEffect(configuration.isPressed ? 1.03 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct GPressedKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var gPressed: Bool {
        get { self[GPressedKey.self] }
        set { self[GPressedKey.self] = newValue }
    }
}

// MARK: - Root

struct GarysRootView: View {
    let sourceName: String
    let live: [Channel]
    let movies: [Channel]
    let series: [Channel]
    let onPlay: (Channel, Bool, [Channel]) -> Void
    let onSchedule: (Channel) -> Void
    let onSearch: () -> Void
    let onSettings: () -> Void
    let movieDestination: (Channel) -> AnyView
    let seriesDestination: (Channel) -> AnyView

    enum Screen { case home, guide, movies, shows }
    @State private var screen: Screen = .home

    private var crumb: String {
        switch screen {
        case .home: return ""
        case .guide: return "Guide"
        case .movies: return "Movies"
        case .shows: return "Shows"
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Group {
                switch screen {
                case .home:
                    GHomeView(
                        sourceName: sourceName, live: live,
                        hasMovies: !movies.isEmpty, hasShows: !series.isEmpty,
                        onGo: { screen = $0 },
                        onPlay: onPlay, onSchedule: onSchedule,
                        onSearch: onSearch, onSettings: onSettings
                    )
                case .guide:
                    GGuideView(live: live, onPlay: onPlay, onSchedule: onSchedule)
                case .movies:
                    GLibraryView(title: "Movies", items: movies, isSeries: false, onPlay: onPlay,
                                 destination: movieDestination)
                case .shows:
                    GLibraryView(title: "Shows", items: series, isSeries: true, onPlay: onPlay,
                                 destination: seriesDestination)
                }
            }
            .padding(.horizontal, G.rem * 1.6)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .foregroundStyle(G.text)
        .background { GarysPalette.backdrop }
        .preferredColorScheme(.dark)
        .tint(G.red2)
    }

    private var topBar: some View {
        HStack(spacing: G.rem * 1.2) {
            Button { screen = .home } label: {
                HStack(spacing: G.rem * 0.55) {
                    GLogo(size: G.rem * 2.3)
                    (Text("Garys") + Text("TV").foregroundColor(G.red2))
                        .font(G.font(1.35, .heavy))
                        .foregroundStyle(G.text)
                }
            }
            .buttonStyle(.plain)

            HStack(spacing: G.rem * 0.4) {
                if screen != .home {
                    Button("‹ Back") { screen = .home }.buttonStyle(GPillStyle())
                }
                Button("Home") { screen = .home }.buttonStyle(GPillStyle())
                Button("Guide") { screen = .guide }.buttonStyle(GPillStyle())
                Button("Search", action: onSearch).buttonStyle(GPillStyle())
                Button("☰ Menu", action: onSettings).buttonStyle(GPillStyle())
            }

            if !crumb.isEmpty {
                Text(crumb.uppercased())
                    .font(G.font(1.1, .semibold))
                    .tracking(G.rem * 0.12)
                    .foregroundStyle(G.muted)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            TimelineView(.everyMinute) { context in
                HStack(spacing: G.rem * 0.6) {
                    Text(context.date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()))
                        .font(G.font(1))
                        .foregroundStyle(G.muted)
                    Text(context.date.formatted(date: .omitted, time: .shortened))
                        .font(G.font(1.25, .semibold).monospacedDigit())
                }
            }
        }
        .padding(.horizontal, G.rem * 1.6)
        .padding(.top, G.rem * 0.7)
        .padding(.bottom, G.rem * 0.6)
    }
}

// MARK: - Home

private struct GHomeView: View {
    let sourceName: String
    let live: [Channel]
    let hasMovies: Bool
    let hasShows: Bool
    let onGo: (GarysRootView.Screen) -> Void
    let onPlay: (Channel, Bool, [Channel]) -> Void
    let onSchedule: (Channel) -> Void
    let onSearch: () -> Void
    let onSettings: () -> Void

    @ObservedObject private var favorites = FavoritesStore.shared
    @ObservedObject private var epgNow = EpgNowStore.shared

    private struct Tile: Identifiable {
        let id: String
        let symbol: String
        let label: String
        let sub: String
        let action: () -> Void
    }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening"
    }

    private var tiles: [Tile] {
        var t: [Tile] = [
            Tile(id: "guide", symbol: "tablecells", label: "Guide", sub: "See what's on") { onGo(.guide) },
            Tile(id: "live", symbol: "play.tv", label: "Live TV", sub: "Last channel") { playLast() },
        ]
        if hasMovies { t.append(Tile(id: "movies", symbol: "film", label: "Movies", sub: "On demand") { onGo(.movies) }) }
        if hasShows { t.append(Tile(id: "shows", symbol: "tv", label: "Shows", sub: "Every episode") { onGo(.shows) }) }
        t.append(Tile(id: "search", symbol: "magnifyingglass", label: "Search", sub: "Find a show", action: onSearch))
        t.append(Tile(id: "settings", symbol: "gearshape", label: "Settings", sub: "Sources, guide, playback", action: onSettings))
        return t
    }

    private var favoriteChannels: [Channel] {
        guard favorites.hasAnyFavorites else { return [] }
        return live.filter { favorites.isFavorite(sourceId: $0.sourceId, channelId: $0.id) }
    }

    private func playLast() {
        let playable = live.filter { $0.isPlayable }
        guard !playable.isEmpty else { return }
        let lastId = StartupStore.shared.lastLive?.channelId
        let channel = playable.first(where: { $0.id == lastId }) ?? playable[0]
        onPlay(channel, true, playable)
    }

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: G.rem * 2) {
                    HStack(spacing: G.rem * 1.6) {
                        GLogo(size: min(max(geo.size.height * 0.12, G.rem * 4.5), G.rem * 7), ring: 3)
                        (Text("\(greeting). ").fontWeight(.light) + Text("What would you like to watch?").fontWeight(.bold))
                            .font(G.font(2.2))
                            .lineLimit(2)
                        Spacer(minLength: 0)
                        Text(sourceName).font(G.font(1)).foregroundStyle(G.muted).lineLimit(1)
                    }

                    HStack(spacing: G.rem) {
                        ForEach(tiles) { tile in
                            Button(action: tile.action) {
                                GTileLabel(symbol: tile.symbol, label: tile.label, sub: tile.sub)
                                    .frame(height: min(max(geo.size.height * 0.28, G.rem * 8), G.rem * 15))
                            }
                            .buttonStyle(GPressStyle())
                        }
                    }

                    strip("Favorites") {
                        if favoriteChannels.isEmpty {
                            Button { onGo(.guide) } label: {
                                GCardLabel(title: "☆ No favorites yet", sub: "Press and hold a channel in the Guide to add it")
                            }
                            .buttonStyle(GPressStyle())
                        }
                        ForEach(favoriteChannels) { ch in
                            channelCard(ch, list: favoriteChannels, star: true)
                        }
                    }

                    let onNow = live.filter { epgNow.programme(for: $0) != nil }.prefix(12)
                    strip("On now") {
                        if onNow.isEmpty {
                            Text("Once the guide has loaded, what's on now shows up here.")
                                .font(G.font(1)).foregroundStyle(G.muted)
                                .padding(.vertical, G.rem)
                        }
                        ForEach(Array(onNow)) { ch in
                            channelCard(ch, list: live, star: false)
                        }
                    }
                }
                .padding(.vertical, G.rem)
                .frame(minHeight: geo.size.height)
            }
            .scrollIndicators(.hidden)
        }
    }

    private func channelCard(_ ch: Channel, list: [Channel], star: Bool) -> some View {
        Button {
            onPlay(ch, true, list.filter { $0.isPlayable })
        } label: {
            GCardLabel(title: ch.name, sub: epgNow.programme(for: ch)?.title ?? (ch.group ?? "Live"), star: star)
        }
        .buttonStyle(GPressStyle())
        .contextMenu { GChannelMenu(channel: ch, onPlay: { onPlay(ch, true, list.filter { $0.isPlayable }) }, onSchedule: { onSchedule(ch) }) }
    }

    private func strip<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: G.rem * 0.7) {
            Text(title.uppercased())
                .font(G.font(1.15, .semibold))
                .tracking(G.rem * 0.1)
                .foregroundStyle(G.muted)
            ScrollView(.horizontal) {
                HStack(spacing: G.rem) { content() }
                    .padding(.vertical, 4)
                    .padding(.horizontal, 2)
            }
            .scrollIndicators(.hidden)
        }
    }
}

private struct GTileLabel: View {
    let symbol: String
    let label: String
    let sub: String
    @Environment(\.gPressed) private var pressed

    var body: some View {
        VStack(spacing: G.rem * 0.8) {
            Image(systemName: symbol)
                .font(.system(size: G.rem * 2.6, weight: .light))
            Text(label).font(G.font(1.3, .semibold)).lineLimit(1).minimumScaleFactor(0.7)
            Text(sub).font(G.font(0.85)).foregroundStyle(pressed ? G.focusSub : G.muted).lineLimit(1).minimumScaleFactor(0.7)
        }
        .foregroundStyle(pressed ? G.focusText : G.text)
        .padding(G.rem * 0.5)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: G.rem)
                .fill(pressed ? AnyShapeStyle(G.focusBg) : AnyShapeStyle(LinearGradient(colors: [G.panel2, G.panel], startPoint: .top, endPoint: .bottom)))
        )
        .overlay(RoundedRectangle(cornerRadius: G.rem).stroke(pressed ? G.focusBg : G.line, lineWidth: 2))
        .contentShape(RoundedRectangle(cornerRadius: G.rem))
    }
}

private struct GCardLabel: View {
    let title: String
    let sub: String
    var star = false
    @Environment(\.gPressed) private var pressed

    var body: some View {
        VStack(alignment: .leading, spacing: G.rem * 0.3) {
            HStack(spacing: G.rem * 0.5) {
                if star { Text("★").foregroundStyle(G.yellow) }
                Text(title).lineLimit(1)
            }
            .font(G.font(1.1, .bold))
            .foregroundStyle(pressed ? G.focusText : G.text)
            Text(sub)
                .font(G.font(0.9))
                .foregroundStyle(pressed ? G.focusSub : G.muted)
                .lineLimit(1)
        }
        .padding(.horizontal, G.rem * 1.1)
        .padding(.vertical, G.rem)
        .frame(width: G.rem * 17, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: G.rem * 0.7).fill(pressed ? G.focusBg : G.panel))
        .overlay(RoundedRectangle(cornerRadius: G.rem * 0.7).stroke(pressed ? G.focusBg : G.line, lineWidth: 2))
        .contentShape(RoundedRectangle(cornerRadius: G.rem * 0.7))
    }
}

/// Press-and-hold menu for a channel, wherever it appears.
private struct GChannelMenu: View {
    let channel: Channel
    let onPlay: () -> Void
    let onSchedule: () -> Void
    @ObservedObject private var favorites = FavoritesStore.shared

    var body: some View {
        Button(action: onPlay) { Label("Watch \(channel.name)", systemImage: "play.fill") }
        Button(action: onSchedule) { Label("Schedule & reminders", systemImage: "list.bullet.rectangle") }
        let fav = favorites.isFavorite(sourceId: channel.sourceId, channelId: channel.id)
        Button {
            favorites.toggleFavorite(sourceId: channel.sourceId, channelId: channel.id)
        } label: {
            Label(fav ? "Remove from Favorites" : "Add to Favorites", systemImage: fav ? "star.slash" : "star")
        }
    }
}

// MARK: - Guide

private enum GFilter: Equatable {
    case all, favorites
    case category(String)
}

private struct GGuideView: View {
    let live: [Channel]
    let onPlay: (Channel, Bool, [Channel]) -> Void
    let onSchedule: (Channel) -> Void

    @ObservedObject private var favorites = FavoritesStore.shared
    @ObservedObject private var epgStatus = EpgStatus.shared
    @State private var filter: GFilter = .all
    @State private var ws = G.align(G.now)
    @State private var selectedId: String?
    @State private var selectedProgramme: EpgProgramme?

    private let chanW = G.rem * 15
    private let rowH = G.rem * 4.4

    private var categories: [(name: String, count: Int)] {
        var counts: [String: Int] = [:]
        for ch in live { if let g = ch.group, !g.isEmpty { counts[g, default: 0] += 1 } }
        return counts.sorted { $0.value > $1.value }.map { (name: $0.key, count: $0.value) }
    }

    private var rows: [Channel] {
        switch filter {
        case .all: return live
        case .favorites: return live.filter { favorites.isFavorite(sourceId: $0.sourceId, channelId: $0.id) }
        case .category(let name): return live.filter { $0.group == name }
        }
    }

    private var selected: Channel? {
        let r = rows
        return r.first(where: { $0.id == selectedId }) ?? r.first
    }

    var body: some View {
        let shown = rows
        VStack(spacing: 0) {
            infoPane
            chipBar(count: shown.count)
            header
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { index, ch in
                        GGuideRow(
                            channel: ch,
                            number: ch.number.map { "\($0.intValue)" } ?? "\(index + 1)",
                            ws: ws,
                            chanW: chanW,
                            rowH: rowH,
                            isSelected: ch.id == selected?.id,
                            selectedStart: ch.id == selected?.id ? selectedProgramme?.start : nil,
                            isFavorite: favorites.isFavorite(sourceId: ch.sourceId, channelId: ch.id),
                            onSelect: { p in select(ch, p, list: shown) },
                            onPlay: { onPlay(ch, true, shown.filter { $0.isPlayable }) },
                            onSchedule: { onSchedule(ch) }
                        )
                    }
                }
            }
            .scrollIndicators(.hidden)
            .overlay(alignment: .topLeading) { nowLine }
            .simultaneousGesture(
                DragGesture(minimumDistance: 40).onEnded { v in
                    guard abs(v.translation.width) > abs(v.translation.height) * 1.5 else { return }
                    shift(v.translation.width < 0 ? 1 : -1)
                }
            )
            .overlay {
                if shown.isEmpty {
                    Text(filter == .favorites
                         ? "No favorites yet. Press and hold a channel and choose Add to Favorites."
                         : "No channels here.")
                        .font(G.font(1.2)).foregroundStyle(G.muted)
                        .multilineTextAlignment(.center)
                        .padding(G.rem * 3)
                }
            }
        }
        .onChange(of: filter) { _, _ in selectedId = nil; selectedProgramme = nil }
    }

    /// First tap on a programme selects it (details show above); a second
    /// tap plays it if it is on now, or opens the channel's schedule.
    private func select(_ ch: Channel, _ p: EpgProgramme?, list: [Channel]) {
        let same = ch.id == selected?.id && p?.start == selectedProgramme?.start
        if same {
            if let p, !p.isAiring(at: G.now) { onSchedule(ch) } else { onPlay(ch, true, list.filter { $0.isPlayable }) }
        } else {
            selectedId = ch.id
            selectedProgramme = p
        }
    }

    private func shift(_ slots: Int) {
        let floor = G.align(G.now)
        ws = max(floor, ws + slots * G.slot)
        selectedProgramme = nil
    }

    // ----- details above the guide -----
    private var infoPane: some View {
        VStack(alignment: .leading, spacing: G.rem * 0.35) {
            if let ch = selected {
                let p = selectedProgramme ?? EpgNowStore.shared.programme(for: ch)
                Text(p?.title ?? ch.name)
                    .font(G.font(2.1, .bold))
                    .lineLimit(1)
                HStack(spacing: G.rem * 0.8) {
                    (Text(ch.number.map { "\($0.intValue)" } ?? "").fontWeight(.bold).foregroundColor(G.text)
                        + Text("  \(ch.name)"))
                        .lineLimit(1)
                    if let p {
                        Text("\(G.day(p.start)) \(G.time(p.start)) – \(G.time(p.end))")
                        Text("\((p.end - p.start) / 60) min")
                    }
                    if let g = ch.group, !g.isEmpty {
                        Text(g)
                            .font(G.font(0.8, .bold))
                            .foregroundStyle(G.text)
                            .lineLimit(1)
                            .padding(.horizontal, G.rem * 0.4)
                            .overlay(RoundedRectangle(cornerRadius: G.rem * 0.25).stroke(G.line))
                    }
                    if favorites.isFavorite(sourceId: ch.sourceId, channelId: ch.id) {
                        Text("★ Favorite").font(G.font(0.9, .bold)).foregroundStyle(G.yellow)
                    }
                }
                .font(G.font(1))
                .foregroundStyle(G.muted)
                if let p, p.isAiring(at: G.now) {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.white.opacity(0.11))
                            Capsule().fill(G.red2).frame(width: g.size.width * p.progress(at: G.now))
                        }
                    }
                    .frame(width: G.rem * 24, height: G.rem * 0.3)
                }
                Text(p?.description ?? (p == nil ? (epgStatus.text.isEmpty ? "No guide information for this channel." : epgStatus.text) : ""))
                    .font(G.font(1.05))
                    .foregroundStyle(G.desc)
                    .lineLimit(3)
                HStack(spacing: G.rem * 0.6) {
                    Button("▶ Watch") { onPlay(ch, true, rows.filter { $0.isPlayable }) }
                        .buttonStyle(GPillStyle(on: true))
                    Button("Schedule & reminders") { onSchedule(ch) }
                        .buttonStyle(GPillStyle())
                }
                .padding(.top, G.rem * 0.3)
            } else {
                Text("No channels").font(G.font(2.1, .bold))
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .frame(height: G.rem * 13, alignment: .topLeading)
        .padding(.top, G.rem * 0.4)
    }

    // ----- bar above the guide -----
    private func chipBar(count: Int) -> some View {
        HStack(spacing: G.rem * 0.5) {
            Button("All channels") { filter = .all }.buttonStyle(GPillStyle(on: filter == .all))
            Button("★ Favorites") { filter = .favorites }.buttonStyle(GPillStyle(on: filter == .favorites))
            Menu {
                Button("All channels") { filter = .all }
                ForEach(categories, id: \.name) { c in
                    Button("\(c.name)  (\(c.count))") { filter = .category(c.name) }
                }
            } label: {
                if case .category(let name) = filter {
                    GPillLabel(text: "▦ \(name)", on: true)
                } else {
                    GPillLabel(text: "▦ Categories")
                }
            }
            Spacer(minLength: 0)
            Text("\(count) channels").font(G.font(0.95)).foregroundStyle(G.muted)
            Button("‹ Earlier") { shift(-1) }.buttonStyle(GPillStyle()).disabled(ws <= G.align(G.now))
            Button("Now") { ws = G.align(G.now); selectedProgramme = nil }.buttonStyle(GPillStyle())
            Button("Later ›") { shift(1) }.buttonStyle(GPillStyle())
        }
        .padding(.bottom, G.rem * 0.6)
    }

    private var header: some View {
        HStack(spacing: 0) {
            Text(G.day(ws).uppercased())
                .font(G.font(0.95, .bold))
                .tracking(G.rem * 0.06)
                .foregroundStyle(G.yellow)
                .padding(.leading, G.rem * 0.8)
                .frame(width: chanW, alignment: .leading)
            GeometryReader { g in
                ForEach(0..<3, id: \.self) { k in
                    Text(G.time(ws + k * G.slot))
                        .font(G.font(1, .semibold))
                        .foregroundStyle(G.muted)
                        .padding(.leading, G.rem * 0.6)
                        .frame(width: g.size.width / 3, height: g.size.height, alignment: .leading)
                        .overlay(alignment: .leading) { Rectangle().fill(G.line).frame(width: 1) }
                        .offset(x: g.size.width * CGFloat(k) / 3)
                }
            }
        }
        .frame(height: G.rem * 2.6)
        .overlay(alignment: .top) { Rectangle().fill(G.line).frame(height: 1) }
        .overlay(alignment: .topLeading) { nowLine }
    }

    private var nowLine: some View {
        TimelineView(.everyMinute) { _ in
            GeometryReader { g in
                let now = G.now
                if now >= ws && now < ws + G.span {
                    let w = g.size.width - chanW
                    Rectangle()
                        .fill(G.red2)
                        .frame(width: 2, height: g.size.height)
                        .shadow(color: G.red2, radius: 4)
                        .offset(x: chanW + w * CGFloat(now - ws) / CGFloat(G.span))
                }
            }
            .allowsHitTesting(false)
        }
    }
}

private struct GGuideRow: View {
    let channel: Channel
    let number: String
    let ws: Int
    let chanW: CGFloat
    let rowH: CGFloat
    let isSelected: Bool
    let selectedStart: Int?
    let isFavorite: Bool
    let onSelect: (EpgProgramme?) -> Void
    let onPlay: () -> Void
    let onSchedule: () -> Void

    @State private var programmes: [EpgProgramme] = []
    @ObservedObject private var epgNow = EpgNowStore.shared

    var body: some View {
        HStack(spacing: 0) {
            channelCell
            GeometryReader { g in
                let w = g.size.width
                ZStack(alignment: .topLeading) {
                    if programmes.isEmpty {
                        cell(title: channel.name, sub: "", x: 0, width: w, alt: false, filler: true,
                             focused: isSelected && selectedStart == nil, cutLeft: false, cutRight: false) { onSelect(nil) }
                    }
                    ForEach(Array(programmes.enumerated()), id: \.offset) { j, p in
                        let s = max(p.start, ws)
                        let e = min(p.end, ws + G.span)
                        if e > s {
                            cell(
                                title: p.title, sub: "\(G.time(p.start)) – \(G.time(p.end))",
                                x: w * CGFloat(s - ws) / CGFloat(G.span),
                                width: w * CGFloat(e - s) / CGFloat(G.span),
                                alt: j % 2 == 1, filler: false,
                                focused: isSelected && selectedStart == p.start,
                                cutLeft: p.start < ws, cutRight: p.end > ws + G.span
                            ) { onSelect(p) }
                        }
                    }
                }
            }
        }
        .frame(height: rowH)
        .overlay(alignment: .bottom) { Rectangle().fill(G.bg).frame(height: 2) }
        .contextMenu { GChannelMenu(channel: channel, onPlay: onPlay, onSchedule: onSchedule) }
        .task(id: "\(ws)|\(epgNow.revision)") {
            programmes = await EpgManager.shared.grid(for: channel, from: ws, to: ws + G.span)
        }
    }

    private var channelCell: some View {
        Button(action: onPlay) {
            HStack(spacing: G.rem * 0.6) {
                Text(number)
                    .font(G.font(1.35, .bold).monospacedDigit())
                    .lineLimit(1)
                    .frame(minWidth: G.rem * 3.2, alignment: .leading)
                if let logo = channel.logoUrl, let url = URL(string: logo) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFit()
                    } placeholder: {
                        Color.clear
                    }
                    .frame(width: G.rem * 3.2, height: G.rem * 2.2)
                }
                Text(channel.name)
                    .font(G.font(0.95))
                    .foregroundStyle(G.muted)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if isFavorite { Text("★").foregroundStyle(G.yellow) }
            }
            .foregroundStyle(G.text)
            .padding(.horizontal, G.rem * 0.8)
            .frame(width: chanW, height: rowH)
            .background(isSelected ? G.panel2 : G.panel)
            .overlay(alignment: .leading) { if isSelected { Rectangle().fill(G.red2).frame(width: 4) } }
            .overlay(alignment: .trailing) { Rectangle().fill(G.bg).frame(width: 2) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func cell(title: String, sub: String, x: CGFloat, width: CGFloat, alt: Bool, filler: Bool,
                      focused: Bool, cutLeft: Bool, cutRight: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: G.rem * 0.1) {
                Text((cutLeft ? "‹ " : "") + title + (cutRight ? " ›" : ""))
                    .font(G.font(1.1, filler ? .regular : .semibold))
                    .foregroundStyle(focused ? G.focusText : (filler ? G.dim : G.text))
                    .lineLimit(1)
                if !sub.isEmpty && width > G.rem * 9 {
                    Text(sub)
                        .font(G.font(0.85))
                        .foregroundStyle(focused ? G.focusSub : G.muted)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, G.rem * 0.7)
            .frame(width: max(width, 1), height: rowH, alignment: .leading)
            .background(focused ? G.focusBg : (alt ? G.cellAlt : G.cell))
            .overlay(alignment: .bottom) { if focused { Rectangle().fill(G.red2).frame(height: 4) } }
            .overlay(alignment: .leading) { Rectangle().fill(G.bg).frame(width: 2) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .offset(x: x)
    }
}

// MARK: - Movies / Shows

private struct GLibraryView: View {
    let title: String
    let items: [Channel]
    let isSeries: Bool
    let onPlay: (Channel, Bool, [Channel]) -> Void
    let destination: (Channel) -> AnyView

    @State private var group: String?
    @State private var selectedId: String?

    private var groups: [(name: String, count: Int)] {
        var counts: [String: Int] = [:]
        for c in items { if let g = c.group, !g.isEmpty { counts[g, default: 0] += 1 } }
        return counts.sorted { $0.value > $1.value }.map { (name: $0.key, count: $0.value) }
    }

    private var shown: [Channel] {
        guard let group else { return items }
        return items.filter { $0.group == group }
    }

    var body: some View {
        let list = shown
        let selected = list.first(where: { $0.id == selectedId }) ?? list.first
        HStack(alignment: .top, spacing: G.rem * 1.6) {
            VStack(alignment: .leading, spacing: G.rem * 0.8) {
                HStack {
                    Text(title.uppercased())
                        .font(G.font(1.15, .semibold))
                        .tracking(G.rem * 0.1)
                        .foregroundStyle(G.muted)
                    Spacer()
                    Text("\(list.count)").font(G.font(0.95)).foregroundStyle(G.muted)
                }
                ScrollView(.horizontal) {
                    HStack(spacing: G.rem * 0.5) {
                        Button("All") { group = nil }.buttonStyle(GPillStyle(on: group == nil))
                        ForEach(groups, id: \.name) { g in
                            Button(g.name) { group = g.name; selectedId = nil }.buttonStyle(GPillStyle(on: group == g.name))
                        }
                    }
                    .padding(2)
                }
                .scrollIndicators(.hidden)
                ScrollView {
                    LazyVStack(spacing: G.rem * 0.35) {
                        ForEach(list) { c in
                            let on = c.id == selected?.id
                            Button { selectedId = c.id } label: {
                                VStack(alignment: .leading, spacing: G.rem * 0.15) {
                                    Text(c.name)
                                        .font(G.font(1.15, .semibold))
                                        .foregroundStyle(on ? G.focusText : G.text)
                                        .lineLimit(1)
                                    if let g = c.group {
                                        Text(g).font(G.font(0.92)).foregroundStyle(on ? G.focusSub : G.muted).lineLimit(1)
                                    }
                                }
                                .padding(.horizontal, G.rem)
                                .padding(.vertical, G.rem * 0.75)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(RoundedRectangle(cornerRadius: G.rem * 0.5).fill(on ? G.focusBg : G.panel))
                                .overlay(alignment: .leading) { if on { Rectangle().fill(G.red2).frame(width: 5) } }
                                .clipShape(RoundedRectangle(cornerRadius: G.rem * 0.5))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            .frame(maxWidth: .infinity)
            .layoutPriority(1.25)

            detail(selected)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .padding(.bottom, G.rem * 0.4)
    }

    private func detail(_ c: Channel?) -> some View {
        VStack(alignment: .leading, spacing: G.rem * 0.7) {
            if let c {
                if let logo = c.logoUrl, let url = URL(string: logo) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFit()
                    } placeholder: {
                        Color.clear
                    }
                    .frame(maxWidth: .infinity, maxHeight: G.rem * 20)
                    .clipShape(RoundedRectangle(cornerRadius: G.rem * 0.6))
                }
                Text(c.name).font(G.font(1.8, .bold)).lineLimit(3)
                if let g = c.group { Text(g).font(G.font(1.15)).foregroundStyle(G.sub).lineLimit(1) }
                HStack(spacing: G.rem * 0.7) {
                    if isSeries {
                        NavigationLink { destination(c) } label: { Text("Seasons & episodes") }
                            .buttonStyle(GPillStyle(on: true))
                    } else {
                        Button("▶ Play") { onPlay(c, false, []) }
                            .buttonStyle(GPillStyle(on: true))
                            .disabled(!c.isPlayable)
                        NavigationLink { destination(c) } label: { Text("More info") }
                            .buttonStyle(GPillStyle())
                    }
                }
                .padding(.top, G.rem * 0.4)
                Spacer(minLength: 0)
            } else {
                Text("Nothing here yet.").font(G.font(1.1)).foregroundStyle(G.muted)
            }
        }
        .padding(.horizontal, G.rem * 1.6)
        .padding(.vertical, G.rem * 1.4)
        .background(RoundedRectangle(cornerRadius: G.rem * 0.8).fill(G.panel))
        .overlay(RoundedRectangle(cornerRadius: G.rem * 0.8).stroke(G.line))
    }
}
