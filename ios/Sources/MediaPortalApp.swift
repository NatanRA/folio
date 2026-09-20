import SwiftUI
import UIKit
import UniformTypeIdentifiers

let ebookTypes: [UTType] = [UTType(filenameExtension: "epub")!, .pdf, UTType(filenameExtension: "cbz") ?? .zip]

@main struct MediaPortalApp: App {
    @StateObject var library = Library()
    @StateObject var server = BookServer()
    @StateObject var audio = AudioPlayer()
    @StateObject var sync = KoSync()
    @AppStorage("appearance") var appearance = "System"
    @AppStorage("accent") var accent = "Aquamarine"
    var body: some Scene {
        WindowGroup {
            PortalHome().environmentObject(library).environmentObject(server).environmentObject(audio).environmentObject(sync)
                .tint(portalAccent(accent))
                .onChange(of: appearance, initial: true) { _, _ in applyWindowScheme(appearance) }
                .task { TelegramLibrary.shared.library = library; if TelegramLibrary.shared.configured { TelegramLibrary.shared.start() } }
                .onOpenURL { url in
                    // A pairing link asks to connect a library; it is not a book to import.
                    if let pairing = PastedConnection(pairingLink: url) { server.pendingPairing = pairing; return }
                    Task {
                        library.busy = true; defer { library.busy = false }
                        do { _ = try await library.importFile(url, audio: !["epub", "pdf", "cbz"].contains(url.pathExtension.lowercased())) }
                        catch { library.error = error.localizedDescription }
                    }
                }
        }
    }
}

/// The single place the appearance setting is applied. SwiftUI's `preferredColorScheme` only
/// reaches the root view: a sheet is presented outside that hierarchy, and one applied inside the
/// sheet is only read as the sheet is presented, which left the Appearance sheet on the old theme
/// until it was dismissed and reopened. Leaving both mechanisms in place let them disagree, so the
/// window and everything presented over it are driven from here and nowhere else. That keeps the
/// root, the sheet's content and the sheet's own background in step as soon as the setting changes.
func applyWindowScheme(_ appearance: String) {
    let style: UIUserInterfaceStyle = appearance == "Dark" ? .dark : appearance == "Light" ? .light : .unspecified
    for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
        for window in scene.windows {
            window.overrideUserInterfaceStyle = style
            var controller = window.rootViewController
            while let current = controller {
                current.overrideUserInterfaceStyle = style
                controller = current.presentedViewController
            }
        }
    }
}

func portalAccent(_ name: String) -> Color {
    switch name {
    case "Blue": return Color(red: 0.30, green: 0.55, blue: 1)
    case "Lilac": return Color(red: 0.69, green: 0.53, blue: 0.95)
    case "Peach": return Color(red: 0.95, green: 0.55, blue: 0.37)
    default: return Color(red: 0.20, green: 0.72, blue: 0.65)
    }
}


struct BookCover: View {
    var book: MediaBook
    @EnvironmentObject var library: Library
    var body: some View {
        GeometryReader { geometry in
            if let cover = book.cover, let image = UIImage(contentsOfFile: library.file(cover).path) {
                // Covers keep their own proportions and rest on the bottom edge, so a row of mixed shapes reads as a shelf.
                Image(uiImage: image).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 12))
                    .frame(width: geometry.size.width, height: geometry.size.height, alignment: .bottom)
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 12).fill(Color.accentColor.opacity(0.12))
                    VStack(spacing: 12) { Image(systemName: book.audio != nil && book.ebook == nil ? "headphones" : "book.closed").font(.largeTitle); Text(book.title).font(.headline).multilineTextAlignment(.center).lineLimit(4) }.padding()
                }.clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }.accessibilityHidden(true)
    }
}

