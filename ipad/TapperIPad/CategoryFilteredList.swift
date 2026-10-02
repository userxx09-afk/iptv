import SwiftUI
import TapperCore

// Swift doesn't allow a stored `static let` inside a generic type
// (CategoryFilteredList<RowContent> below is generic over its row view), so
// this lives at file scope instead of as a member.
private let categoryPriorityTokens: Set<Substring> = ["US", "USA", "ENGLISH", "EN"]

// Whole-token match only - splitting on non-letters so "Music" or "Russia"
// (which merely contain "us") can't false-positive against the short
// "US"/"EN" tokens the way a substring check would. Shared by the list
// ordering below and by CategoryPickerSheet's own "Suggested" section, so
// the two agree on what counts as priority.
private func isPriorityCategory(_ category: String) -> Bool {
    let upper = category.uppercased()
    if upper.contains("UNITED STATES") { return true }
    return upper.split(whereSeparator: { !$0.isLetter })
        .contains { categoryPriorityTokens.contains($0) }
}

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
struct CategoryFilteredList<RowContent: View>: View {
    let channels: [Channel]
    @ViewBuilder let row: (Channel) -> RowContent

    @State private var selectedCategory: String?
    @State private var showingCategoryPicker = false

    private func categoryNames(for channel: Channel) -> [String] {
        if !channel.categories.isEmpty {
            return channel.categories.map { $0 }
        }
        return [channel.group ?? "Uncategorized"]
    }

    // Country/language catalogues on these sources run into the hundreds
    // (iptv-org alone has one category per country), so a plain alphabetical
    // sort buries US/English content under "Albania", "Argentina", etc.
    // Rather than hide everything else, this just promotes the categories
    // most people here actually want to the front of the list - "All" stays
    // first, then these, then the rest alphabetically same as before.
    private var categories: [String] {
        var seen = Set<String>()
        var ordered: [String] = []
        for channel in channels {
            for name in categoryNames(for: channel) where !seen.contains(name) {
                seen.insert(name)
                ordered.append(name)
            }
        }
        let priority = ordered.filter(isPriorityCategory).sorted()
        let rest = ordered.filter { !isPriorityCategory($0) }.sorted()
        return priority + rest
    }

    private var filtered: [Channel] {
        guard let selectedCategory else { return channels }
        return channels.filter { categoryNames(for: $0).contains(selectedCategory) }
    }

    var body: some View {
        VStack(spacing: 0) {
            if categories.count > 1 {
                categoryBar
                Divider()
            }
            List(filtered, id: \.id) { channel in
                row(channel)
            }
            // Picking a category can empty the on-screen list if that
            // category's only channel was just removed by a source switch -
            // resetting here keeps a stale filter from hiding everything.
            .onChange(of: channels.map(\.id)) { _, _ in
                if let selectedCategory, !categories.contains(selectedCategory) {
                    self.selectedCategory = nil
                }
            }
        }
        .sheet(isPresented: $showingCategoryPicker) {
            CategoryPickerSheet(categories: categories, selected: selectedCategory) { choice in
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
/// several hundred. Sectioned the same priority-then-alphabetical way as the
/// list's own ordering, so US/English categories surface first with nothing
/// typed; typing narrows both sections to a case-insensitive substring match.
private struct CategoryPickerSheet: View {
    let categories: [String]
    let selected: String?
    let onSelect: (String?) -> Void

    @State private var search = ""
    @Environment(\.dismiss) private var dismiss

    private var priority: [String] { categories.filter(isPriorityCategory) }
    private var rest: [String] { categories.filter { !isPriorityCategory($0) } }

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

                let shownPriority = priority.filter(matches)
                if !shownPriority.isEmpty {
                    Section("Suggested") {
                        ForEach(shownPriority, id: \.self) { category in
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

    @ViewBuilder
    private func categoryRow(_ category: String) -> some View {
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
        }
        .foregroundStyle(.primary)
    }
}
