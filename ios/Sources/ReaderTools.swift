import SwiftUI
import ReadiumShared
import ReadiumNavigator

struct SavedMark: Codable, Identifiable, Equatable {
    var id = UUID().uuidString
    var locator: String
    var quote = ""
    var note = ""
    var bookmark = false
    var location: Locator? { try? Locator(jsonString: locator) }
}

enum ReaderTool: String, Identifiable, CaseIterable {
    case search = "Search", chapters = "Chapters", bookmarks = "Bookmarks", notes = "Notes"
    var id: String { rawValue }
}

extension ReaderModel {
    func loadMarks() {
        if let url = library?.file("words-\(id).json"), FileManager.default.fileExists(atPath: url.path) {
            do { words = try JSONDecoder().decode([SavedMark].self, from: Data(contentsOf: url)) }
            catch { self.error = "Couldn’t read saved words: \(error.localizedDescription)" }
        }
        guard let url = library?.file("marks-\(id).json"), FileManager.default.fileExists(atPath: url.path) else { return }
        do { marks = try JSONDecoder().decode([SavedMark].self, from: Data(contentsOf: url)) }
        catch { self.error = "Couldn’t read saved notes: \(error.localizedDescription)" }
    }
    func saveMark(_ mark: SavedMark) {
        persistMarks(marks.filter { $0.id != mark.id } + [mark])
    }
    func removeMark(_ mark: SavedMark) { persistMarks(marks.filter { $0.id != mark.id }) }
    private func persistMarks(_ updated: [SavedMark]) {
        guard let url = library?.file("marks-\(id).json") else { return }
        do {
            if let library { try NotesExchange(library: library, id: id).record(before: marks, after: updated) }
            try JSONEncoder().encode(updated).write(to: url, options: .atomic); marks = updated; editingMark = nil; decorate()
        }
        catch { self.error = "Couldn’t save notes: \(error.localizedDescription)" }
    }
    func decorate() {
        navigator?.apply(decorations: marks.filter { !$0.bookmark }.compactMap { mark in
            mark.location.map { Decoration(id: mark.id, locator: $0, style: .highlight(tint: .systemYellow)) }
        }, in: "notes")
    }
    func addBookmark() {
        guard let locator = navigator?.currentLocation, let json = locator.jsonString else { return }
        guard !marks.contains(where: { $0.bookmark && $0.locator == locator.jsonString }) else { return }
        saveMark(SavedMark(locator: json, bookmark: true))
    }
    func captureSelection(note: Bool) {
        guard let locator = navigator?.currentSelection?.locator, let json = locator.jsonString, let quote = locator.text.highlight, !quote.isEmpty else { return }
        let mark = SavedMark(locator: json, quote: quote)
        if note { editingMark = mark } else { saveMark(mark) }
        navigator?.clearSelection()
    }
    func saveWords(_ updated: [SavedMark]) {
        guard let url = library?.file("words-\(id).json") else { return }
        do {
            if let library { try NotesExchange(library: library, id: id).record(before: words, after: updated, words: true) }
            try JSONEncoder().encode(updated).write(to: url, options: .atomic); words = updated
        }
        catch { self.error = "Couldn’t save words: \(error.localizedDescription)" }
    }
    /// Opens the bundled dictionary on the selected text, like Android's Define action.
    func defineSelection() {
        guard let word = navigator?.currentSelection?.locator.text.highlight, !word.isEmpty else { return }
        dictionary = DictionaryRequest(word: word)
        navigator?.clearSelection()
    }
    func captureWord() {
        guard let locator = navigator?.currentSelection?.locator, let json = locator.jsonString,
              let word = locator.text.highlight, !word.isEmpty, word.count <= 200,
              !words.contains(where: { $0.quote.lowercased() == word.lowercased() }) else { return }
        saveWords(words + [SavedMark(locator: json, quote: word)])
        navigator?.clearSelection()
    }
}

