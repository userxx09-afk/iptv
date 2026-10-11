import Foundation
import TapperCore
import UserNotifications

/// A programme the viewer asked to be reminded about - the iPad counterpart of
/// Fire TV's Reminder.
struct Reminder: Codable, Identifiable, Equatable {
    /// "appSourceId|channelId|startUtc"
    var id: String
    var appSourceId: String
    var channelId: String
    var channelName: String
    var programmeTitle: String
    var startUtc: Int

    var startDate: Date { Date(timeIntervalSince1970: TimeInterval(startUtc)) }

    static func idFor(source: String, channel: String, start: Int) -> String {
        "\(source)|\(channel)|\(start)"
    }
}

/// Where a tapped reminder wants to go.
struct ReminderTarget: Equatable {
    let sourceId: String
    let channelId: String
}

/// Reminders, kept in UserDefaults and scheduled as local notifications - iOS
/// delivers those itself, even with the app closed, so (unlike the Fire TV
/// side) there is nothing to re-arm after a restart.
@MainActor
final class ReminderStore: ObservableObject {
    static let shared = ReminderStore()

    /// Bumped on any change so screens showing reminders refresh.
    @Published private(set) var revision = 0

    /// Minutes before the programme starts; 0 is "at the start".
    @Published var leadMinutes: Int {
        didSet {
            guard oldValue != leadMinutes else { return }
            UserDefaults.standard.set(leadMinutes, forKey: leadKey)
            rescheduleAll()
        }
    }

    private let listKey = "tapper.reminders"
    private let leadKey = "tapper.reminder.lead"
    private var reminders: [Reminder] = []

    private init() {
        leadMinutes = UserDefaults.standard.integer(forKey: "tapper.reminder.lead")
        if let data = UserDefaults.standard.data(forKey: listKey),
           let decoded = try? JSONDecoder().decode([Reminder].self, from: data) {
            reminders = decoded
        }
        pruneOld()
    }

    // MARK: - Reading

    /// Reminders for programmes that haven't started yet, soonest first.
    func upcoming() -> [Reminder] {
        let now = Int(Date().timeIntervalSince1970)
        return reminders.filter { $0.startUtc > now }.sorted { $0.startUtc < $1.startUtc }
    }

    func has(channel: Channel, programme: EpgProgramme) -> Bool {
        let id = Reminder.idFor(source: SourceStore.shared.activeId, channel: channel.id, start: programme.start)
        return reminders.contains { $0.id == id }
    }

    // MARK: - Changing

    /// Adds the reminder, or removes it if it exists. Returns false when
    /// notifications are switched off for the app, so the caller can say so.
    func toggle(channel: Channel, programme: EpgProgramme) async -> Bool {
        let source = SourceStore.shared.activeId
        let id = Reminder.idFor(source: source, channel: channel.id, start: programme.start)
        if reminders.contains(where: { $0.id == id }) {
            remove(id: id)
            return true
        }
        guard await ensureAllowed() else { return false }
        let reminder = Reminder(
            id: id, appSourceId: source, channelId: channel.id, channelName: channel.name,
            programmeTitle: programme.title, startUtc: programme.start
        )
        reminders.removeAll { $0.id == id }
        reminders.append(reminder)
        schedule(reminder)
        persist()
        return true
    }

    func remove(id: String) {
        reminders.removeAll { $0.id == id }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
        persist()
    }

    func removeAll() {
        let ids = reminders.map { $0.id }
        reminders = []
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
        persist()
    }

    // MARK: - Notifications

    /// Whether the app may post notifications, asking the first time.
    func ensureAllowed() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined:
            return (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        default:
            return false
        }
    }

    func notificationsAllowed() async -> Bool {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional || status == .ephemeral
    }

    private func schedule(_ reminder: Reminder) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [reminder.id])

        let now = Date()
        // Programme already under way: nothing to remind about.
        guard reminder.startDate > now else { return }
        // With a lead time that has already passed, remind straight away.
        let fire = max(reminder.startDate.addingTimeInterval(-TimeInterval(leadMinutes * 60)),
                       now.addingTimeInterval(2))

        let content = UNMutableNotificationContent()
        content.title = reminder.programmeTitle
        let minutes = Int((reminder.startDate.timeIntervalSince(fire) / 60).rounded())
        content.body = minutes <= 0
            ? "Starting now on \(reminder.channelName)"
            : "Starts in \(minutes) min on \(reminder.channelName)"
        content.sound = .default
        content.userInfo = ["source": reminder.appSourceId, "channel": reminder.channelId]

        let trigger = UNTimeIntervalNotificationTrigger(
            timeInterval: max(fire.timeIntervalSince(now), 1), repeats: false
        )
        center.add(UNNotificationRequest(identifier: reminder.id, content: content, trigger: trigger)) { error in
            if let error {
                AppLog.shared.log("REMINDER", "Couldn't schedule \"\(reminder.programmeTitle)\": \(error.localizedDescription)")
            }
        }
    }

    private func rescheduleAll() {
        for reminder in upcoming() { schedule(reminder) }
        revision += 1
    }

    private func pruneOld() {
        let now = Int(Date().timeIntervalSince1970)
        let kept = reminders.filter { $0.startUtc > now }
        if kept.count != reminders.count {
            reminders = kept
            persist()
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(reminders) {
            UserDefaults.standard.set(data, forKey: listKey)
        }
        revision += 1
    }
}

/// Receives notification events: shows reminders as banners even while the app
/// is open, and passes a tap on one to the screen that plays the channel.
final class ReminderCenter: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = ReminderCenter()

    /// Set when a reminder is tapped; cleared once the channel has been opened.
    @Published var pending: ReminderTarget?

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        if let source = info["source"] as? String, let channel = info["channel"] as? String {
            DispatchQueue.main.async {
                self.pending = ReminderTarget(sourceId: source, channelId: channel)
            }
        }
        completionHandler()
    }
}
