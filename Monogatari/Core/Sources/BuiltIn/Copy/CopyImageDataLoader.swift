import Foundation
import Nuke

/// Copy reader pages and covers share their verified CDN and network recovery.
final class CopyImageDataLoader: DataLoading, @unchecked Sendable {
    private let fallback: any DataLoading
    private let client: CopyClient
    init(fallback: any DataLoading, client: CopyClient = CopyClient()) {
        self.fallback = fallback
        self.client = client
    }
    func loadData(with request: URLRequest,
                  didReceiveData: @escaping @Sendable (Data, URLResponse) -> Void,
                  completion: @escaping @Sendable (Error?) -> Void) -> any Cancellable {
        guard CopyClient.isCopyImage(request.url) else {
            return fallback.loadData(with: request, didReceiveData: didReceiveData, completion: completion)
        }
        let task = Task {
            do {
                let (data, response) = try await client.imageData(for: request)
                try Task.checkCancellation()
                didReceiveData(data, response)
                completion(nil)
            } catch CopyError.http(let status, _) { completion(DataLoader.Error.statusCodeUnacceptable(status)) }
            catch { completion(error) }
        }
        return CopyImageLoadTask(task: task)
    }
}

private struct CopyImageLoadTask: Cancellable {
    let task: Task<Void, Never>
    func cancel() { task.cancel() }
}