struct PortalHome: View {
    @AppStorage("accent") private var brandAccent = "Aquamarine"
    @AppStorage("library.sort") private var sortName = LibrarySort.title.rawValue
    @EnvironmentObject var library: Library
    @EnvironmentObject var server: BookServer
    @EnvironmentObject var audio: AudioPlayer
    @State private var tab = 0
    @State private var search = ""
    @State private var searching = false
    @State private var freeBooks = false
    @FocusState private var searchFocused: Bool
    @State private var importing = false
    @State private var importAudio = false
    @State private var importChoice = false
    @State private var appearance = false
    @State private var resultsHeight: CGFloat = 0
    /// A book opened from search is pushed on the visible tab's own stack, so the tab bar stays.
    @State private var pushedBook: (tab: Int, id: String)?
    var body: some View {
        VStack(spacing: 0) {
        ZStack {
        TabView(selection: $tab) {
            catalogue(home: true, downloads: false).tabItem { Label("Home", systemImage: "house") }.tag(0)
            catalogue(home: false, downloads: false).tabItem { Label("Library", systemImage: "books.vertical") }.tag(1)
            catalogue(home: false, downloads: true).tabItem { Label("Downloads", systemImage: "arrow.down.circle") }.tag(2)
            NavigationStack { ServerScreen(onSearch: openSearch) }.tabItem { Label("Server", systemImage: "externaldrive") }.tag(3)
        }
        .blur(radius: searching ? 22 : 0)
        .animation(.easeOut(duration: 0.28), value: searching)
        if searching {
            // Search floats over the tabs on a blurred layer so nothing behind it has to move.
            NavigationStack {
                VStack(spacing: 12) {
                    searchHeader
                    Picker("Search in", selection: $freeBooks) { Text("My library").tag(false); Text("Free books").tag(true) }.pickerStyle(.segmented).padding(.horizontal)
                    if freeBooks { DiscoveryView(query: search) }
                    else {
                        let matches = library.books.filter { search.trimmingCharacters(in: .whitespaces).isEmpty || ($0.title + " " + $0.author).localizedCaseInsensitiveContains(search.trimmingCharacters(in: .whitespaces)) }
                        // Sized to its rows so the empty space below stays part of the tap-to-dismiss layer.
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                if matches.isEmpty { Text("No matching titles in your library. Try Free books.").foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20).padding(.vertical, 14) }
                                ForEach(matches) { book in
                                    Button { openFromSearch(book.id) } label: {
                                        HStack(spacing: 12) {
                                            Image(systemName: "book")
                                            VStack(alignment: .leading) { Text(book.title); Text(book.author).font(.caption).foregroundStyle(.secondary) }
                                            Spacer(minLength: 8)
                                            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                                        }.padding(.horizontal, 20).padding(.vertical, 12).contentShape(Rectangle())
                                    }.buttonStyle(.plain)
                                    Divider().padding(.leading, 52)
                                }
                            }.background(GeometryReader { proxy in Color.clear.preference(key: SearchResultsHeight.self, value: proxy.size.height) })
                        }.scrollDismissesKeyboard(.interactively).frame(maxHeight: resultsHeight > 0 ? resultsHeight : .infinity).onPreferenceChange(SearchResultsHeight.self) { resultsHeight = $0 }
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top).contentShape(Rectangle()).onTapGesture { closeSearch() }
                .navigationBarTitleDisplayMode(.inline).toolbar(.hidden, for: .navigationBar).clearNavigationBackground()
            }.background(Color(uiColor: .systemBackground).opacity(0.55))
            .transition(.opacity.combined(with: .scale(scale: 0.98)))
        }
        }
        }
        .onChange(of: searchFocused) { _, value in if value { searching = true } }
        .onChange(of: freeBooks) { _, _ in searchFocused = false }
        .safeAreaInset(edge: .bottom) {
            if !audio.playerScreenVisible, let id = audio.bookID, let book = library.book(id) {
                HStack {
                    Image(systemName: "headphones"); Text(book.title).font(.subheadline).lineLimit(1); Spacer()
                    Button { audio.playing ? audio.pause() : audio.play() } label: { Image(systemName: audio.playing ? "pause.fill" : "play.fill").frame(width: 44, height: 44) }.accessibilityLabel(audio.playing ? "Pause audiobook" : "Play audiobook")
                }.padding(.horizontal, 20).background(.regularMaterial)
            }
        }
        .alert("Something needs attention", isPresented: Binding(get: { library.error != nil }, set: { if !$0 { library.error = nil } })) { Button("OK") { library.error = nil } } message: { Text(library.error ?? "") }
        .confirmationDialog("Add to your library", isPresented: $importChoice, titleVisibility: .visible) {
            Button("Load e-book") { importAudio = false; importing = true }
            Button("Load audiobook") { importAudio = true; importing = true }
        } message: { Text("E-book: EPUB, PDF or CBZ. Audiobook: MP3, M4A, M4B, AAC, WAV, FLAC or AIFF.") }
        .fileImporter(isPresented: $importing, allowedContentTypes: importAudio ? [.audio, UTType(filenameExtension: "m4b") ?? .audio] : ebookTypes) { result in
            Task {
                library.busy = true; defer { library.busy = false }
                do { _ = try await library.importFile(result.get(), audio: importAudio) } catch { library.error = error.localizedDescription }
            }
        }
        .sheet(isPresented: $appearance) { AppearanceScreen() }
        .sheet(item: $server.pendingPairing) { PairingConfirmation(pairing: $0) }
    }
    /// Expands the search field out of the toolbar magnifier. The field scales in from its trailing edge,
    /// where the magnifier sits, so it reads as the icon growing into the field.
    private func openSearch() {
        withAnimation(.easeOut(duration: 0.28)) { searching = true }
    }
    private func openFromSearch(_ id: String) {
        let target = tab == 3 ? 0 : tab
        closeSearch()
        tab = target
        pushedBook = (target, id)
    }
    private func pushedBinding(for tab: Int) -> Binding<String?> {
        Binding(get: { pushedBook?.tab == tab ? pushedBook?.id : nil }, set: { if $0 == nil, pushedBook?.tab == tab { pushedBook = nil } })
    }
    private func closeSearch() {
        search = ""; searchFocused = false; freeBooks = false
        withAnimation(.easeIn(duration: 0.2)) { searching = false }
    }
    private var searchButton: some View {
        Button { openSearch() } label: { Image(systemName: "magnifyingglass") }.accessibilityLabel("Search")
    }
    private var searchHeader: some View {
        HStack(spacing: 8) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search books or authors", text: $search).focused($searchFocused).submitLabel(.search).autocorrectionDisabled().onSubmit { searchFocused = false }.accessibilityIdentifier("global-search")
                    .onAppear { Task { try? await Task.sleep(nanoseconds: 80_000_000); searchFocused = true } }
            }.padding(.horizontal, 16).frame(minHeight: 48).background(Color(uiColor: .secondarySystemBackground), in: Capsule())
            Button { closeSearch() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("Close search")
        }.padding(.horizontal, 16).padding(.top, 8)
    }
    func catalogue(home: Bool, downloads: Bool) -> some View {
        let tabIndex = home ? 0 : downloads ? 2 : 1
        return NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if library.busy { ProgressView("Updating library…").frame(maxWidth: .infinity) }
                    let filtered = LibrarySorting.sorted(library.books.filter { !downloads || $0.isLocal }, by: LibrarySort(rawValue: sortName) ?? .title)
                    let unfinished = library.books.filter { $0.progress > 0 && !$0.finished }
                    if home, let resume = unfinished.max(by: { ($0.lastOpenedAt ?? .distantPast) < ($1.lastOpenedAt ?? .distantPast) }) {
                        NavigationLink { BookDetail(id: resume.id) } label: {
                            HStack(spacing: 20) {
                                BookCover(book: resume).frame(width: 100, height: 145)
                                VStack(alignment: .leading, spacing: 10) {
                                    Text(resume.listening ? "CONTINUE LISTENING" : "CONTINUE READING").font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor)
                                    Text(resume.title).font(.title3.bold()).foregroundStyle(.primary).lineLimit(3)
                                    Text("\(Int(resume.progress * 100))% \(resume.listening ? "listened" : "read")").font(.subheadline).foregroundStyle(.secondary)
                                }
                            }.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 24))
                        }.buttonStyle(.plain)
                    }
                    if filtered.isEmpty {
                        ContentUnavailableView {
                            Label(search.isEmpty ? (downloads ? "No downloads yet" : "Your library starts here") : "No matching titles", systemImage: "books.vertical")
                        } description: { Text(search.isEmpty ? "Add an e-book or audiobook, or connect your BookLore library." : "Try another title or author.") }
                        actions: { if search.isEmpty { Button("Add a book") { importChoice = true }.buttonStyle(.borderedProminent) } }
                    } else {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 145), spacing: 20, alignment: .top)], alignment: .leading, spacing: 24) {
                            ForEach(filtered) { book in
                                NavigationLink { BookDetail(id: book.id) } label: {
                                    VStack(alignment: .leading, spacing: 8) {
                                        BookCover(book: book).frame(height: 215).overlay(alignment: .bottomTrailing) {
                                            HStack(spacing: 4) {
                                                if book.download != nil || book.telegramSource != nil { Image(systemName: "cloud.fill").accessibilityLabel("From your cloud library, last checked") }
                                                if book.isLocal { Image(systemName: "checkmark.circle.fill") }
                                            }.font(.caption).padding(6).background(.regularMaterial, in: Capsule()).padding(6)
                                        }
                                        if book.progress > 0 || book.finished { ProgressView(value: book.finished ? 1 : book.progress) }
                                        Text(book.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(2)
                                        Text(book.author.isEmpty ? (book.audio != nil ? "Audiobook" : "E-book") : book.author).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }.padding(20)
            }.navigationTitle(home ? "Home" : downloads ? "Downloads" : "Library")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Image("FolioLogo").renderingMode(.template).resizable().scaledToFit().foregroundStyle(portalAccent(brandAccent)).frame(width: 28, height: 28).accessibilityLabel("Folio") }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if !home {
                        Menu {
                            Picker("Sort by", selection: $sortName) { ForEach(LibrarySort.allCases) { Text($0.label).tag($0.rawValue) } }
                        } label: { Image(systemName: "arrow.up.arrow.down") }.accessibilityLabel("Sort library")
                    }
                    searchButton
                    Button { appearance = true } label: { Image(systemName: "paintpalette") }.accessibilityLabel("Appearance")
                    Button { importChoice = true } label: { Image(systemName: "plus") }.disabled(library.busy).accessibilityLabel("Add book")
                }
            }
            .refreshable { guard let account = server.account else { return }; do { try await server.refresh(account, library: library) } catch { library.error = error.localizedDescription } }
            .navigationDestination(item: pushedBinding(for: tabIndex)) { id in BookDetail(id: id) }
        }
    }
}