final class ReaderHost: UIViewController {
    let model: ReaderModel
    init(model: ReaderModel) { self.model = model; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidLoad() {
        super.viewDidLoad()
        guard let nav = model.navigator else { return }
        addChild(nav); view.addSubview(nav.view); nav.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([nav.view.leadingAnchor.constraint(equalTo: view.leadingAnchor), nav.view.trailingAnchor.constraint(equalTo: view.trailingAnchor), nav.view.topAnchor.constraint(equalTo: view.topAnchor), nav.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)])
        nav.didMove(toParent: self)
    }
    @objc func highlightText(_ sender: Any?) { model.captureSelection(note: false) }
    @objc func annotateText(_ sender: Any?) { model.captureSelection(note: true) }
    @objc func saveWord(_ sender: Any?) { model.captureWord() }
    @objc func defineWord(_ sender: Any?) { model.defineSelection() }
}

struct VocabularyView: View {
    @ObservedObject var model: ReaderModel
    @State private var query = ""
    @State private var define: DictionaryRequest?
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack {
            List {
                if model.words.isEmpty { Text("Select a word in your book and choose Save word.").foregroundStyle(.secondary) }
                ForEach(model.words.filter { query.isEmpty || $0.quote.localizedCaseInsensitiveContains(query) }) { word in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(word.quote).font(.headline)
                        Text((word.location?.text.before ?? "").suffix(80) + word.quote + (word.location?.text.after ?? "").prefix(80)).font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                        HStack(spacing: 16) {
                            Button("Define") { define = DictionaryRequest(word: word.quote) }
                            Button("Remove", role: .destructive) { model.saveWords(model.words.filter { $0.id != word.id }) }
                        }.buttonStyle(.borderless).font(.subheadline.weight(.semibold))
                    }.padding(.vertical, 8).swipeActions { Button("Remove", role: .destructive) { model.saveWords(model.words.filter { $0.id != word.id }) } }
                }
            }.searchable(text: $query, prompt: "Search words").navigationTitle("Saved words")
                .toolbar { Button("Done") { dismiss() } }
                .sheet(item: $define) { DictionarySheet(word: $0.word) }
        }
    }
}

