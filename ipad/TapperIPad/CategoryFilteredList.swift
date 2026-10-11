import SwiftUI
import TapperCore

/// Distinct enough not to collide with any real category name a provider
/// might use, and shared by CategoryFilteredList (filtering) and
/// CategoryPickerSheet (the selectable row) below.
private let favoritesPseudoCategory = "\u{2605} Favorites"

/// Groups a channel list by country and category and lets the user filter to
/// either - the iPad counterpart of Fire TV's BrowseScreen pickers. The ⋯ menu
/// saves the current country + category as this list's starting point (Fire
/// TV's "Home"), which the list then opens on instead of showing everything;
/// Settings lists and clears the saved starting points.
///
/// The country picker only appears when the list actually spans more than one
/// country (an Xtream panel whose categories read "US | Sports", "UK | News";
/// an iptv-org playlist). Categories are then limited to the chosen country.
///
/// Falls back to `group` when `categories` is empty - Xtream sources
/// populate categories directly, but a plain M3U channel only ever has
/// group-title, so this keeps both source types groupable the same way.
///
/// Category *selection* is a button that opens a searchable sheet
/// (CategoryPickerSheet below), not a horizontally-scrolling chip row. A
/// source like iptv-org has one category per country - several hundred -
/// and a chip strip that long means swiping past a hundred others to find
/// "Spain." A search field turns that into typing a few letters.
///
/// Category ordering used to auto-promote anything that looked US/English
/// ("US", "USA", "English", country name containing "United States") ahead
/// of everything else, picked for the app rather than by anyone using it.
/// That's gone - ordering is now driven entirely by categories the user has
/// actually pinned (FavoritesStore), and individual channels/shows can be
/// starred the same way, surfaced here as a selectable "Favorites" entry.
struct CategoryFilteredList<RowContent: View>: View {
    let channels: [Channel]
    /// Storage namespace for this list's pinned categories - "live",
    /// "movie", "series", "playlist" from ContentView's call sites. Kept
    /// separate per list: Movies and Shows don't share a category
    /// namespace, and a provider could plausibly reuse the same category
    /// name for genuinely different things in each.
    let categoryNamespace: String
    @ViewBuilder let row: (Channel) -> RowContent

    @State private var selectedCategory: String?
    @State private var selectedCountry: String?
    @State private var showingCategoryPicker = false
    @State private var showingCountryPicker = false
    @State private var index = CategoryIndex.empty
    /// The list the index and the starting point were last applied to - so
    /// leaving a tab and coming back keeps whatever the user had picked
    /// instead of rebuilding and snapping back to the starting point.
    @State private var builtFor: Fingerprint?
    @ObservedObject private var favorites = FavoritesStore.shared
    @ObservedObject private var startup = StartupStore.shared

    private func isPinned(_ category: String) -> Bool {
        favorites.isPinnedCategory(namespace: categoryNamespace, category: category)
    }

    /// Changes whenever the underlying list is replaced (a different source,
    /// a reload) - cheap to compare, unlike mapping every id on every redraw.
    private struct Fingerprint: Equatable {
        let count: Int
        let first: String?
        let last: String?
    }

    private var fingerprint: Fingerprint {
        Fingerprint(count: channels.count, first: channels.first?.id, last: channels.last?.id)
    }

    // Pinned categories first (whatever the user has starred in the picker
    // sheet - nothing promoted automatically), then everything else
    // alphabetically, same as before. Only evaluated while the picker sheet
    // is actually open.
    private var orderedCategories: [String] {
        let names = visibleCategoryNames
        let pinned = names.filter(isPinned)
        let rest = names.filter { !isPinned($0) }
        return pinned + rest
    }

    /// Every category, or - with a country chosen - just the ones that
    /// country has.
    private var visibleCategoryNames: [String] {
        guard let selectedCountry, let present = index.countryCategories[selectedCountry] else {
            return index.names
        }
        return index.names.filter { present.contains($0) }
    }

    // "Anything starred anywhere", not "anything starred in this list": the
    // exact check meant walking the whole catalogue (every row crossing the
    // Kotlin/Swift boundary) on every redraw, which is what made big
    // sources sluggish and memory-hungry.
    private var hasFavorites: Bool { favorites.hasAnyFavorites }

