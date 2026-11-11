import XCTest
import Network
@testable import SpareCore

final class HTTPTests:XCTestCase {
    func testXtreamChannelsLoadWhenOptionalCategoriesFail() async throws {
        let server=try FixtureServer { path in
            if path.contains("get_live_categories") { return (503,"Unavailable",[:]) }
            if path.contains("get_live_streams") { return (200,#"[{"stream_id":12,"name":"Fixture channel","category_id":"2"}]"#,[:]) }
            return (200,#"{"user_info":{"auth":1}}"#,[:])
        }
        let address=try await server.start()
        defer { server.stop() }
        let source=Playlist(id:"fixture",address:address,username:"test",password:"test",allowHTTP:true)
        let lineup=try await PlaylistService.load(source)
        XCTAssertEqual(lineup.channels.map(\.name),["Fixture channel"])
        XCTAssertEqual(lineup.channels.first?.group,"Other")
    }

    func testHTTPRejectsOversizeBodiesAndRedirectsToFiles() async throws {
        let server=try FixtureServer { path in
            if path=="/redirect" { return (302,"",["Location":"file:///tmp/spare-test-unread"]) }
            return (200,String(repeating:"x",count:4096),[:])
        }
        let address=try await server.start()
        defer { server.stop() }
        do {
            _=try await HTTP.get(address+"/large",allowHTTP:true,limit:1024)
            XCTFail("Accepted an oversized response")
        } catch { XCTAssertEqual(error as? SpareError,.limit) }
        do {
            _=try await HTTP.get(address+"/redirect",allowHTTP:true)
            XCTFail("Accepted a redirect to a file")
        } catch { XCTAssertNotNil(error as? SpareError) }
    }
}

// A loopback-only fixture exercises URLSession's real response/redirect delegate paths.
private final class FixtureServer {
    private let listener:NWListener
    private let queue=DispatchQueue(label:"spare.http.tests")
    init(response:@escaping (String)->(Int,String,[String:String])) throws {
        let parameters=NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host:"127.0.0.1",port:.any)
        listener=try NWListener(using:parameters)
        listener.newConnectionHandler={ connection in
            connection.start(queue:DispatchQueue.global())
            Self.read(connection,data:Data(),response:response)
        }
    }
    func start() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler={ [listener] state in
                switch state {
                case .ready:
                    listener.stateUpdateHandler=nil
                    continuation.resume(returning:"http://127.0.0.1:\(listener.port!.rawValue)")
                case .failed(let error):
                    listener.stateUpdateHandler=nil
                    continuation.resume(throwing:error)
                default:break
                }
            }
            listener.start(queue:queue)
        }
    }
    func stop() { listener.cancel() }
    private static func read(_ connection:NWConnection,data:Data,response:@escaping (String)->(Int,String,[String:String])) {
        connection.receive(minimumIncompleteLength:1,maximumLength:16384) { chunk,_,complete,error in
            var request=data
            if let chunk { request.append(chunk) }
            guard error==nil,request.count<=16384 else { connection.cancel();return }
            guard request.range(of:Data("\r\n\r\n".utf8)) != nil else {
                if complete { connection.cancel() } else { read(connection,data:request,response:response) }
                return
            }
            let path=String(decoding:request,as:UTF8.self).split(separator:" ").dropFirst().first.map(String.init) ?? "/"
            let (status,body,headers)=response(path),content=Data(body.utf8)
            let extra=headers.map { "\($0.key): \($0.value)\r\n" }.joined()
            var message=Data("HTTP/1.1 \(status) Fixture\r\nContent-Length: \(content.count)\r\nConnection: close\r\n\(extra)\r\n".utf8)
            message.append(content)
            connection.send(content:message,completion:.contentProcessed { _ in connection.cancel() })
        }
    }
}
