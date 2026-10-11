import SwiftUI
import UIKit
import TapperCore

private func clock(_ date: Date) -> String {
    date.formatted(date: .omitted, time: .shortened)
}

/// A live channel's row: its name, and underneath what is on right now with
/// how far through it is - the same line Fire TV shows on every channel.
/// Falls back to the channel's group when the guide has nothing for it.
struct LiveRowLabel: View {
    let channel: Channel
    @ObservedObject private var now = EpgNowStore.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(channel.name)
                .font(.body)
                .foregroundStyle(channel.isPlayable ? Color.primary : Color.secondary)
            if let programme = now.programme(for: channel) {
                HStack(spacing: 6) {
                    Text(programme.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Text("\(clock(programme.startDate)) - \(clock(programme.endDate))")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
                ProgressBar(fraction: programme.progress(at: Int(Date().timeIntervalSince1970)), width: 140)
            } else if let group = channel.group {
                Text(group)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// A thin "how far through" bar.
struct ProgressBar: View {
    let fraction: Double
    var width: CGFloat = 140

    var body: some View {
        Capsule()
            .fill(Color.secondary.opacity(0.2))
            .frame(width: width, height: 3)
            .overlay(alignment: .leading) {
                Capsule()
                    .fill(Color.blue)
                    .frame(width: max(width * CGFloat(fraction), 0), height: 3)
            }
    }
}

/// One live channel's schedule: what is on and what follows, with Play. Opened
/// from the guide button on a channel row, and from a programme found in
/// search.
struct ChannelGuideView: View {
    let channel: Channel
    let onPlay: (Channel) -> Void
    let onClose: () -> Void

    @State private var rows: [EpgProgramme] = []
    @State private var loaded = false
    @ObservedObject private var reminders = ReminderStore.shared
    @State private var notifyDenied = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        if let logo = channel.logoUrl, let url = URL(string: logo) {
                            AsyncImage(url: url) { image in
                                image.resizable().scaledToFit()
                            } placeholder: {
                                Color.clear
                            }
                            .frame(width: 48, height: 48)
                        }
                        Text(channel.name)
                            .font(.headline)
                        Spacer()
                        Button {
                            onPlay(channel)
                        } label: {
                            Label("Play", systemImage: "play.fill")
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(!channel.isPlayable)
                    }
                }

                Section("Schedule") {
                    if !loaded {
                        ForEach(0..<3, id: \.self) { _ in
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Programme title goes here")
                                    .font(.body)
                                Text("Long description of the programme goes here")
                                    .font(.caption)
                            }
                            .redacted(reason: .placeholder)
                        }
                    } else if rows.isEmpty {
                        Text("No guide data for this channel.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(Array(rows.enumerated()), id: \.offset) { _, programme in
                            row(programme)
                        }
                    }
                }
            }
            .navigationTitle("Guide")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onClose)
                }
            }
            .alert("Notifications are off", isPresented: $notifyDenied) {
                Button("Open iOS Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                Button("Not now", role: .cancel) {}
            } message: {
                Text("Turn on notifications for Tapper in iOS Settings to get reminders.")
            }
        }
        .task {
            let found = await EpgManager.shared.schedule(for: channel)
            withAnimation(.easeOut(duration: 0.2)) {
                rows = found
                loaded = true
            }
        }
    }

    private func row(_ programme: EpgProgramme) -> some View {
        let nowSeconds = Int(Date().timeIntervalSince1970)
        let airing = programme.isAiring(at: nowSeconds)
        return HStack(alignment: .top, spacing: 14) {
            Text(clock(programme.startDate))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(airing ? Color.blue : Color.secondary)
                .frame(width: 76, alignment: .leading)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(programme.title)
                        .font(.body.weight(airing ? .semibold : .regular))
                    if airing {
                        Text("NOW")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.blue.opacity(0.15))
                            .foregroundStyle(Color.blue)
                            .clipShape(Capsule())
                    }
                }
                if airing {
                    ProgressBar(fraction: programme.progress(at: nowSeconds), width: 160)
                }
                if let description = programme.description {
                    Text(description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
            }
            Spacer(minLength: 0)
            if programme.start > nowSeconds {
                let set = reminders.has(channel: channel, programme: programme)
                Button {
                    Task {
                        if await reminders.toggle(channel: channel, programme: programme) == false {
                            notifyDenied = true
                        }
                    }
                } label: {
                    Image(systemName: set ? "bell.fill" : "bell")
                        .foregroundStyle(set ? Color.blue : Color.secondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(set ? "Remove reminder" : "Remind me")
            }
        }
    }
}