    private var filtered: [Channel] {
        let total = channels.count
        // The index is rebuilt off the main thread after a reload, so for a
        // moment it can describe the previous list; its country table is
        // only trusted when it lines up with the current one.
        let country: String? = index.countryOfItem.count == total ? selectedCountry : nil
        let countries = index.countryOfItem

        if selectedCategory == favoritesPseudoCategory {
            var out: [Channel] = []
            for i in 0..<total {
                if let country, countries[i] != country { continue }
                let channel = channels[i]
                if favorites.isFavorite(sourceId: channel.sourceId, channelId: channel.id) {
                    out.append(channel)
                }
            }
            return out
        }
        if let selectedCategory, let positions = index.members[selectedCategory] {
            var out: [Channel] = []
            out.reserveCapacity(positions.count)
            for pos in positions where pos < total {
                if let country, countries[pos] != country { continue }
                out.append(channels[pos])
            }
            return out
        }
        if let country {
            var out: [Channel] = []
            for i in 0..<total where countries[i] == country {
                out.append(channels[i])
            }
            return out
        }
        return channels
    }

    var body: some View {
        VStack(spacing: 0) {
            // selectedCategory != nil keeps the bar (and its clear button)
            // visible even if a filter becomes degenerate while active -
            // e.g. unstarring the last favorite while viewing Favorites,
            // which drops hasFavorites to false but shouldn't strand the
            // user on an empty list with no way back to "All Categories".
            if index.names.count > 1 || index.countries.count > 1 || hasFavorites
                || selectedCategory != nil || selectedCountry != nil {
                categoryBar
                Divider()
            }
            List(filtered, id: \.id) { channel in
                row(channel)
            }
            // Picking a category can empty the on-screen list if that
            // category's only channel was just removed by a source switch -
            // resetting here keeps a stale filter from hiding everything.
            // Favorites isn't a real category name, so it's checked
            // separately rather than against the `categories` list.
            .onChange(of: index.names) { _, names in
                guard let selectedCategory else { return }
                if selectedCategory == favoritesPseudoCategory {
                    if !hasFavorites { self.selectedCategory = nil }
                } else if !names.contains(selectedCategory) {
                    self.selectedCategory = nil
                }
            }
            // Unstarring the last favorite while viewing Favorites - caught
            // separately from the onChange above, since that one only fires
            // when the channel list itself changes, not when a favorite is
            // toggled.
            .onChange(of: hasFavorites) { _, nowHasFavorites in
                if !nowHasFavorites, selectedCategory == favoritesPseudoCategory {
                    selectedCategory = nil
                }
            }
        }
        // Grouping a big catalogue by category touches every item once;
        // done on a background thread and only when the list changes, never
        // as part of drawing.
        .task(id: fingerprint) {
            let current = fingerprint
            // Already built (and the starting point already applied) for this
            // very list - e.g. coming back to this tab.
            if builtFor == current { return }
            let snapshot = channels
            let started = Date()
            let built = await Task.detached(priority: .userInitiated) {
                CategoryIndex.build(snapshot)
            }.value
            index = built
            builtFor = current
            applyStartingPoint(built)
            let secs = String(format: "%.2f", Date().timeIntervalSince(started))
            AppLog.shared.log("UI", "\(categoryNamespace): grouped \(snapshot.count) items into \(built.names.count) categories, \(built.countries.count) countries in \(secs)s - \(AppLog.memoryDescription())")
        }
        .sheet(isPresented: $showingCountryPicker) {
            CountryPickerSheet(
                countries: index.countries,
                selected: selectedCountry
            ) { choice in
                selectCountry(choice)
                showingCountryPicker = false
            }
        }
        .sheet(isPresented: $showingCategoryPicker) {
            CategoryPickerSheet(
                categoryNamespace: categoryNamespace,
                categories: orderedCategories,
                showFavoritesOption: hasFavorites,
                selected: selectedCategory
            ) { choice in
                selectedCategory = choice
                showingCategoryPicker = false
            }
        }
    }

    /// Picking a country keeps the chosen category only if that country has
    /// it (Favorites always carries over).
    private func selectCountry(_ code: String?) {
        selectedCountry = code
        guard let code, let category = selectedCategory, category != favoritesPseudoCategory else { return }
        if index.countryCategories[code]?.contains(category) != true {
            selectedCategory = nil
        }
    }

