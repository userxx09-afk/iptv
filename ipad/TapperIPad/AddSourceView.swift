import SwiftUI

/// Add an Xtream account or an M3U playlist. Mirrors Fire TV's
/// AddSourceScreen.kt field-for-field: the mode toggle, the same field set
/// per mode, and the footer note about where credentials live (Keychain
/// here, the Android Keystore there - see CredentialVault).
struct AddSourceView: View {
    let busy: Bool
    let error: String?
    let onSubmitXtream: (_ name: String, _ host: String, _ user: String, _ pass: String) -> Void
    let onSubmitM3u: (_ name: String, _ url: String, _ epg: String?) -> Void
    let onCancel: () -> Void

    @State private var xtream = true
    @State private var name = ""
    @State private var host = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var url = ""
    @State private var epg = ""

    private var canSubmit: Bool {
        xtream
            ? !host.isEmpty && !user.isEmpty && !pass.isEmpty
            : !url.isEmpty
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Picker("Source type", selection: $xtream) {
                        Text("Xtream login").tag(true)
                        Text("M3U playlist URL").tag(false)
                    }
                    .pickerStyle(.segmented)

                    labeledField("Name", text: $name, placeholder: "Living room provider")

                    if xtream {
                        labeledField("Server address", text: $host, placeholder: "http://provider.tv:8080", keyboard: .URL)
                        labeledField("Username", text: $user, placeholder: "")
                        labeledField("Password", text: $pass, placeholder: "", secure: true)
                    } else {
                        labeledField("Playlist URL", text: $url, placeholder: "https://\u{2026}/playlist.m3u", keyboard: .URL)
                        // The default playlist declares two guides and the
                        // first one 404s, so an override is a normal
                        // requirement here, not an edge case.
                        labeledField("EPG URL (optional)", text: $epg, placeholder: "https://\u{2026}/guide.xml.gz", keyboard: .URL)
                    }

                    if let error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }

                    HStack(spacing: 16) {
                        Button {
                            if xtream {
                                onSubmitXtream(name, host, user, pass)
                            } else {
                                onSubmitM3u(name, url, epg.isEmpty ? nil : epg)
                            }
                        } label: {
                            if busy {
                                ProgressView()
                            } else {
                                Text("Save and Connect")
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(busy || !canSubmit)

                        Button("Cancel", role: .cancel, action: onCancel)
                            .disabled(busy)
                    }

                    Text("Credentials are stored in the device keychain, not in the app database.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(32)
                .frame(maxWidth: 560, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle("Add an IPTV service")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    @ViewBuilder
    private func labeledField(
        _ label: String,
        text: Binding<String>,
        placeholder: String,
        secure: Bool = false,
        keyboard: UIKeyboardType = .default
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Group {
                if secure {
                    SecureField(placeholder, text: text)
                } else {
                    TextField(placeholder, text: text)
                        .keyboardType(keyboard)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
            }
            .textFieldStyle(.roundedBorder)
        }
    }
}

/// List of saved sources, with the active one checked. Tapping a row
/// switches to it and loads it; swipe-to-delete removes a non-built-in one.
/// The "+" leads to AddSourceView. Deliberately a flat sheet rather than
/// folded into a larger Settings screen - that's later, broader work (see
/// Fire TV's SettingsScreen, which this is only the source-list slice of).
struct SourceListView: View {
    let sources: [TvSource]
    let activeId: String
    let onSelect: (TvSource) -> Void
    let onRemove: (TvSource) -> Void
    let onAddTapped: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(sources) { source in
                    Button {
                        onSelect(source)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(source.name)
                                Text(source.kind == .xtream ? "Xtream" : "M3U playlist")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if source.id == activeId {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.blue)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                    .swipeActions {
                        if !source.builtIn {
                            Button("Remove", role: .destructive) { onRemove(source) }
                        }
                    }
                }
            }
            .navigationTitle("Sources")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(action: onAddTapped) {
                        Label("Add", systemImage: "plus")
                    }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done", action: onDismiss)
                }
            }
        }
    }
}
