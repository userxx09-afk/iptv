import SwiftUI
import UIKit

/// Settings > Watch history > Sync with other devices. The counterpart of
/// Fire TV's Settings > Shared watch history: sign both in to the same Google
/// or Microsoft account (or point both at the same WebDAV folder) and what one
/// device watches, the other can resume.
struct SyncSettingsView: View {
    @ObservedObject private var sync = SyncManager.shared
    @Environment(\.openURL) private var openURL

    /// A Google / Microsoft sign-in in progress.
    private struct SignIn {
        let provider: CloudProvider
        var code: CloudAuth.DeviceCode?
        var error: String?
    }

    @State private var signIn: SignIn?
    @State private var signInTask: Task<Void, Never>?
    @State private var cloudProvider = SyncConfig.cloudProvider
    @State private var cloudAccount = SyncConfig.cloudAccount

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
            cloudSection

            if let signIn {
                signInSection(signIn)
            }

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
                Text(cloudProvider == nil ? "WebDAV folder" : "Or use a WebDAV folder instead")
            } footer: {
                Text("Use the same folder address you give the Fire TV app (Settings > Shared watch history > WebDAV / NAS). The folder must already exist on the server. Works with Nextcloud, ownCloud, Synology and most NAS boxes.")
            }

            Section {
                Button {
                    Task { await saveAndSync() }
                } label: {
                    HStack {
                        Label(configured && cloudProvider == nil ? "Save and sync now" : "Use this WebDAV folder",
                              systemImage: "arrow.triangle.2.circlepath")
                        if sync.syncing {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(!canSave)

                if configured {
                    Button("Turn off sync", role: .destructive) { turnOff() }
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
                Text("How this iPad is named to your other devices. It syncs when the app opens, when you come back to it, and when a film or episode closes. Marking something unwatched, or removing it from Continue, reaches your other devices too. The Fire TV app syncs when it starts, when you return to it, and when something stops playing.")
            }
        }
        .navigationTitle("Sync")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear {
            SyncDevice.label = deviceName
            cancelSignIn()
        }
    }

    private func saveAndSync() async {
        SyncDevice.label = deviceName
        SyncConfig.save(address: address, username: username, password: password)
        configured = true
        cloudProvider = nil
        await sync.syncNow(reason: "settings")
    }

    private func turnOff() {
        signInTask?.cancel()
        signIn = nil
        SyncConfig.clear()
        address = ""
        username = ""
        password = ""
        configured = false
        cloudProvider = nil
        cloudAccount = ""
    }

    // MARK: - Google / Microsoft

    @ViewBuilder
    private var cloudSection: some View {
        Section {
            if let p = cloudProvider, configured {
                HStack {
                    Label(p.label, systemImage: "checkmark.icloud")
                    Spacer()
                    Text(cloudAccount).foregroundStyle(.secondary)
                }
                Button {
                    Task { await sync.syncNow(reason: "settings") }
                } label: {
                    HStack {
                        Label("Sync now", systemImage: "arrow.triangle.2.circlepath")
                        if sync.syncing { Spacer(); ProgressView() }
                    }
                }
                .disabled(sync.syncing)
                Button("Sign out", role: .destructive) { turnOff() }
            } else {
                // Microsoft only once this build has its client ID (OneDrive set-up was shelved).
                ForEach(CloudProvider.allCases.filter { $0 == .google || $0.isConfigured }) { p in
                    Button {
                        startSignIn(p)
                    } label: {
                        Label("Sign in with \(p.company)", systemImage: p == .google ? "g.circle" : "m.circle")
                    }
                    .disabled(signIn != nil)
                }
            }
        } header: {
            Text("Cloud account")
        } footer: {
            if let p = cloudProvider, configured {
                Text("Watch history is kept in \(p.folderDescription) in your \(p.label). Sign the Fire TV app in to the same account (Settings > Shared watch history) to share it.")
            } else {
                Text("The easiest way to share watch history. Sign in and a sync folder is created in your Google Drive automatically - nothing to set up. The app can only see the files it creates there. Sign your Fire TV in to the same account.")
            }
        }
    }

    @ViewBuilder
    private func signInSection(_ s: SignIn) -> some View {
        Section {
            if let error = s.error {
                Text(error).foregroundStyle(.orange)
                Button("Try again") { startSignIn(s.provider) }
                Button("Close") { signIn = nil }
            } else if let code = s.code {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Enter this code on the \(s.provider.company) sign-in page:")
                        .foregroundStyle(.secondary)
                    Text(code.userCode)
                        .font(.system(size: 40, weight: .bold, design: .monospaced))
                        .textSelection(.enabled)
                    Text(code.verificationURL.absoluteString)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
                Button {
                    UIPasteboard.general.string = code.userCode
                    openURL(code.verificationURL)
                } label: {
                    Label("Copy code and open sign-in page", systemImage: "safari")
                }
                HStack {
                    ProgressView()
                    Text("Waiting for you to finish signing in...").foregroundStyle(.secondary)
                }
                Button("Cancel", role: .cancel) { cancelSignIn() }
            } else {
                HStack {
                    ProgressView()
                    Text("Contacting \(s.provider.company)...").foregroundStyle(.secondary)
                }
                Button("Cancel", role: .cancel) { cancelSignIn() }
            }
        } header: {
            Text("Sign in with \(s.provider.company)")
        } footer: {
            if s.error == nil, s.code != nil {
                Text("You can also enter the code on your phone or computer. Come back here when you're done - sync starts by itself.")
            }
        }
    }

    private func cancelSignIn() {
        signInTask?.cancel()
        signInTask = nil
        signIn = nil
    }

    private func startSignIn(_ p: CloudProvider) {
        signInTask?.cancel()
        signIn = SignIn(provider: p)
        signInTask = Task { @MainActor in
            do {
                let dc = try await CloudAuth.start(p)
                try Task.checkCancellation()
                signIn = SignIn(provider: p, code: dc)
                let tokens = try await CloudAuth.waitForSignIn(dc)
                guard let refresh = tokens.refreshToken else {
                    throw SyncError.other("\(p.company) didn't allow offline access. Try signing in again.")
                }
                let account = await CloudAuth.accountName(p, accessToken: tokens.accessToken)
                try Task.checkCancellation()
                try await SyncConfig.connectCloud(p, account: account, refreshToken: refresh)
                AppLog.shared.log("SYNC", "signed in to \(p.label) as \(account)")
                SyncDevice.label = deviceName
                signIn = nil
                cloudProvider = p
                cloudAccount = account
                configured = true
                await sync.syncNow(reason: "signed in")
            } catch is CancellationError {
                // Cancelled from the UI; nothing to show.
            } catch {
                guard !Task.isCancelled else { return }
                AppLog.shared.log("SYNC", "\(p.label) sign-in failed: \(error.localizedDescription)")
                signIn = SignIn(provider: p, error: error.localizedDescription)
            }
        }
    }
}