struct BookDetail: View {
    let id: String
    @EnvironmentObject var audio: AudioPlayer
    @EnvironmentObject var library: Library
    @EnvironmentObject var server: BookServer
    @State private var reader = false
    @State private var importer = false
    @State private var audioImport = false
    @State private var coverImport = false
    var body: some View {
        if let book = library.book(id) {
            ScrollView {
                VStack(spacing: 18) {
                    BookCover(book: book).frame(width: 200, height: 295).shadow(color: .black.opacity(0.15), radius: 16, y: 8)
                    Text(book.title).font(.title.bold()).multilineTextAlignment(.center)
                    if !book.author.isEmpty { Text(book.author).foregroundStyle(.secondary) }
                    HStack(spacing: 12) {
                        if book.ebook != nil { Button { reader = true } label: { formatLabel("E-book", icon: "book", progress: book.finished ? "Finished" : "\(Int(book.readingProgress * 100))%") }.buttonStyle(.plain) }
                        else if book.download != nil { Button { Task { library.busy = true; defer { library.busy = false }; do { try await server.download(book, library: library) } catch { library.error = error.localizedDescription } } } label: { formatLabel("Download", icon: "arrow.down", progress: "E-book") }.buttonStyle(.plain).disabled(library.busy) }
                        if book.audio != nil { NavigationLink { PlayerScreen(id: id) } label: { formatLabel("Audiobook", icon: "headphones", progress: mediaTime(book.audioPosition)) }.buttonStyle(.plain) }
                    }
                    if book.ebook == nil && book.download == nil { Button("Add e-book") { audioImport = false; importer = true }.frame(minHeight: 44) }
                    if book.audio == nil { Button("Add audiobook") { audioImport = true; importer = true }.frame(minHeight: 44) }
                    if book.streaming { Button("Keep offline") { Task {
                        do { guard let url = book.audio.flatMap(URL.init(string:)) else { return }; let entry = try await TelegramLibrary.shared.resolve(url); audio.stopForOffline(); TelegramLibrary.shared.download(entry, library: library) }
                        catch { library.error = error.localizedDescription }
                    } }.frame(minHeight: 44) }
                    if library.busy { ProgressView("Downloading…") }
                    Button("Edit artwork", systemImage: "photo") { coverImport = true }.font(.subheadline).padding(.top, 8)
                }.padding(24)
            }.navigationBarTitleDisplayMode(.inline)
            .fullScreenCover(isPresented: $reader) {
                if ["pdf", "cbz"].contains(book.ebook?.components(separatedBy: ".").last?.lowercased() ?? "") { FixedReaderScreen(id: id) } else { ReaderScreen(id: id) }
            }
            .fileImporter(isPresented: $importer, allowedContentTypes: audioImport ? [.audio, UTType(filenameExtension: "m4b") ?? .audio] : ebookTypes) { result in Task {
                library.busy = true; defer { library.busy = false }
                do { _ = try await library.importFile(result.get(), audio: audioImport, linking: id) } catch { library.error = error.localizedDescription }
            } }
            .fileImporter(isPresented: $coverImport, allowedContentTypes: [.jpeg, .png]) { result in do { try library.replaceCover(result.get(), id: id) } catch { library.error = error.localizedDescription } }
        }
    }
    func formatLabel(_ name: String, icon: String, progress: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: icon).font(.title2)
            Text(name).font(.headline)
            Text(progress).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(20).background(Color.accentColor.opacity(0.13), in: RoundedRectangle(cornerRadius: 22))
    }
}

