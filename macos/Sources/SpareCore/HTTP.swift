import Foundation

public enum HTTP {
    public static func validate(_ value: String, allowHTTP: Bool) throws -> URL {
        guard let url=URL(string: value), let scheme=url.scheme?.lowercased(), let host=url.host, !host.isEmpty,
              url.user == nil, url.password == nil, ["https","http"].contains(scheme), value.count <= 16384 else { throw SpareError.address }
        if scheme == "http" && !allowHTTP { throw SpareError.httpConsent }
        return url
    }
    public static func get(_ value: String, allowHTTP: Bool = false, limit: Int = 48*1024*1024,
                           timeout: TimeInterval = 25) async throws -> Data {
        let url=try validate(value, allowHTTP: allowHTTP)
        let request=BoundedRequest(url: url, allowHTTP: allowHTTP, limit: limit, timeout: timeout)
        return try await withTaskCancellationHandler(operation: { try await request.run() },
                                                     onCancel: { request.cancel() })
    }
}

// Own the task and bound data as it arrives, including chunked responses.
private final class BoundedRequest: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let url: URL, allowHTTP: Bool, limit: Int, timeout: TimeInterval
    private let lock=NSLock()
    private var continuation: CheckedContinuation<Data, Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var data=Data()
    private var cancelled=false, finished=false
    init(url: URL, allowHTTP: Bool, limit: Int, timeout: TimeInterval) {
        self.url=url; self.allowHTTP=allowHTTP; self.limit=limit; self.timeout=timeout
    }
    func run() async throws -> Data {
        try await withCheckedThrowingContinuation { c in
            lock.lock()
            if cancelled { lock.unlock(); c.resume(throwing: CancellationError()); return }
            continuation=c
            let config=URLSessionConfiguration.ephemeral
            config.httpCookieStorage=nil; config.urlCredentialStorage=nil; config.urlCache=nil
            config.httpShouldSetCookies=false
            config.timeoutIntervalForRequest=timeout; config.timeoutIntervalForResource=timeout*2
            let queue=OperationQueue(); queue.maxConcurrentOperationCount=1
            let session=URLSession(configuration: config, delegate: self, delegateQueue: queue)
            self.session=session
            var request=URLRequest(url: url)
            request.setValue("Spare-Mac/preview", forHTTPHeaderField: "User-Agent")
            let task=session.dataTask(with: request); self.task=task
            lock.unlock(); task.resume()
        }
    }
    func cancel() {
        lock.lock(); cancelled=true; let current=task; lock.unlock()
        current?.cancel()
    }
    private func complete(_ result: Result<Data, Error>) {
        lock.lock()
        if finished { lock.unlock(); return }
        finished=true; let c=continuation; continuation=nil
        let current=session; session=nil
        lock.unlock()
        c?.resume(with: result); current?.invalidateAndCancel()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        do {
            guard let value=request.url?.absoluteString else { throw SpareError.address }
            _=try HTTP.validate(value, allowHTTP: allowHTTP)
            completionHandler(request)
        } catch { completionHandler(nil); complete(.failure(error)) }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let response=response as? HTTPURLResponse else { completionHandler(.cancel); complete(.failure(SpareError.network)); return }
        guard (200...299).contains(response.statusCode) else {
            completionHandler(.cancel)
            complete(.failure([401,403].contains(response.statusCode) ? SpareError.authentication : SpareError.status(response.statusCode))); return
        }
        guard response.expectedContentLength <= limit else { completionHandler(.cancel); complete(.failure(SpareError.limit)); return }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        guard data.count <= limit-chunk.count else { complete(.failure(SpareError.limit)); return }
        data.append(chunk)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { complete(.failure((error as? URLError)?.code == .cancelled ? CancellationError() : SpareError.network)) }
        else { complete(.success(data)) }
    }
}
