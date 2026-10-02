import SwiftUI
import TapperCore

// Swift doesn't allow a stored `static let` inside a generic type
// (CategoryFilteredList<RowContent> below is generic over its row view), so
// this lives at file scope instead of as a member.
private let categoryPriorityTokens: Set<Substring> = ["US", "USA", "ENGLISH", "EN"]

/// Groups a channel list by category and lets the user filter to one - the
/// iPad-sized first slice of Fire TV's BrowseScreen category picker (which
/// also adds a country dimension and remembers the last-viewed category per
/// tab; this starts with just the category axis, applied uniformly to Live,
/// Movies and Shows per request - country/nav-home parity is later work).
///
/// Falls back to `group` when `categories` is empty - Xtream sources
/// populate categories directly, but a plain M3U channel only ever has
/// group-title, so this keeps both source types groupable the same way.
struct CategoryFilteredList<RowContent: View>: View {
    let channels: [Channel]
    @ViewBuilder let row: (Channel) -> RowContent

    @State private var selectedCategory: String?

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
    // most people here actually want to the front of the row - "All" stays
    // first, then these, then the rest alphabetically same as before.
    private func isPriority(_ category: String) -> Bool {
        let upper = category.uppercased()
        if upper.contains("UNITED STATES") { return true }
        // Whole-token match only - splitting on non-letters so "Music" or
        // "Russia" (which merely contain "us") can't false-positive against
        // the short "US"/"EN" tokens the way a substring check would.
        return upper.split(whereSeparator: { !$0.isLetter })
            .contains { categoryPriorityTokens.contains($0) }
    }

    private var categories: [String] {
        var seen = Set<String>()
        var ordered: [String] = []
        for channel in channels {
            for name in categoryNames(for: channel) where !seen.contains(name) {
                seen.insert(name)
                ordered.append(name)
            }
        }
        let priority = ordered.filter(isPriority).sorted()
        let rest = ordered.filter { !isPriority($0) }.sorted()
        return priority + rest
    }

    private var filtered: [Channel] {
        guard let selectedCategory else { return channels }
        return channels.filter { categoryNames(for: $0).contains(selectedCategory) }
    }

    var body: some View {
        VStack(spacing: 0) {
            if categories.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        categoryChip(title: "All", isSelected: selectedCategory == nil) {
                            selectedCategory = nil
                        }
                        ForEach(categories, id: \.self) { category in
                            categoryChip(title: category, isSelected: selectedCategory == category) {
                                selectedCategory = category
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                }
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
    }

    private func categoryChip(title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(isSelected ? Color.accentColor : Color.secondary.opacity(0.15))
                .foregroundStyle(isSelected ? Color.white : Color.primary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}