/// Settings > Program guide: refresh status, how well the guide lines up with
/// the channels, and the supplemental guides (Marquee Sports Network by
/// default).
struct GuideSettingsView: View {
    @ObservedObject private var status = EpgStatus.shared
    @State private var supplemental = SupplementalEpgStore.shared.all()
    @State private var coverage = ""
    @State private var adding = false
    @State private var newName = ""
    @State private var newUrl = ""
    @State private var newIds = ""

    var body: some View {
        Form {
            Section {
                Text(status.text.isEmpty ? "No guide loaded yet." : status.text)
                    .foregroundStyle(.secondary)
                if status.isRefreshing {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Working in the background - you can keep browsing.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Button("Refresh guide now") {
                    EpgManager.shared.refresh()
                }
                .disabled(status.isRefreshing || !EpgManager.shared.hasSource)
                if !coverage.isEmpty {
                    Text(coverage)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Provider guide")
            } footer: {
                Text("Each source's own guide is downloaded automatically when it loads and every six hours after. Only channels this source carries are kept.")
            }

            Section {
                ForEach(supplemental) { entry in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.name)
                        Text("\(AppLog.hostOnly(entry.guideUrl))  ·  \(entry.channelIds.count) channel id\(entry.channelIds.count == 1 ? "" : "s")")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .swipeActions {
                        Button("Remove", role: .destructive) {
                            SupplementalEpgStore.shared.remove(id: entry.id)
                            supplemental = SupplementalEpgStore.shared.all()
                        }
                    }
                }
                if adding {
                    TextField("Name (e.g. a channel's name)", text: $newName)
                    TextField("Guide URL (https://.../guide.xml.gz)", text: $newUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Channel id(s) in that guide, comma-separated", text: $newIds)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Button("Save") { saveNew() }
                            .buttonStyle(.borderless)
                            .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty
                                      || newUrl.trimmingCharacters(in: .whitespaces).isEmpty
                                      || newIds.trimmingCharacters(in: .whitespaces).isEmpty)
                        Spacer()
                        Button("Cancel", role: .cancel) { adding = false }
                            .buttonStyle(.borderless)
                    }
                } else {
                    Button {
                        adding = true
                    } label: {
                        Label("Add supplemental guide", systemImage: "plus")
                    }
                }
            } header: {
                Text("Supplemental guides")
            } footer: {
                Text("Layered on top of every source's own guide, for a channel whose provider never carries a schedule for it - Marquee Sports Network is built in. A channel is matched when its name contains the guide's name, or its guide id matches one listed here.")
            }
        }
        .navigationTitle("Program guide")
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadCoverage() }
        .onChange(of: status.isRefreshing) { _, refreshing in
            if !refreshing { Task { await loadCoverage() } }
        }
    }

    private func saveNew() {
        let ids = newIds
            .split(whereSeparator: { $0 == "," || $0 == "\n" })
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let entry = SupplementalEpgSource(
            id: "custom-" + String(UUID().uuidString.prefix(8)),
            name: newName.trimmingCharacters(in: .whitespacesAndNewlines),
            guideUrl: newUrl.trimmingCharacters(in: .whitespacesAndNewlines),
            channelIds: ids
        )
        SupplementalEpgStore.shared.add(entry)
        supplemental = SupplementalEpgStore.shared.all()
        newName = ""
        newUrl = ""
        newIds = ""
        adding = false
        // Pull the new guide in now rather than at the next scheduled refresh.
        EpgManager.shared.refresh()
    }

    private func loadCoverage() async {
        guard EpgManager.shared.hasSource else {
            coverage = "Load a source to see how the guide matches its channels."
            return
        }
        let c = await EpgManager.shared.coverage()
        if c.programmes == 0 {
            coverage = ""
        } else {
            coverage = "\(c.programmes) programmes stored. \(c.matched) of \(c.live) live channels matched to the guide."
        }
    }
}
