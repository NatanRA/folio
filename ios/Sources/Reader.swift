import SwiftUI
import ReadiumShared
import ReadiumNavigator

@MainActor final class ReaderModel: ObservableObject, EPUBNavigatorDelegate {
    @Published var navigator: EPUBNavigatorViewController?
    @Published var positions: [Locator] = []
    @Published var contents: [ReadiumShared.Link] = []
    @Published var current = 1
    @Published var error: String?
    @Published var controls = true
    @Published var fontSize = 1.1
    @Published var scrolling = false
    @Published var marks: [SavedMark] = []
    @Published var editingMark: SavedMark?
    @Published var words: [SavedMark] = []
    @Published var dictionary: DictionaryRequest?
    @Published var paper = UserDefaults.standard.string(forKey: "reader.paper") ?? "White"
    @Published var sans = UserDefaults.standard.bool(forKey: "reader.sans")
    @Published var lineSpacing = UserDefaults.standard.object(forKey: "reader.spacing") as? Double ?? 1.45
    @Published var margins = UserDefaults.standard.object(forKey: "reader.margins") as? Double ?? 1.0
    var publication: Publication?
    weak var library: Library?
    var id = ""
    func open(_ book: MediaBook, library: Library) async {
        guard navigator == nil else { return }
        self.library = library; id = book.id
        fontSize = UserDefaults.standard.object(forKey: "reader.fontSize") as? Double ?? 1.1
        scrolling = UserDefaults.standard.bool(forKey: "reader.scrolling")
        loadMarks()
        do {
            guard let file = book.ebook else { throw PortalError.message("Download this e-book first.") }
            let publication = try await library.engine.open(library.file(file)); self.publication = publication
            positions = try await publication.positions().get()
            contents = (try? await publication.tableOfContents().get()) ?? []
            if contents.isEmpty { contents = publication.readingOrder }
            let locator = try book.locator.flatMap { try Locator(jsonString: $0) }
            let nav = try EPUBNavigatorViewController(publication: publication, initialLocation: locator,
                config: .init(preferences: preferences, editingActions: EditingAction.defaultActions + [
                    EditingAction(title: "Define", action: #selector(ReaderHost.defineWord(_:))),
                    EditingAction(title: "Highlight", action: #selector(ReaderHost.highlightText(_:))),
                    EditingAction(title: "Add note", action: #selector(ReaderHost.annotateText(_:))),
                    EditingAction(title: "Save word", action: #selector(ReaderHost.saveWord(_:)))
                ]))
            nav.delegate = self
            nav.addObserver(.tap { [weak self] event in
                guard let self, let nav = self.navigator else { return false }
                let x = event.location.x / max(1, nav.view.bounds.width)
                if x > 0.75 { Task { await nav.goForward(options: .init(animated: true)) } }
                else if x < 0.25 { Task { await nav.goBackward(options: .init(animated: true)) } }
                else { withAnimation(.easeInOut(duration: 0.2)) { self.controls.toggle() } }
                return true
            })
            navigator = nav
            decorate()
        } catch { self.error = error.localizedDescription }
    }
    var preferences: EPUBPreferences {
        EPUBPreferences(fontFamily: sans ? .sansSerif : .serif, fontSize: fontSize, lineHeight: lineSpacing, pageMargins: margins,
                        paragraphIndent: 1.25, paragraphSpacing: 0, publisherStyles: false,
                        scroll: scrolling, textAlign: .left, theme: paper == "Night" ? .dark : paper == "Sepia" ? .sepia : .light)
    }
    var paperColor: SwiftUI.Color { paper == "Night" ? .black : paper == "Sepia" ? SwiftUI.Color(red: 0.98, green: 0.957, blue: 0.91) : .white }
    func apply() {
        let defaults = UserDefaults.standard
        defaults.set(fontSize, forKey: "reader.fontSize"); defaults.set(scrolling, forKey: "reader.scrolling")
        defaults.set(paper, forKey: "reader.paper"); defaults.set(sans, forKey: "reader.sans")
        defaults.set(lineSpacing, forKey: "reader.spacing"); defaults.set(margins, forKey: "reader.margins")
        navigator?.submitPreferences(preferences)
    }
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        current = locator.locations.position ?? 1
        library?.update(id) { $0.locator = locator.jsonString; $0.readingProgress = locator.locations.totalProgression ?? 0; $0.lastOpenedAt = Date() }
    }
    func navigator(_ navigator: Navigator, presentError error: NavigatorError) { self.error = error.localizedDescription }
    func jump(_ index: Int) async {
        guard positions.indices.contains(index) else { return }
        _ = await navigator?.go(to: positions[index])
    }
}

struct NativeEPUB: UIViewControllerRepresentable {
    let model: ReaderModel
    func makeUIViewController(context: Context) -> ReaderHost { ReaderHost(model: model) }
    func updateUIViewController(_ uiViewController: ReaderHost, context: Context) {}
}

struct ReaderScreen: View {
    let id: String
    @EnvironmentObject var library: Library
    @Environment(\.dismiss) var dismiss
    @StateObject var model = ReaderModel()
    @State private var pages = false
    @State private var options = false
    @State private var tool: ReaderTool?
    @State private var sync = false
    @State private var vocabulary = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if model.navigator != nil { NativeEPUB(model: model).accessibilityIdentifier("epubReader") }
                else if let error = model.error { ContentUnavailableView("Couldn’t open book", systemImage: "book.closed", description: Text(error)) }
                else { ProgressView("Opening book…").frame(maxWidth: .infinity, maxHeight: .infinity) }
            }
            .background(model.paperColor)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button("Reading options", systemImage: "textformat.size") { options = true }
                        Button("Search book", systemImage: "magnifyingglass") { tool = .search }
                        Button("Chapters", systemImage: "list.bullet") { tool = .chapters }
                        Button("Bookmarks", systemImage: "bookmark") { tool = .bookmarks }
                        Button("Highlights & notes", systemImage: "highlighter") { tool = .notes }
                        Button("Saved words", systemImage: "character.book.closed") { vocabulary = true }
                        Button("Sync position", systemImage: "arrow.triangle.2.circlepath") { sync = true }
                        Button("Close book", systemImage: "house") { dismiss() }
                    } label: { Image(systemName: "line.3.horizontal.decrease").frame(width: 44, height: 44) }.accessibilityLabel("Reader menu")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("\(model.current) / \(max(1, model.positions.count))") { pages = true }.monospacedDigit().accessibilityIdentifier("pagePicker")
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                // Stacked explicitly: the finish button sits above the page controls instead of sharing their spot.
                VStack(spacing: 0) {
                    if model.current >= model.positions.count && !model.positions.isEmpty {
                        Button(library.book(id)?.finished == true ? "Finished · Undo" : "Mark as finished") { library.update(id) { $0.finished.toggle() } }
                            .buttonStyle(.borderedProminent).padding(.top, 8).frame(maxWidth: .infinity).background(model.paperColor)
                    }
                    if model.controls {
                        HStack(spacing: 0) {
                            Button { dismiss() } label: { Image(systemName: "house").frame(width: 70, height: 48) }.accessibilityLabel("Home")
                            Button { Task { await model.navigator?.goBackward(options: .init(animated: true)) } } label: { Image(systemName: "arrow.left").frame(width: 70, height: 48) }.accessibilityLabel("Previous page")
                            Button { Task { await model.navigator?.goForward(options: .init(animated: true)) } } label: { Image(systemName: "arrow.right").frame(width: 70, height: 48) }.accessibilityLabel("Next page")
                        }.font(.title3.weight(.semibold)).padding(4).background(.regularMaterial, in: Capsule()).padding(.vertical, 8)
                        .frame(maxWidth: .infinity).background(model.paperColor).transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
            }
            .task { if let book = library.book(id) { await model.open(book, library: library) } }
            .sheet(isPresented: $vocabulary) { VocabularyView(model: model) }
            .sheet(item: $model.dictionary) { DictionarySheet(word: $0.word) }
            .sheet(isPresented: $pages) {
                NavigationStack {
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 72))], spacing: 12) {
                            ForEach(Array(model.positions.indices), id: \.self) { i in
                                PageTile(number: i + 1, selected: model.current == i + 1) {
                                    Task { await model.jump(i); pages = false }
                                }
                            }
                        }.padding()
                        Text("Estimated pages; layout changes with text size.").font(.footnote).foregroundStyle(.secondary).padding()
                    }.background(SwiftUI.Color(uiColor: .systemGroupedBackground)).navigationTitle("Pages")
                    .toolbar { Button("Done") { pages = false } }
                }
            }
            .sheet(isPresented: $options) {
                NavigationStack {
                    Form {
                        Section("Type") {
                            Picker("Paper", selection: $model.paper) { ForEach(["White", "Sepia", "Night"], id: \.self) { Text($0) } }
                            Toggle("Sans serif", isOn: $model.sans)
                            Slider(value: $model.fontSize, in: 0.8...2, step: 0.1).accessibilityLabel("Text size")
                            Text("Text size \(Int(model.fontSize * 100))%")
                            Slider(value: $model.lineSpacing, in: 1.1...2, step: 0.05).accessibilityLabel("Line spacing")
                            Text("Line spacing \(model.lineSpacing, specifier: "%.2f")")
                            Slider(value: $model.margins, in: 0.5...2, step: 0.1).accessibilityLabel("Margins")
                            Text("Margins \(model.margins, specifier: "%.1f")")
                        }
                        Section("Navigation") { Toggle("Scroll vertically", isOn: $model.scrolling); Toggle("Show bottom controls", isOn: $model.controls) }
                    }.navigationTitle("Reading options").toolbar { Button("Done") { options = false } }
                    .onChange(of: model.fontSize) { _, _ in model.apply() }.onChange(of: model.scrolling) { _, _ in model.apply() }
                    .onChange(of: model.paper) { _, _ in model.apply() }.onChange(of: model.sans) { _, _ in model.apply() }
                    .onChange(of: model.lineSpacing) { _, _ in model.apply() }.onChange(of: model.margins) { _, _ in model.apply() }
                }.presentationDetents([.medium, .large])
            }
            .sheet(item: $tool) { ReaderToolsSheet(model: model, tab: $0) }
            .sheet(item: Binding(get: { tool == nil ? model.editingMark : nil }, set: { model.editingMark = $0 })) { NoteEditor(model: model, mark: $0) }
            .alert("Something needs attention", isPresented: Binding(get: { model.error != nil && model.navigator != nil }, set: { if !$0 { model.error = nil } })) { Button("OK") { model.error = nil } } message: { Text(model.error ?? "") }
            .sheet(isPresented: $sync) { ReaderSyncSheet(model: model) }
        }
    }
}

private struct PageTile: View {
    let number: Int
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            SwiftUI.Text(String(number))
                .font(.title2.weight(.semibold))
                .frame(maxWidth: .infinity).frame(height: 94)
                .foregroundStyle(selected ? SwiftUI.Color.black : SwiftUI.Color.primary)
                .background(selected ? SwiftUI.Color.accentColor : SwiftUI.Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
        }.accessibilityLabel("Page \(number)")
    }
}
