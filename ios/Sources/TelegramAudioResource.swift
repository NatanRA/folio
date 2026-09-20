import AVFoundation
import UniformTypeIdentifiers

/// Supplies AVPlayer with requested bytes, including tail metadata and seek ranges.
@MainActor final class TelegramAudioResource: NSObject, AVAssetResourceLoaderDelegate {
    private var jobs: [ObjectIdentifier: Task<Void, Never>] = [:]
    let asset: AVURLAsset
    private let resolve: (URL) async throws -> TelegramFile
    private let read: (TelegramFile, Int64, Int) async throws -> Data
    init(url: URL, resolve: @escaping (URL) async throws -> TelegramFile = { try await TelegramLibrary.shared.resolve($0) }, read: @escaping (TelegramFile, Int64, Int) async throws -> Data = { try await TelegramLibrary.shared.readRange($0, offset: $1, length: $2) }) {
        self.resolve = resolve; self.read = read
        asset = AVURLAsset(url: url)
        super.init()
        asset.resourceLoader.setDelegate(self, queue: .main)
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        let key = ObjectIdentifier(request)
        jobs[key] = Task {
            defer { jobs.removeValue(forKey: key) }
            do {
                let entry = try await resolve(asset.url)
                if let info = request.contentInformationRequest {
                    info.contentType = UTType(filenameExtension: (entry.name as NSString).pathExtension)?.identifier ?? UTType.audio.identifier
                    info.contentLength = entry.size
                    info.isByteRangeAccessSupported = true
                }
                if let data = request.dataRequest {
                    var offset = max(data.requestedOffset, data.currentOffset)
                    let end = data.requestsAllDataToEndOfResource ? entry.size : min(entry.size, data.requestedOffset + Int64(data.requestedLength))
                    while offset < end {
                        try Task.checkCancellation()
                        let bytes = try await read(entry, offset, Int(min(256 * 1024, end - offset)))
                        try Task.checkCancellation()
                        guard !bytes.isEmpty else { throw PortalError.message("Telegram returned an empty audio range.") }
                        data.respond(with: bytes); offset += Int64(bytes.count)
                    }
                }
                if !request.isCancelled { request.finishLoading() }
            } catch is CancellationError { }
            catch { if !request.isCancelled { request.finishLoading(with: error) } }
        }
        return true
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
        jobs.removeValue(forKey: ObjectIdentifier(request))?.cancel()
    }
    func cancel() { jobs.values.forEach { $0.cancel() }; jobs.removeAll() }
}
