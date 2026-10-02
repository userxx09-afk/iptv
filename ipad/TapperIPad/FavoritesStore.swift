import Combine
import Foundation
import SwiftUI
import TapperCore

/// Favorite channels/shows, and pinned categories - the iPad counterpart to
/// Fire TV's FavoritesStore.kt (same key shapes and reasoning; nothing is
/// shared on disk between the two apps).
///
/// Favorites are keyed "sourceId|channelId" and deliberately span sources -
/// a favorites list organized by provider would be useless, since the whole
/// point is one place for the handful of channels/shows actually watched.
/// Plain UserDefaults, same as SourceStore - nothing stored here is
/// sensitive.
///
/// An ObservableObject (unlike SourceStore/CredentialVault) so every row's
/// star and every category picker's pin update immediately everywhere, not
/// just in the view that made the change - there's no single owner of this
/// state the way ContentView owns its own @State.
final class FavoritesStore: ObservableObject {
    static let shared = FavoritesStore()

    @Published private var favoriteIds: Set<String>
    @Published private var pinnedCategoryIds: Set<String>

    private let defaults = UserDefaults.standard
    private let favoritesKey = "tapper.favorites"
    private let pinnedCategoriesKey = "tapper.pinnedCategories"

    private init() {
        favoriteIds = Set(defaults.stringArray(forKey: favoritesKey) ?? [])
        pinnedCategoryIds = Set(defaults.stringArray(forKey: pinnedCategoriesKey) ?? [])
    }

    private func key(sourceId: String, channelId: String) -> String { "\(sourceId)|\(channelId)" }

    func isFavorite(sourceId: String, channelId: String) -> Bool {
        favoriteIds.contains(key(sourceId: sourceId, channelId: channelId))
    }

    func toggleFavorite(sourceId: String, channelId: String) {
        let k = key(sourceId: sourceId, channelId: channelId)
        if favoriteIds.contains(k) {
            favoriteIds.remove(k)
        } else {
            favoriteIds.insert(k)
        }
        defaults.set(Array(favoriteIds), forKey: favoritesKey)
    }

    func hasAnyFavorite(in channels: [Channel]) -> Bool {
        channels.contains { isFavorite(sourceId: $0.sourceId, channelId: $0.id) }
    }

    /// Pins are kept separate per list ("live"/"movie"/"series"/"playlist" -
    /// whatever CategoryFilteredList's caller passes as categoryNamespace).
    /// Movies and Shows don't share a category namespace, and a provider
    /// could plausibly reuse the same category name for genuinely different
    /// things in each.
    private func categoryKey(namespace: String, category: String) -> String { "\(namespace)|\(category)" }

    func isPinnedCategory(namespace: String, category: String) -> Bool {
        pinnedCategoryIds.contains(categoryKey(namespace: namespace, category: category))
    }

    func togglePinnedCategory(namespace: String, category: String) {
        let k = categoryKey(namespace: namespace, category: category)
        if pinnedCategoryIds.contains(k) {
            pinnedCategoryIds.remove(k)
        } else {
            pinnedCategoryIds.insert(k)
        }
        defaults.set(Array(pinnedCategoryIds), forKey: pinnedCategoriesKey)
    }
}

/// Star button for a channel/show row - used by ContentView's channelList
/// and seriesList, and by EpisodeListView if episode-level favoriting is
/// ever wanted (not wired up there yet; favoriting stops at the
/// channel/show level today, matching Fire TV's FavoritesStore scope).
///
/// Self-contained: owns its own observation of FavoritesStore so each row in
/// a List updates independently when toggled, without the parent view
/// needing to hold the ObservableObject itself.
struct FavoriteButton: View {
    let sourceId: String
    let channelId: String

    @ObservedObject private var favorites = FavoritesStore.shared

    var body: some View {
        Button {
            favorites.toggleFavorite(sourceId: sourceId, channelId: channelId)
        } label: {
            let isFav = favorites.isFavorite(sourceId: sourceId, channelId: channelId)
            Image(systemName: isFav ? "star.fill" : "star")
                .foregroundStyle(isFav ? Color.yellow : Color.secondary)
        }
        .buttonStyle(.plain)
    }
}
