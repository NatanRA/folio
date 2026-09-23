import SwiftUI
import UIKit
import UniformTypeIdentifiers

let ebookTypes: [UTType] = [UTType(filenameExtension: "epub")!, .pdf, UTType(filenameExtension: "cbz") ?? .zip]

@main struct MediaPortalApp: App {
    @StateObject var library = Library()
    @StateObject var server = BookServer()
    @StateObject var audio = AudioPlayer()
    @StateObject var sync = KoSync()
    @State private var navigation: FolioNavigation?
    @AppStorage("appearance") var appearance = "System"
    @AppStorage("accent") var accent = "Aquamarine"
    @Environment(\.scenePhase) var phase
    var body: some Scene {
        WindowGroup {
            PortalHome(navigation: navigation).environmentObject(library).environmentObject(server).environmentObject(audio).environmentObject(sync)
                .tint(portalAccent(accent))
                .onChange(of: appearance, initial: true) { _, _ in applyWindowScheme(appearance) }
                // Coming to the front is when a device first has a chance to learn where the reader
                // got to elsewhere; going to the back is its last chance to send where it got to.
                .onChange(of: phase, initial: true) { _, value in
                    if value == .active || value == .background { PositionSync.shared.exchange(library: library) }
                }
                .task { TelegramLibrary.shared.library = library; if TelegramLibrary.shared.configured { TelegramLibrary.shared.start() } }
                .onOpenURL { url in
                    // Invalid navigation links must never fall through to file import.
                    if url.scheme == "folio" {
                        if let destination = FolioNavigation(url: url) { navigation = destination }
                        return
                    }
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

let portalAccentNames = ["Aquamarine", "Blue", "Lilac", "Peach", "Sakura"]

/// An accent as a deep shade for light surfaces and a pale one for dark surfaces. Both keep text at least
/// 4.6:1 against every background the app draws them on, including the accent's own tinted cards.
struct AccentShades: Equatable {
    let light: UInt32
    let dark: UInt32
    var color: Color { Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) }) }
}

func portalAccent(_ name: String) -> Color {
    switch name {
    case "Blue": AccentShades(light: 0x245CB0, dark: 0x89B4FF).color
    case "Lilac": AccentShades(light: 0x6750A4, dark: 0xC3A5F2).color
    case "Peach": AccentShades(light: 0x98452A, dark: 0xF2A072).color
    case "Sakura": AccentShades.sakura.color
    default: AccentShades(light: 0x006A62, dark: 0x39C4B2).color
    }
}

extension AccentShades {
    static let sakura = AccentShades(light: 0xA43455, dark: 0xF4A6BC)
}

/// Text and icons drawn on a filled accent: white on the deep light-mode shades, black on the pale dark-mode ones.
let portalOnAccent = Color(UIColor { $0.userInterfaceStyle == .dark ? .black : .white })

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat(rgb >> 16 & 0xFF) / 255, green: CGFloat(rgb >> 8 & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
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
    var navigation: FolioNavigation? = nil
    @AppStorage("accent") private var brandAccent = "Aquamarine"
    @AppStorage("library.sort") private var sortName = LibrarySort.title.rawValue
    @EnvironmentObject var library: Library
    @EnvironmentObject var server: BookServer
    @EnvironmentObject var audio: AudioPlayer
    @State private var tab = 0
    @Namespace private var playerZoom
    @State private var search = ""
    @State private var searching = false
    @State private var freeBooks = false
    @FocusState private var searchFocused: Bool
    @State private var importing = false
    @State private var importAudio = false
    @State private var importChoice = false
    @State private var appearance = false
    @State private var resultsHeight: CGFloat = 0
    @State private var miniPlayerHeight: CGFloat = 0
    /// A book opened from search is pushed on the visible tab's own stack, so the tab bar stays.
    @State private var pushedBook: (tab: Int, id: String)?
    var body: some View {
        VStack(spacing: 0) {
        ZStack {
        TabView(selection: $tab) {
            catalogue(home: true, downloads: false).tabItem { Label("Home", systemImage: "house") }.tag(0)
            catalogue(home: false, downloads: false).tabItem { Label("Library", systemImage: "books.vertical") }.tag(1)
            catalogue(home: false, downloads: true).tabItem { Label("Downloads", systemImage: "arrow.down.circle") }.tag(2)
            NavigationStack { ServerScreen(onSearch: openSearch) }.safeAreaInset(edge: .bottom) { miniPlayer(tab: 3) }.tabItem { Label("Server", systemImage: "externaldrive") }.tag(3)
        }
        .id(navigation?.id)
        .onChange(of: navigation?.id, initial: true) { _, _ in
            guard let navigation else { return }
            tab = navigation.tab
            search = navigation.query ?? ""
            searching = navigation.query != nil
            freeBooks = false
            searchFocused = false
            pushedBook = nil
            appearance = false
            importChoice = false
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
        .sheet(item: $audio.playerRoute) { route in PlayerScreen(id: route.id).zoomTransition(from: "mini-player", in: playerZoom, active: route.fromMiniPlayer) }
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
                        actions: { if search.isEmpty { Button("Add a book") { importChoice = true }.buttonStyle(.borderedProminent).foregroundStyle(portalOnAccent) } }
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
        .environment(\.miniPlayerClearance, audio.bookID == nil ? 0 : miniPlayerHeight)
        .safeAreaInset(edge: .bottom) { miniPlayer(tab: tabIndex) }
    }
    /// Inset inside each tab, where the tab bar has already taken its space, so it sits above the bar.
    /// Inset around the whole `TabView` instead, it was drawn over the tab bar and left no way to
    /// change tabs while an audiobook was loaded.
    /// It floats as its own capsule, matching the tab bar, rather than a full-width strip behind it.
    /// Every tab carries its own copy, so only the visible tab's is the zoom source. It stays in place
    /// under the open player, so the player has something to shrink back into.
    @ViewBuilder private func miniPlayer(tab owner: Int) -> some View {
        if let id = audio.bookID, let book = library.book(id) {
            HStack(spacing: 4) {
                Button { audio.playerRoute = PlayerRoute(id: id, fromMiniPlayer: true) } label: {
                    HStack(spacing: 12) {
                        Group {
                            if let cover = book.cover, let image = UIImage(contentsOfFile: library.file(cover).path) { Image(uiImage: image).resizable().scaledToFill() }
                            else { Image(systemName: "headphones").frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.accentColor.opacity(0.12)) }
                        }.frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(book.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text(audio.chapters.indices.contains(audio.chapterIndex) ? audio.chapters[audio.chapterIndex].name : book.author).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                    }.contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel("Open player, \(book.title)")
                Button { audio.playing ? audio.pause() : audio.play() } label: { Image(systemName: audio.playing ? "pause.fill" : "play.fill").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel(audio.playing ? "Pause audiobook" : "Play audiobook")
            }
            .padding(.leading, 10).padding(.trailing, 6).padding(.vertical, 6)
            .miniPlayerBackground()
            .zoomSource(id: "mini-player", in: playerZoom, active: owner == tab)
            .padding(.horizontal, 20).padding(.bottom, 8)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { miniPlayerHeight = $0 }
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
    @Environment(\.miniPlayerClearance) private var miniPlayerClearance
    var body: some View {
        if let book = library.book(id) {
            // The page fits between the navigation and tab bars, with the artwork taking the height that is left.
            // It only scrolls when the text is too large for even a small cover.
            ViewThatFits(in: .vertical) {
                details(book, fitting: true).frame(maxHeight: .infinity, alignment: .top)
                ScrollView { details(book, fitting: false) }
            }.safeAreaPadding(.bottom, miniPlayerClearance).navigationBarTitleDisplayMode(.inline)
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
    func details(_ book: MediaBook, fitting: Bool) -> some View {
        VStack(spacing: 18) {
            artwork(book, fitting: fitting)
            Text(book.title).font(.title.bold()).multilineTextAlignment(.center)
            if !book.author.isEmpty { Text(book.author).foregroundStyle(.secondary) }
            HStack(spacing: 12) {
                if book.ebook != nil { Button { reader = true } label: { formatLabel("E-book", icon: "book", progress: book.finished ? "Finished" : "\(Int(book.readingProgress * 100))%") }.buttonStyle(.plain) }
                else if book.download != nil { Button { Task { library.busy = true; defer { library.busy = false }; do { try await server.download(book, library: library) } catch { library.error = error.localizedDescription } } } label: { formatLabel("Download", icon: "arrow.down", progress: "E-book") }.buttonStyle(.plain).disabled(library.busy) }
                if book.audio != nil { Button { audio.playerRoute = PlayerRoute(id: id) } label: { formatLabel("Audiobook", icon: "headphones", progress: mediaTime(book.audioPosition)) }.buttonStyle(.plain) }
                else if book.audioDownload != nil { Button { Task { library.busy = true; defer { library.busy = false }; do { try await server.downloadAudio(book, library: library) } catch { library.error = error.localizedDescription } } } label: { formatLabel("Download", icon: "arrow.down", progress: "Audiobook") }.buttonStyle(.plain).disabled(library.busy) }
            }
            if book.ebook == nil && book.download == nil { Button("Add e-book") { audioImport = false; importer = true }.frame(minHeight: 44) }
            if book.audio == nil && book.audioDownload == nil { Button("Add audiobook") { audioImport = true; importer = true }.frame(minHeight: 44) }
            if book.streaming { Button("Keep offline") { Task {
                do { guard let url = book.audio.flatMap(URL.init(string:)) else { return }; let entry = try await TelegramLibrary.shared.resolve(url); audio.stopForOffline(); TelegramLibrary.shared.download(entry, library: library) }
                catch { library.error = error.localizedDescription }
            } }.frame(minHeight: 44) }
            if library.busy { ProgressView("Downloading…") }
            Button("Edit artwork", systemImage: "photo") { coverImport = true }.font(.subheadline).padding(.top, 8)
        }.padding(24)
    }
    /// The cover at its own proportions, at most 240 points wide and 320 tall. On a page that fits the screen it
    /// grows into the height the rest leaves, from 140 points; in the scrolling fallback it is full size.
    func artwork(_ book: MediaBook, fitting: Bool) -> some View {
        let size = book.cover.flatMap { UIImage(contentsOfFile: library.file($0).path)?.size }
        let aspect = size.map { $0.width / max($0.height, 1) } ?? 200 / 295
        let tallest = min(320, 240 / aspect)
        return BookCover(book: book).aspectRatio(aspect, contentMode: .fit)
            .frame(maxWidth: 240, idealHeight: fitting ? min(140, tallest) : tallest, maxHeight: tallest)
            .layoutPriority(-1) // The title and buttons keep their full height; the cover takes what is left.
            .shadow(color: .black.opacity(0.15), radius: 16, y: 8)
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
                Section("Accent colour") { ForEach(portalAccentNames, id: \.self) { name in
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

extension EnvironmentValues {
    /// The mini player's height while it shows. The tab's inset only moves the stack's root page up, so a
    /// pushed page reserves this itself instead of laying out behind the mini player.
    @Entry var miniPlayerClearance: CGFloat = 0
}

private extension View {
    /// Lets the blurred page show through the search layer. The navigation container is opaque before iOS 18.
    @ViewBuilder func clearNavigationBackground() -> some View {
        if #available(iOS 18, *) { containerBackground(Color.clear, for: .navigation) } else { self }
    }
    /// Marks the view the player zooms out of, on iOS 18 and later.
    @ViewBuilder func zoomSource(id: String, in namespace: Namespace.ID, active: Bool) -> some View {
        if #available(iOS 18, *), active { matchedTransitionSource(id: id, in: namespace) } else { self }
    }
    /// Grows the player out of its source and, on a swipe down, shrinks it back in; iOS 17 keeps the plain sheet.
    @ViewBuilder func zoomTransition(from id: String, in namespace: Namespace.ID, active: Bool) -> some View {
        if #available(iOS 18, *), active { navigationTransition(.zoom(sourceID: id, in: namespace)) } else { self }
    }
    /// Liquid Glass where the tab bar is glass too; a frosted capsule on earlier versions.
    @ViewBuilder func miniPlayerBackground() -> some View {
        if #available(iOS 26, *) { glassEffect(.regular, in: Capsule()) }
        else { background(.regularMaterial, in: Capsule()).shadow(color: .black.opacity(0.12), radius: 12, y: 4) }
    }
}

private struct SearchResultsHeight: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}
