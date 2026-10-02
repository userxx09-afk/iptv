import SwiftUI
import TapperCore

/// Distinct enough not to collide with any real category name a provider
/// might use, and shared by CategoryFilteredList (filtering) and
/// CategoryPickerSheet (the selectable row) below.
private let favoritesPseudoCategory = "\u{2605} Favorites"

/// Groups a channel list by category and lets the user filter to one - the
/// iPad-sized first slice of Fire TV's BrowseScreen category picker (which
/// also adds a country dimension and remembers the last-viewed category per
/// tab; this starts with just the category axis, applied uniformly to Live,
/// Movies and Shows per request - country/nav-home parity is later work).
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
    @State private var showingCategoryPicker = false
    @ObservedObject private var favorites = FavoritesStore.shared

    private func categoryNames(for channel: Channel) -> [String] {
        if !channel.categories.isEmpty {
            return channel.categories.map { $0 }
        }
        return [channel.group ?? "Uncategorized"]
    }

    private func isPinned(_ category: String) -> Bool {
        favorites.isPinnedCategory(namespace: categoryNamespace, category: category)
    }

    // Pinned categories first (whatever the user has starred in the picker
    // sheet - nothing promoted automatically), then everything else
    // alphabetically, same as before.
    private var categories: [String] {
        var seen = Set<String>()
        var ordered: [String] = []
        for channel in channels {
            for name in categoryNames(for: channel) where !seen.contains(name) {
                seen.insert(name)
                ordered.append(name)
            }
        }
        let pinned = ordered.filter(isPinned).sorted()
        let rest = ordered.filter { !isPinned($0) }.sorted()
        return pinned + rest
    }

    private var hasFavorites: Bool { favorites.hasAnyFavorite(in: channels) }

    private var filtered: [Channel] {
        if selectedCategory == favoritesPseudoCategory {
            return channels.filter { favorites.isFavorite(sourceId: $0.sourceId, channelId: $0.id) }
        }
        guard let selectedCategory else { return channels }
        return channels.filter { categoryNames(for: $0).contains(selectedCategory) }
    }

    var body: some View {
        VStack(spacing: 0) {
            // selectedCategory != nil keeps the bar (and its clear button)
            // visible even if a filter becomes degenerate while active -
            // e.g. unstarring the last favorite while viewing Favorites,
            // which drops hasFavorites to false but shouldn't strand the
            // user on an empty list with no way back to "All Categories".
            if categories.count > 1 || hasFavorites || selectedCategory != nil {
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
            .onChange(of: channels.map(\.id)) { _, _ in
                guard let selectedCategory else { return }
                if selectedCategory == favoritesPseudoCategory {
                    if !hasFavorites { self.selectedCategory = nil }
                } else if !categories.contains(selectedCategory) {
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
        .sheet(isPresented: $showingCategoryPicker) {
            CategoryPickerSheet(
                categoryNamespace: categoryNamespace,
                categories: categories,
                showFavoritesOption: hasFavorites,
                selected: selectedCategory
            ) { choice in
                selectedCategory = choice
                showingCategoryPicker = false
            }
        }
    }

    private var categoryBar: some View {
        HStack(spacing: 12) {
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

            if selectedCategory != nil {
                Button {
                    selectedCategory = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }

            Spacer()

            Text("\(categories.count) categories")
                .font(.caption)
                .foregroundStyle(.secondary)
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
