import SwiftUI
import UIKit

/// Settings > Reminders: the programmes you asked to be reminded about, how
/// early the reminder comes, and whether iOS lets the app show them.
struct RemindersView: View {
    @ObservedObject private var store = ReminderStore.shared
    @Environment(\.openURL) private var openURL

    @State private var allowed = true
    @State private var confirmClear = false

    var body: some View {
        Form {
            if !allowed {
                Section {
                    Text("Notifications are turned off for Tapper, so reminders can't appear.")
                        .foregroundStyle(.orange)
                    Button("Open iOS Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            openURL(url)
                        }
                    }
                }
            }

            Section {
                Picker("Remind me", selection: $store.leadMinutes) {
                    Text("When it starts").tag(0)
                    Text("5 minutes before").tag(5)
                    Text("10 minutes before").tag(10)
                    Text("15 minutes before").tag(15)
                }
            } footer: {
                Text("Add a reminder from a channel's schedule with the bell. Tapping the notification opens the channel.")
            }

            let upcoming = store.upcoming()
            Section("Upcoming") {
                if upcoming.isEmpty {
                    Text("No reminders set.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(upcoming) { reminder in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(reminder.programmeTitle)
                                .font(.body)
                                .lineLimit(2)
                            Text("\(reminder.channelName) - \(reminder.startDate.formatted(.dateTime.weekday(.abbreviated).hour().minute()))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        .swipeActions {
                            Button("Remove", role: .destructive) {
                                store.remove(id: reminder.id)
                            }
                        }
                    }
                }
            }

            if !upcoming.isEmpty {
                Section {
                    Button("Remove all reminders", role: .destructive) {
                        confirmClear = true
                    }
                }
            }
        }
        .navigationTitle("Reminders")
        .navigationBarTitleDisplayMode(.inline)
        .task { allowed = await store.notificationsAllowed() }
        .confirmationDialog("Remove all reminders?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Remove all", role: .destructive) { store.removeAll() }
        }
    }
}