    /// A fresh list always starts clean, then opens on the saved starting
    /// point if there is one and it still exists in this list - a starting
    /// point naming a country or category this provider doesn't have just
    /// shows the whole list. With no starting point saved, a list opens on
    /// the United States when it has it.
    private func applyStartingPoint(_ built: CategoryIndex) {
        selectedCountry = nil
        selectedCategory = nil
        guard let home = startup.home(for: categoryNamespace) else {
            selectedCountry = built.countryCategories.keys.first { $0.lowercased() == "us" }
            return
        }
        if let code = home.countryCode {
            guard built.countryCategories[code] != nil else { return }
            selectedCountry = code
        }
        if let category = home.category {
            if category == favoritesPseudoCategory {
                if favorites.hasAnyFavorites { selectedCategory = category }
            } else if built.members[category] != nil {
                if let code = selectedCountry, built.countryCategories[code]?.contains(category) != true {
                    return
                }
                selectedCategory = category
            }
        }
    }

    private var currentHome: NavHome {
        NavHome(countryCode: selectedCountry, category: selectedCategory)
    }

    private var startingPointMenu: some View {
        Menu {
            Button {
                startup.setHome(currentHome, for: categoryNamespace)
            } label: {
                Label("Make this my starting point", systemImage: "house")
            }
            if let home = startup.home(for: categoryNamespace) {
                Button {
                } label: {
                    Text("Now: \(homeSummary(home))")
                }
                .disabled(true)
                Button(role: .destructive) {
                    startup.clearHome(for: categoryNamespace)
                } label: {
                    Label("Clear starting point", systemImage: "xmark.circle")
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.title3)
        }
    }

    private var chipBackground: Color { Color.secondary.opacity(0.15) }

    private var categoryBar: some View {
        HStack(spacing: 12) {
            if index.countries.count > 1 || selectedCountry != nil {
                Button {
                    showingCountryPicker = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "globe")
                            .font(.caption)
                        Text(selectedCountry.map(countryLabel) ?? "All Countries")
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.caption)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(chipBackground)
                    .foregroundStyle(Color.primary)
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }

            Button {
                showingCategoryPicker = true
            } label: {
                HStack(spacing: 6) {
                    if selectedCategory == favoritesPseudoCategory {
                        Image(systemName: "star.fill")
                            .font(.caption)
                            .foregroundStyle(.yellow)
                    }
                    Text(selectedCategory ?? "All Categories")
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.secondary.opacity(0.15))
                .foregroundStyle(Color.primary)
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)

            if selectedCategory != nil || selectedCountry != nil {
                Button {
                    selectedCategory = nil
                    selectedCountry = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }

            Spacer()

            Text("\(visibleCategoryNames.count) categories")
                .font(.caption)
                .foregroundStyle(.secondary)

            startingPointMenu
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }
}

/// Searchable picker for one category out of - on a source like iptv-org -
/// several hundred. Pinned categories (starred by the user, from this same
/// sheet) surface first under "Pinned" with nothing typed; typing narrows
/// both sections to a case-insensitive substring match.
private struct CategoryPickerSheet: View {
    let categoryNamespace: String
    let categories: [String]
    let showFavoritesOption: Bool
    let selected: String?
    let onSelect: (String?) -> Void

    @State private var search = ""
    @ObservedObject private var favorites = FavoritesStore.shared
    @Environment(\.dismiss) private var dismiss

    private func isPinned(_ category: String) -> Bool {
        favorites.isPinnedCategory(namespace: categoryNamespace, category: category)
    }

    private var pinned: [String] { categories.filter(isPinned) }
    private var rest: [String] { categories.filter { !isPinned($0) } }

    private func matches(_ category: String) -> Bool {
        search.isEmpty || category.localizedCaseInsensitiveContains(search)
    }

    var body: some View {
        NavigationStack {
            List {
                Button {
                    onSelect(nil)
                } label: {
                    HStack {
                        Text("All Categories")
                        Spacer()
                        if selected == nil {
                            Image(systemName: "checkmark").foregroundStyle(.blue)
                        }
                    }
                }
                .foregroundStyle(.primary)

                if showFavoritesOption {
                    Button {
                        onSelect(favoritesPseudoCategory)
                    } label: {
                        HStack {
                            Label("Favorites", systemImage: "star.fill")
                                .foregroundStyle(.yellow)
                            Spacer()
                            if selected == favoritesPseudoCategory {
                                Image(systemName: "checkmark").foregroundStyle(.blue)
                            }
                        }
                    }
                }

                let shownPinned = pinned.filter(matches)
                if !shownPinned.isEmpty {
                    Section("Pinned") {
                        ForEach(shownPinned, id: \.self) { category in
                            categoryRow(category)
                        }
                    }
                }

                let shownRest = rest.filter(matches)
                if !shownRest.isEmpty {
                    Section("All") {
                        ForEach(shownRest, id: \.self) { category in
                            categoryRow(category)
                        }
                    }
                }
            }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search categories")
            .navigationTitle("Categories")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    // Two independent tap targets in one row - the name selects the
    // category, the pin toggles it - each its own plain-style Button so
    // neither swallows the other's taps inside the List row.
    @ViewBuilder
    private func categoryRow(_ category: String) -> some View {
        HStack {
            Button {
                onSelect(category)
            } label: {
                HStack {
                    Text(category)
                    Spacer()
                    if selected == category {
                        Image(systemName: "checkmark").foregroundStyle(.blue)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.primary)

            Button {
                favorites.togglePinnedCategory(namespace: categoryNamespace, category: category)
            } label: {
                Image(systemName: isPinned(category) ? "pin.fill" : "pin")
                    .foregroundStyle(isPinned(category) ? Color.blue : Color.secondary)
            }
            .buttonStyle(.plain)
        }
    }
}

/// Searchable picker for the country filter.
private struct CountryPickerSheet: View {
    let countries: [CountryCount]
    let selected: String?
    let onSelect: (String?) -> Void

    @State private var search = ""
    @Environment(\.dismiss) private var dismiss

    private var shown: [CountryCount] {
        search.isEmpty
            ? countries
            : countries.filter {
                countryLabel($0.code).localizedCaseInsensitiveContains(search)
                    || $0.code.localizedCaseInsensitiveContains(search)
            }
    }

    var body: some View {
        NavigationStack {
            List {
                Button {
                    onSelect(nil)
                } label: {
                    HStack {
                        Text("All Countries")
                        Spacer()
                        if selected == nil {
                            Image(systemName: "checkmark").foregroundStyle(.blue)
                        }
                    }
                }
                .foregroundStyle(.primary)

                ForEach(shown) { country in
                    Button {
                        onSelect(country.code)
                    } label: {
                        HStack {
                            Text(countryLabel(country.code))
                            Spacer()
                            Text("\(country.count)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if selected == country.code {
                                Image(systemName: "checkmark").foregroundStyle(.blue)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                }
            }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search countries")
            .navigationTitle("Countries")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// Items grouped by category, as positions into the source array rather than
/// copies of it. Built once per list (off the main thread) so that scrolling,
/// toggling a star or opening the picker never re-walks the whole catalogue.
///
/// Falls back to `group` when `categories` is empty - Xtream sources
/// populate categories directly, but a plain M3U channel only ever has
/// group-title.
private struct CountryCount: Equatable, Identifiable {
    let code: String
    let count: Int
    var id: String { code }
}

private struct CategoryIndex: Equatable {
    var names: [String] = []
    var members: [String: [Int]] = [:]
    /// Country code of every item, by position (nil when it has none).
    var countryOfItem: [String?] = []
    /// Countries present, with item counts, ordered by readable name.
    var countries: [CountryCount] = []
    /// Which categories each country has.
    var countryCategories: [String: Set<String>] = [:]

    static let empty = CategoryIndex()

    static func build(_ channels: [Channel]) -> CategoryIndex {
        var members: [String: [Int]] = [:]
        var countryOfItem: [String?] = []
        countryOfItem.reserveCapacity(channels.count)
        var countryTotals: [String: Int] = [:]
        var countryCategories: [String: Set<String>] = [:]
        for (i, channel) in channels.enumerated() {
            let rawCode: String? = channel.countryCode
            let code: String? = (rawCode?.isEmpty == false) ? rawCode : nil
            countryOfItem.append(code)
            if let code { countryTotals[code, default: 0] += 1 }
            let cats = channel.categories
            if cats.isEmpty {
                let name = channel.group ?? "Uncategorized"
                members[name, default: []].append(i)
                if let code { countryCategories[code, default: []].insert(name) }
            } else {
                var seen = Set<String>()
                for c in cats where seen.insert(c).inserted {
                    members[c, default: []].append(i)
                    if let code { countryCategories[code, default: []].insert(c) }
                }
            }
        }
        let countries = countryTotals
            .map { CountryCount(code: $0.key, count: $0.value) }
            .sorted { countryLabel($0.code).localizedCaseInsensitiveCompare(countryLabel($1.code)) == .orderedAscending }
        return CategoryIndex(
            names: members.keys.sorted(),
            members: members,
            countryOfItem: countryOfItem,
            countries: countries,
            countryCategories: countryCategories
        )
    }
}
