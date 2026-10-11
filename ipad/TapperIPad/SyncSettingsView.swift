import SwiftUI

/// Settings > Watch history > Sync with other devices. The counterpart of
/// Fire TV's Settings > Shared watch history > WebDAV / NAS: point both at the
/// same folder and what one device watches, the other can resume.
struct SyncSettingsView: View {
    @ObservedObject private var sync = SyncManager.shared

    @State private var address = SyncConfig.address
    @State private var username = SyncConfig.credentials.username
    @State private var password = SyncConfig.credentials.password
    @State private var deviceName = SyncDevice.label
    @State private var configured = SyncConfig.isConfigured

    private var canSave: Bool {
        !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !sync.syncing
    }

    var body: some View {
        Form {
            Section {
                TextField("https://server/remote.php/dav/files/me/Tapper", text: $address)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Username (if the server asks)", text: $username)
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                    .textContentType(.password)
            } header: {
                Text("WebDAV folder")
            } footer: {
                Text("Use the same folder address you give the Fire TV app (Settings > Shared watch history > WebDAV / NAS). The folder must already exist on the server. Works with Nextcloud, ownCloud, Synology and most NAS boxes.")
            }

            Section {
                Button {
                    Task { await saveAndSync() }
                } label: {
                    HStack {
                        Label(configured ? "Save and sync now" : "Turn on sync", systemImage: "arrow.triangle.2.circlepath")
                        if sync.syncing {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(!canSave)

                if configured {
                    Button("Turn off sync", role: .destructive) {
                        SyncConfig.clear()
                        address = ""
                        username = ""
                        password = ""
                        configured = false
                    }
                }
            } footer: {
                if !sync.message.isEmpty {
                    Text(sync.message)
                        .foregroundStyle(sync.lastFailed ? Color.orange : Color.secondary)
                }
            }

            if configured {
                Section("Status") {
                    HStack {
                        Text("Last synced")
                        Spacer()
                        if let last = sync.lastSynced {
                            Text(last, style: .relative)
                                .foregroundStyle(.secondary)
                        } else {
                            Text("Not yet").foregroundStyle(.secondary)
                        }
                    }
                    if !sync.otherDevices.isEmpty {
                        HStack(alignment: .top) {
                            Text("Other devices")
                            Spacer()
                            Text(sync.otherDevices.joined(separator: ", "))
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.trailing)
                        }
                    }
                }
            }

            Section {
                TextField("Name", text: $deviceName)
                    .onSubmit { SyncDevice.label = deviceName }
            } header: {
                Text("This device")
            } footer: {
                Text("How this iPad is named to your other devices. It syncs when the app opens, when you come back to it, and when a film or episode closes. Marking something unwatched on one device stays on that device. The Fire TV app syncs when it starts.")
            }
        }
        .navigationTitle("Sync")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { SyncDevice.label = deviceName }
    }

    private func saveAndSync() async {
        SyncDevice.label = deviceName
        SyncConfig.save(address: address, username: username, password: password)
        configured = true
        await sync.syncNow(reason: "settings")
    }
}
