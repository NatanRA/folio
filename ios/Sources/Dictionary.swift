import SwiftUI

struct DictionaryRequest: Identifiable {
    var word: String
    var id: String { word }
}

/// The in-reader dictionary. Matches the Android sheet: editable lookup, grouped senses with examples, and the WordNet credit.
struct DictionarySheet: View {
    @State private var query: String
    @State private var lookup: String
    @State private var meanings: [WordMeaning]?
    @State private var failure = false
    @State private var retry = 0
    @FocusState private var editing: Bool
    @Environment(\.dismiss) private var dismiss
    init(word: String) {
        let initial = String(word.prefix(80))
        _query = State(initialValue: initial); _lookup = State(initialValue: initial)
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Image(systemName: "character.book.closed").foregroundStyle(Color.accentColor)
                    Text("Dictionary").font(.title2.weight(.semibold))
                    Spacer()
                    Button { dismiss() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("Close dictionary")
                }
                Text("English · Available offline").font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentColor)
                HStack(spacing: 8) {
                    TextField("Word or phrase", text: $query).textFieldStyle(.roundedBorder).submitLabel(.search).autocorrectionDisabled()
                        .focused($editing).onSubmit(find).accessibilityIdentifier("dictionary-query")
                        .onChange(of: query) { _, value in if value.count > 80 { query = String(value.prefix(80)) } }
                    Button("Find", action: find).buttonStyle(.bordered).disabled(query.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                if let meanings {
                    if failure {
                        Text("Couldn’t open the dictionary.")
                        Button("Try again") { retry += 1 }
                    } else if meanings.isEmpty {
                        Text("No definition found").font(.title3.weight(.semibold))
                        Text("Try the base form or a single English word. Names and newer words may not be included.")
                    } else {
                        ForEach(grouped(meanings), id: \.heading) { group in
                            Text(group.heading.word).font(.title.weight(.semibold)).padding(.top, 8)
                            Text(group.heading.partOfSpeech).font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentColor)
                            ForEach(Array(group.senses.enumerated()), id: \.offset) { index, meaning in
                                HStack(alignment: .top, spacing: 12) {
                                    Text("\(index + 1)").font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentColor)
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(meaning.definition)
                                        ForEach(meaning.examples, id: \.self) { Text("“\($0)”").font(.subheadline).foregroundStyle(.secondary) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                    Text("Finding definition…")
                }
                Divider().padding(.top, 8)
                Text("WordNet® 3.0 · Copyright 2006 Princeton University").font(.footnote).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
        }
        .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        .task(id: "\(lookup)#\(retry)") {
            meanings = nil; failure = false
            do { meanings = try await OfflineDictionary.lookup(lookup) }
            catch is CancellationError { }
            catch { failure = true; meanings = [] }
        }
    }
    private func find() { editing = false; lookup = query.trimmingCharacters(in: .whitespaces) }
    private struct Heading: Hashable { let word: String; let partOfSpeech: String }
    private func grouped(_ meanings: [WordMeaning]) -> [(heading: Heading, senses: [WordMeaning])] {
        var groups: [(heading: Heading, senses: [WordMeaning])] = []
        for meaning in meanings {
            let heading = Heading(word: meaning.word, partOfSpeech: meaning.partOfSpeech)
            if let index = groups.firstIndex(where: { $0.heading == heading }) { groups[index].senses.append(meaning) }
            else { groups.append((heading, [meaning])) }
        }
        return groups
    }
}