struct ReaderToolsSheet: View {
    @ObservedObject var model: ReaderModel
    @State var tab: ReaderTool
    @State private var query = ""
    @State private var results: [Locator] = []
    @State private var busy = false
    @State private var failure: String?
    @State private var capped = false
    @State private var transfer = false
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack {
            VStack {
                Picker("Explore book", selection: $tab) { ForEach(ReaderTool.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented).padding(.horizontal)
                switch tab {
                case .search:
                    TextField("Search inside this book", text: $query).textFieldStyle(.roundedBorder).padding(.horizontal).accessibilityIdentifier("bookSearch")
                    if busy { ProgressView("Searching…") }
                    if let failure { Text(failure).foregroundStyle(.secondary).padding() }
                    if !busy && failure == nil { Text(query.trimmingCharacters(in: .whitespaces).count < 2 ? "Enter at least two characters." : (capped ? "First 500 matches. Refine your search." : "\(results.count) matches")).font(.footnote).foregroundStyle(.secondary) }
                    List(Array(results.enumerated()), id: \.offset) { _, location in
                        Button { go(location) } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(location.title ?? "Match").font(.caption).foregroundStyle(Color.secondary)
                                (Text(snippet(String((location.text.before ?? "").suffix(80)))) + Text(location.text.highlight ?? "").bold() + Text(snippet(String((location.text.after ?? "").prefix(100))))).lineLimit(4).foregroundStyle(Color.primary)
                            }.padding(.vertical, 8)
                        }
                    }
                case .chapters:
                    List(Array(flatten(model.contents).enumerated()), id: \.offset) { index, entry in
                        Button(entry.0.title ?? "Section \(index + 1)") { Task { if await model.navigator?.go(to: entry.0) == true { dismiss() } } }.padding(.leading, CGFloat(min(entry.1, 4) * 12)).padding(.vertical, 6)
                    }
                case .bookmarks, .notes:
                    if tab == .notes { Button("Sync & export") { transfer = true }.padding(.vertical, 8) }
                    if tab == .bookmarks { Button("Bookmark this page") { model.addBookmark() }.buttonStyle(.borderedProminent).foregroundStyle(portalOnAccent).padding(.vertical, 8) }
                    if tab == .notes { TextField("Search notes", text: $query).textFieldStyle(.roundedBorder).padding(.horizontal) }
                    let filtered = model.marks.filter { $0.bookmark == (tab == .bookmarks) && (tab == .bookmarks || query.isEmpty || ($0.quote + $0.note).localizedCaseInsensitiveContains(query)) }
                    if filtered.isEmpty { ContentUnavailableView(tab == .bookmarks ? "No bookmarks yet" : "No matching notes", systemImage: tab == .bookmarks ? "bookmark" : "highlighter", description: Text(tab == .bookmarks ? "Save a place to return to later." : "Select book text to highlight it or add a note.")) }
                    List(filtered) { mark in
                        VStack(alignment: .leading, spacing: 10) {
                            Button { if let location = mark.location { go(location) } } label: {
                                Text(mark.bookmark ? "\(mark.location?.title ?? "Saved place") · \(Int((mark.location?.locations.totalProgression ?? 0) * 100))%" : mark.quote).foregroundStyle(.primary).lineLimit(5)
                            }
                            if !mark.note.isEmpty { Text(mark.note).foregroundStyle(Color.accentColor) }
                            if !mark.bookmark { Button("Edit note") { model.editingMark = mark } }
                        }.padding(.vertical, 8).swipeActions { Button("Delete", role: .destructive) { model.removeMark(mark) } }
                    }
                }
            }.navigationTitle("Explore book").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("Done") { dismiss() } }
                .task(id: "\(tab.rawValue):\(query)") { if tab == .search { await search() } }
                .sheet(item: $model.editingMark) { NoteEditor(model: model, mark: $0) }
                .sheet(isPresented: $transfer) { NotesTransferView(model: model) }
        }
    }
    private func go(_ locator: Locator) { Task { if await model.navigator?.go(to: locator) == true { dismiss() } } }
    private func snippet(_ text: String) -> String { text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression) }
    private func flatten(_ links: [ReadiumShared.Link], depth: Int = 0) -> [(ReadiumShared.Link, Int)] { links.flatMap { [($0, depth)] + flatten($0.children, depth: depth + 1) } }
    private func search() async {
        results = []; failure = nil; capped = false; busy = false
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard term.count >= 2, let publication = model.publication else { return }
        busy = true
        do {
            try await Task.sleep(for: .milliseconds(300))
            let iterator = try await publication.search(query: term).get()
            defer { iterator.close() }
            var found: [Locator] = []
            while let batch = try await iterator.next().get() {
                try Task.checkCancellation(); found += batch.locators
                if found.count >= 500 { capped = true; break }
            }
            try Task.checkCancellation(); results = Array(found.prefix(500)); busy = false
        } catch is CancellationError { }
        catch { if !Task.isCancelled { failure = "Couldn’t search this book. Try again."; busy = false } }
    }
}

struct NoteEditor: View {
    @ObservedObject var model: ReaderModel
    let mark: SavedMark
    @State private var text = ""
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack {
            Form { Section("Selected text") { Text(mark.quote) }; Section("Your note") { TextEditor(text: $text).frame(minHeight: 140).accessibilityLabel("Your note") } }
                .navigationTitle("Note").toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Save") { var updated = mark; updated.note = text; model.saveMark(updated); if model.error == nil { dismiss() } } }
                }.onAppear { text = mark.note }
        }
    }
}
