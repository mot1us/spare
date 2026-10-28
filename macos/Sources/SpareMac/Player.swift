import AppKit
import SwiftUI
import CSpare
import SpareCore

struct PlaybackTrack:Identifiable,Equatable { let id:Int32, name:String }
private struct PlayerSnapshot:Sendable {
    var state:Int32=0, width:UInt32=0, height:UInt32=0, fps:Float=0, time:Int64=0
}
private struct QueueHandle:@unchecked Sendable { let raw:OpaquePointer }
private struct QueueView:@unchecked Sendable { let raw:UnsafeMutableRawPointer }
private final class CommandToken:@unchecked Sendable {
    private let lock=NSLock()
    private var value=0
    func set(_ next:Int) { lock.lock();value=next;lock.unlock() }
    func matches(_ next:Int)->Bool { lock.lock();defer { lock.unlock() };return value==next }
}

// All blocking libvlc work, including stop/reconnect, runs on one worker.
@MainActor final class Player:ObservableObject {
    @Published var loading=false
    @Published var playing=false
    @Published var error:String?
    @Published var quality=""
    @Published var paused=false
    @Published var audioTracks=[PlaybackTrack]()
    @Published var subtitleTracks=[PlaybackTrack]()
    @Published var volume:Double=85 { didSet { let v=Int32(volume); perform { spare_player_volume($0,v) } } }
    var didMeasure:((Int,Double)->Void)?
    var didSwitchCandidate:((Int)->Void)?
    private let queue=DispatchQueue(label:"spare.video",qos:.userInitiated)
    private let commandToken=CommandToken()
    private var handle:QueueHandle?
    private var surface:NSView?
    private var timer:Timer?
    private var generation=0, retries=0
    private var urls=[String](), urlIndex=0
    private var started=Date(), lastProgress=Date(), lastTime:Int64 = -1
    private var trackPoll=0, stopped=true
    private var pollPending=false
    init() {
        // Cocoa must exist before libvlc starts its native video output.
        _=NSApplication.shared
        let runtime=Bundle.main.privateFrameworksURL?.appendingPathComponent("VLCRuntime").path
            ?? ""
        queue.async { [weak self] in
            let handle=spare_player_create(runtime).map { QueueHandle(raw:$0) }
            DispatchQueue.main.async {
                guard let self else { return }
                self.handle=handle
                if handle == nil { self.error="Playback engine could not start. Reinstall the complete Spare app." }
                if let surface=self.surface, let handle { self.bind(surface,to:handle) }
            }
        }
        timer=Timer.scheduledTimer(withTimeInterval:0.75,repeats:true) { [weak self] _ in Task { @MainActor in self?.poll() } }
    }
    private func perform(_ block:@escaping (OpaquePointer)->Void) {
        guard let handle else { return }
        queue.async { block(handle.raw) }
    }
    func attach(_ view:NSView) {
        guard surface !== view else { return }
        surface=view
        if let handle { bind(view,to:handle) }
    }
    private func bind(_ view:NSView,to handle:QueueHandle) {
        let raw=QueueView(raw:Unmanaged.passUnretained(view).toOpaque())
        queue.async { spare_player_bind(handle.raw,raw.raw) }
    }
    private func advance() {
        generation += 1;commandToken.set(generation)
    }
    func play(_ candidates:[String]) {
        guard !candidates.isEmpty else { return }
        advance(); urls=Array(candidates.prefix(6)); urlIndex=0; retries=0; stopped=false
        error=nil; audioTracks=[]; subtitleTracks=[]; quality=""; paused=false
        tune()
    }
    private func tune() {
        guard urlIndex<urls.count else { fail(); return }
        guard handle != nil else {
            if error == nil {
                let expected=generation
                Task { try? await Task.sleep(nanoseconds:300_000_000); if expected==generation { tune() } }
            }
            return
        }
        loading=true; playing=false; started=Date(); lastProgress=Date(); lastTime = -1; trackPoll=0
        let url=urls[urlIndex], expected=generation,token=commandToken
        perform { [weak self] handle in
            guard token.matches(expected) else { return }
            spare_player_stop(handle)
            guard token.matches(expected) else { return }
            let status=spare_player_play(handle,url)
            DispatchQueue.main.async {
                if let self, expected==self.generation, status<0 { self.recover() }
            }
        }
    }
    func stop() {
        advance(); stopped=true; urls=[]; loading=false; playing=false; paused=false
        perform { spare_player_stop($0) }
    }
    func togglePause() { paused.toggle(); let value:Int32=paused ? 1:0; perform { spare_player_pause($0,value) } }
    func retry() { guard !urls.isEmpty else { return }; advance(); retries=0; urlIndex=0; error=nil; stopped=false; tune() }
    func selectTrack(_ id:Int32,subtitles:Bool) { perform { spare_player_track_select($0,subtitles ? 1:0,id) } }
    private func poll() {
        guard !stopped, handle != nil,!pollPending else { return }
        pollPending=true
        let expected=generation
        let updateTracks=trackPoll%6==0; trackPoll += 1
        perform { [weak self] handle in
            let s=PlayerSnapshot(state:spare_player_state(handle),width:spare_player_width(handle),height:spare_player_height(handle),
                fps:spare_player_fps(handle),time:spare_player_time(handle))
            func tracks(_ sub:Int32)->[PlaybackTrack] {
                let count=spare_player_track_count(handle,sub)
                return (0..<count).map { index in
                    PlaybackTrack(id:spare_player_track_id(handle,sub,index),name:String(cString:spare_player_track_name(handle,sub,index)))
                }
            }
            let audio=updateTracks ? tracks(0):[], subtitles=updateTracks ? tracks(1):[]
            DispatchQueue.main.async {
                guard let self else { return }
                self.pollPending=false
                guard expected==self.generation,!self.stopped else { return }
                if updateTracks { self.audioTracks=audio; self.subtitleTracks=subtitles }
                self.consume(s)
            }
        }
    }
    private func consume(_ snapshot:PlayerSnapshot) {
        if paused { loading=false; return }
        let now=Date()
        if snapshot.time != lastTime && snapshot.time>=0 { lastTime=snapshot.time; lastProgress=now }
        if snapshot.state==3 && snapshot.width>0 {
            loading=false; playing=true
            let rate=snapshot.fps>0 ? String(format:" · %.2f fps",snapshot.fps):""
            let next="\(snapshot.width) × \(snapshot.height)\(rate)"
            if quality != next {
                quality=next
                didMeasure?(Int(snapshot.height),Double(snapshot.fps))
            }
        } else if snapshot.state==1 || snapshot.state==2 {
            // Short rebuffering keeps the picture and only adds a small spinner.
            loading=true
        }
        if snapshot.state==6 || snapshot.state==7 { recover(); return }
        if loading && now.timeIntervalSince(started)>20 || playing && now.timeIntervalSince(lastProgress)>25 { recover() }
    }
    private func recover() {
        guard !stopped else { return }
        advance()
        if playing && retries<2 { retries += 1; tune() }
        else if urlIndex+1<urls.count { urlIndex += 1; retries=0; didSwitchCandidate?(urlIndex); tune() }
        else if retries<1 { retries += 1;tune() }
        else { fail() }
    }
    private func fail() { stopped=true; loading=false; playing=false; error=SpareError.unavailable.errorDescription }
}

private final class VideoHost:NSView {
    override var isOpaque:Bool { true }
    override init(frame:NSRect) { super.init(frame:frame); wantsLayer=true; layer?.backgroundColor=NSColor.black.cgColor }
    required init?(coder:NSCoder) { fatalError() }
}
struct VideoSurface:NSViewRepresentable {
    @ObservedObject var player:Player
    func makeNSView(context:Context)->NSView {
        let view=VideoHost(frame:.zero); player.attach(view); return view
    }
    func updateNSView(_ view:NSView,context:Context) { player.attach(view) }
}