struct AppearanceScreen: View {
    @Environment(\.dismiss) var dismiss
    @AppStorage("appearance") var appearance = "System"
    @AppStorage("accent") var accent = "Aquamarine"
    var body: some View {
        NavigationStack {
            Form {
                Picker("Appearance", selection: $appearance) { ForEach(["System", "Light", "Dark"], id: \.self) { Text($0) } }.pickerStyle(.segmented)
                Section("Accent colour") { ForEach(["Aquamarine", "Blue", "Lilac", "Peach"], id: \.self) { name in
                    Button { accent = name } label: { HStack { Circle().fill(portalAccent(name)).frame(width: 24, height: 24); Text(name).foregroundStyle(.primary); Spacer(); if name == accent { Image(systemName: "checkmark") } } }.frame(minHeight: 44)
                } }
            }.navigationTitle("Appearance").toolbar { Button("Done") { dismiss() } }
        }.presentationDetents([.medium, .large])
    }
}

struct ServerScreen: View {
    var onSearch: (() -> Void)? = nil
    @EnvironmentObject var server: BookServer
    @EnvironmentObject var library: Library
    @State var address = ""
    @State var user = ""
    @State var password = ""
    @State var expanded = false
    var body: some View {
        Form {
            Section {
                DisclosureGroup(isExpanded: $expanded) {
                    TextField("Server address", text: $address).keyboardType(.URL).textContentType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("OPDS username", text: $user).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("OPDS password", text: $password).textContentType(.password)
                    PasteButton(payloadType: String.self) { items in
                        guard let text = items.first, let pasted = PastedConnection(text) else { return }
                        DispatchQueue.main.async { address = pasted.address; user = pasted.user; password = pasted.password }
                    }
                    Text("Address looks like https://books.example.com. Paste fills all three from one line, or from a link that carries them.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button(server.account == nil ? "Connect library" : "Refresh library") { Task {
                        library.busy = true; defer { library.busy = false }
                        do { try await server.refresh(ServerAccount(url: BookServer.endpoint(address), user: user, password: password), library: library) }
                        catch { library.error = error.localizedDescription }
                    } }.disabled(library.busy || address.isEmpty || user.isEmpty || password.isEmpty)
                    if library.busy { ProgressView("Connecting…") }
                    if server.account != nil { Button("Disconnect", role: .destructive) { do { try server.disconnect(); password = "" } catch { library.error = error.localizedDescription } } }
                } label: {
                    Label { VStack(alignment: .leading, spacing: 4) { Text("BookLore").font(.headline); Text(server.account == nil ? "Not connected" : "Connected").font(.caption).foregroundStyle(.secondary) } } icon: { Image(systemName: "externaldrive") }
                }
            } footer: { Text("Use your separate BookLore OPDS credentials. Downloaded books stay available offline.") }
            TelegramSettings()
            KoSettingsView()
            Section { NotesSyncSettings() }
            Section { Text("Reading and listening positions are saved separately on this iPhone.").foregroundStyle(.secondary) }
        }.navigationTitle("Server settings")
        .toolbar { if let onSearch { ToolbarItem(placement: .topBarTrailing) { Button { onSearch() } label: { Image(systemName: "magnifyingglass") }.accessibilityLabel("Search") } } }
        .onAppear { if let account = server.account { address = account.url.absoluteString; user = account.user; password = account.password } }
        .animation(.easeInOut(duration: 0.25), value: expanded)
    }
}

private extension View {
    /// Lets the blurred page show through the search layer. The navigation container is opaque before iOS 18.
    @ViewBuilder func clearNavigationBackground() -> some View {
        if #available(iOS 18, *) { containerBackground(Color.clear, for: .navigation) } else { self }
    }
}

private struct SearchResultsHeight: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}
