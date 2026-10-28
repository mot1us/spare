import Foundation
import SwiftUI
import SpareCore

enum Destination:String,CaseIterable { case guide="Live TV", sports="Sports" }

@MainActor final class AppStore:ObservableObject {
    @Published var playlists=[Playlist]()
    @Published var activeID=""
    @Published var channels=[Channel]()
    @Published var groups=[String]()
    @Published var visibleChannels=[Channel]()
    @Published var guide=[String:[Programme]]()
    @Published var destination:Destination = .guide
    @Published var category="All channels"
    @Published var query="" { didSet { filter() } }
    @Published var favorites=Set<String>()
    @Published var current:Channel?
    @Published var previous:Channel?
    @Published var loading=false
    @Published var guideLoading=false
    @Published var loadingMessage=""
    @Published var notice:String?
    @Published var showPlaylists=false
    @Published var showSettings=false
    @Published var fullScreen=false
    @Published var games=[Game]()
    @Published var sportsLoading=false
    @Published var sportsError:String?
    @Published var sportsDate=Date()
    @Published var sportsLiveOnly=false
    @Published var sportsLeague="All leagues"
    @Published var enabledLeagues=Set(League.all.map(\.id))
    @Published var followedTeams=Set<String>()
    @Published var hideScores=false
    @Published var matching=false
    @Published var streamResults=[StreamMatch]()
    @Published var selectedGame:Game?
    @Published var showStreams=false
    @Published var lightAppearance=false
    let player=Player()
    private let vault:Vault, sportsService=SportsService(), prefs:UserDefaults
    private var lineupTask:Task<Void,Never>?, epgTask:Task<Void,Never>?, filterTask:Task<Void,Never>?, warmTask:Task<Void,Never>?
    private var sportsTask:Task<Void,Never>?, matchTask:Task<Void,Never>?, tuneTask:Task<Void,Never>?
    private var sourceGeneration=0, sportsGeneration=0, filterGeneration=0
    private var epgVisible=Set<String>(), epgChecked=[String:Date](), byID=[String:Channel](), searchIndex=[String:String]()
    private var nowTimer:Timer?, sportsTimer:Timer?
    var source:Playlist? { playlists.first { $0.id==activeID } }
    var currentProgramme:Programme? { current.flatMap { nowPlaying($0) } }
    var demo:Bool { ProcessInfo.processInfo.arguments.contains("--demo") }

    init() {
        let demo=ProcessInfo.processInfo.arguments.contains("--demo")
        prefs=demo ? UserDefaults(suiteName:"io.github.mot1us.spare.demo")! : .standard
        let directory=ProcessInfo.processInfo.environment["SPARE_DATA_DIR"].map { URL(fileURLWithPath:$0,isDirectory:true) }
        vault=Vault(directory:directory,testMode:demo)
        favorites=Set(prefs.stringArray(forKey:"favorites") ?? [])
        followedTeams=Set(prefs.stringArray(forKey:"teams") ?? [])
        enabledLeagues=Set(prefs.stringArray(forKey:"leagues") ?? League.all.map(\.id))
        hideScores=prefs.bool(forKey:"hideScores")
        lightAppearance=prefs.bool(forKey:"lightAppearance")
        player.didMeasure={ [weak self] height,fps in
            guard let self,let current=self.current,let index=self.channels.firstIndex(where:{$0.id==current.id}) else { return }
            guard self.channels[index].height != height || abs(self.channels[index].fps-fps)>0.2 else { return }
            self.channels[index].height=height
            if fps>=10 && fps<=120 { self.channels[index].fps=fps }
        }
        nowTimer=Timer.scheduledTimer(withTimeInterval:60,repeats:true) { [weak self] _ in
            Task { @MainActor in self?.objectWillChange.send() }
        }
        sportsTimer=Timer.scheduledTimer(withTimeInterval:60,repeats:true) { [weak self] _ in
            Task { @MainActor in
                guard let self,self.destination == .sports,!self.sportsLoading else { return }
                self.loadSports()
            }
        }
        Task { await bootstrap() }
    }
    private func bootstrap() async {
        if demo { await loadDemo(); return }
        do {
            if let saved=try await vault.secret("playlists") { playlists=try JSONDecoder().decode([Playlist].self,from:saved) }
            activeID=prefs.string(forKey:"activePlaylist") ?? playlists.first?.id ?? ""
            if source == nil { activeID=playlists.first?.id ?? "" }
            if let source { activate(source) } else { showPlaylists=true }
        } catch { notice=SpareError.storage.errorDescription; showPlaylists=true }
    }
    func activate(_ source:Playlist,refresh:Bool=false) {
        if activeID != source.id || current.map({ (try? HTTP.validate($0.url,allowHTTP:source.allowHTTP)) == nil }) == true {
            tuneTask?.cancel();player.stop();current=nil;previous=nil
        }
        activeID=source.id; prefs.set(activeID,forKey:"activePlaylist")
        sourceGeneration += 1; let generation=sourceGeneration
        lineupTask?.cancel(); epgTask?.cancel(); warmTask?.cancel(); matchTask?.cancel()
        epgVisible=[]; epgChecked=[:]; guide=[:]; category="All channels"; query=""
        loading=true; loadingMessage="Opening saved channels…"; notice=nil
        lineupTask=Task {
            do {
                if !refresh,let saved=try? await vault.load(Lineup.self,name:"lineup:"+source.id) {
                    await apply(saved,source:source,generation:generation)
                    if let cached=try? await vault.load([String:[Programme]].self,name:"epg:"+source.id),generation==sourceGeneration { guide=cached }
                    loading=false
                    if saved.savedAt.timeIntervalSinceNow < -24*3600 { refreshPlaylist(inBackground:true) }
                    else { loadGuide(source:source,address:saved.epgURL,generation:generation) }
                    return
                }
                let result=try await PlaylistService.load(source) { [weak self] phase in
                    await self?.setPhase(phase,generation:generation)
                }
                try Task.checkCancellation()
                await apply(result,source:source,generation:generation)
                try await vault.save(result,name:"lineup:"+source.id)
                if generation==sourceGeneration { loading=false; loadGuide(source:source,address:result.epgURL,generation:generation) }
            } catch is CancellationError {} catch {
                if generation==sourceGeneration { loading=false; notice=SpareError.safe(error) }
            }
        }
    }
    private func setPhase(_ value:String,generation:Int) { if sourceGeneration==generation { loadingMessage=value } }
    private func apply(_ lineup:Lineup,source:Playlist,generation:Int) async {
        let index=await Task.detached(priority:.userInitiated) {
            (lineup.groups,Dictionary(uniqueKeysWithValues:lineup.channels.map { ($0.id,$0) }),
             Dictionary(uniqueKeysWithValues:lineup.channels.map { ($0.id,$0.searchKey) }))
        }.value
        guard generation==sourceGeneration,!Task.isCancelled else { return }
        channels=lineup.channels; groups=index.0; byID=index.1; searchIndex=index.2; filter()
        if current == nil,let last=prefs.string(forKey:"last:"+source.id),let channel=byID[last] { play(channel) }
    }
    func refreshPlaylist(inBackground:Bool=false) {
        guard let source else { return }
        if !inBackground { activate(source,refresh:true); return }
        let generation=sourceGeneration
        lineupTask=Task {
            do {
                let result=try await PlaylistService.load(source)
                try Task.checkCancellation()
                await apply(result,source:source,generation:generation)
                try await vault.save(result,name:"lineup:"+source.id)
                if generation==sourceGeneration { loadGuide(source:source,address:result.epgURL,generation:generation) }
            } catch {}
        }
    }
    private func loadGuide(source:Playlist,address:String,generation:Int) {
        guard !address.isEmpty,!demo else { return }
        epgTask?.cancel()
        epgTask=Task {
            guideLoading=true
            do {
                let result=try await GuideService.load(address,allowHTTP:source.allowHTTP)
                try Task.checkCancellation()
                if generation==sourceGeneration {
                    guide.merge(result,uniquingKeysWith: { _,new in new })
                    try await vault.save(guide,name:"epg:"+source.id)
                }
            } catch is CancellationError {} catch {
                // Existing guide and per-channel data remain usable if the full XMLTV endpoint fails.
                if generation==sourceGeneration && source.kind=="m3u" && guide.isEmpty {
                    notice="Channels are ready. Guide data could not be refreshed; check the XMLTV address in Playlists."
                }
            }
            if generation==sourceGeneration { guideLoading=false }
        }
    }
    func filter() {
        filterTask?.cancel(); filterGeneration += 1
        let generation=filterGeneration, values=channels, group=category, search=TextMatch.normalize(query), favorites=favorites,index=searchIndex
        filterTask=Task {
            if !search.isEmpty { try? await Task.sleep(nanoseconds:120_000_000) }
            guard !Task.isCancelled else { return }
            let filtered=await Task.detached(priority:.userInitiated) {
                values.filter { channel in
                    (group=="All channels" || group=="Favorites" && favorites.contains(channel.id) || channel.group==group)
                    && (search.isEmpty || (index[channel.id] ?? "").contains(search))
                }
            }.value
            if !Task.isCancelled,generation==filterGeneration { visibleChannels=filtered }
        }
    }
    func selectCategory(_ value:String) { category=value; filter() }
    func favorite(_ channel:Channel) {
        if !favorites.insert(channel.id).inserted { favorites.remove(channel.id) }
        prefs.set(Array(favorites),forKey:"favorites"); filter()
    }
    func nowPlaying(_ channel:Channel,date:Date=Date())->Programme? {
        guide[GuideService.key(channel)]?.first { $0.start<=date && $0.end>date }
    }
    func play(_ channel:Channel,alternates:[Channel]=[]) {
        guard let source,(try? HTTP.validate(channel.url,allowHTTP:source.allowHTTP)) != nil else { notice=SpareError.httpConsent.errorDescription; return }
        if current?.id != channel.id { previous=current }
        current=channel; prefs.set(channel.id,forKey:"last:"+source.id)
        tuneTask?.cancel()
        let choices=([channel]+alternates).flatMap { value in
            PlaylistService.playbackURLs(value,source:source).compactMap { url -> (Channel,String)? in
                guard let valid=try? HTTP.validate(url,allowHTTP:source.allowHTTP) else { return nil }
                return (value,valid.absoluteString)
            }
        }.prefix(6).map { $0 }
        let candidates=choices.map(\.1)
        player.didSwitchCandidate={ [weak self] index in
            guard let self,index<choices.count else { return }
            self.current=choices[index].0
            self.prefs.set(choices[index].0.id,forKey:"last:"+source.id)
        }
        tuneTask=Task {
            try? await Task.sleep(nanoseconds:100_000_000)
            guard !Task.isCancelled else { return }
            player.play(candidates)
        }
        warmEPG(channel)
    }
    func previousChannel() { if let previous { play(previous) } }
    func warmEPG(_ channel:Channel,visible:Bool=true) {
        if visible { epgVisible.insert(channel.id) } else { epgVisible.remove(channel.id); return }
        guard !demo,source?.kind=="xtream",warmTask==nil else { return }
        warmTask=Task {
            try? await Task.sleep(nanoseconds:220_000_000)
            let generation=sourceGeneration
            while !Task.isCancelled,let source,self.sourceGeneration==generation {
                let next=epgVisible.compactMap { byID[$0] }.filter { channel in
                    (epgChecked[channel.id]?.timeIntervalSinceNow ?? -1000)<(-300)
                    && (guide[GuideService.key(channel),default:[]].last?.end ?? .distantPast) < Date().addingTimeInterval(3600)
                }.prefix(2)
                if next.isEmpty { break }
                for channel in next { epgChecked[channel.id]=Date() }
                let results=await withTaskGroup(of:(Channel,[Programme]).self,returning:[(Channel,[Programme])].self) { group in
                    for channel in next { group.addTask { (channel,(try? await PlaylistService.shortEPG(source,channel:channel)) ?? []) } }
                    var results=[(Channel,[Programme])]();for await result in group { results.append(result) };return results
                }
                guard generation==sourceGeneration,!Task.isCancelled else { break }
                for (channel,entries) in results where !entries.isEmpty { guide[GuideService.key(channel)]=entries }
            }
            if generation==sourceGeneration { warmTask=nil }
        }
    }
    func savePlaylist(_ playlist:Playlist) async -> Bool {
        do {
            _=try HTTP.validate(playlist.address,allowHTTP:playlist.allowHTTP)
            if playlist.kind=="xtream" {
                guard !playlist.username.isEmpty,!playlist.password.isEmpty else { notice="Enter your provider username and password.";return false }
                _=try PlaylistService.endpoint(playlist)
            }
            var updated=playlists
            if let index=updated.firstIndex(where:{$0.id==playlist.id}) { updated[index]=playlist } else { updated.append(playlist) }
            try await vault.saveSecret(JSONEncoder().encode(updated),key:"playlists")
            playlists=updated; showPlaylists=false; activate(playlist,refresh:true); return true
        } catch { notice=SpareError.safe(error);return false }
    }
    func deletePlaylist(_ playlist:Playlist) async {
        let updated=playlists.filter { $0.id != playlist.id }
        do {
            try await vault.saveSecret(JSONEncoder().encode(updated),key:"playlists")
            playlists=updated
            await vault.removeCache(name:"lineup:"+playlist.id);await vault.removeCache(name:"epg:"+playlist.id)
            if activeID==playlist.id {
                lineupTask?.cancel();epgTask?.cancel();warmTask?.cancel();sourceGeneration += 1
                tuneTask?.cancel();player.stop();current=nil;previous=nil;channels=[];visibleChannels=[];groups=[];guide=[:];activeID="";loading=false
                if let first=updated.first { activate(first) }
            }
        } catch { notice=SpareError.storage.errorDescription }
    }
    func loadSports(force:Bool=false) {
        guard !demo else { return }
        sportsTask?.cancel();sportsGeneration += 1
        let generation=sportsGeneration,date=sportsDate,leagues=enabledLeagues
        sportsLoading=true;sportsError=nil
        sportsTask=Task {
            let result=await sportsService.load(date:date,leagues:leagues,force:force)
            guard !Task.isCancelled,generation==sportsGeneration else { return }
            games=result.games;sportsLoading=false
            if !result.failed.isEmpty { sportsError="Some scores could not refresh: "+result.failed.joined(separator:", ")+". Saved games remain available." }
        }
    }
    var displayedGames:[Game] {
        games.filter { game in
            (sportsLeague=="All leagues" || sportsLeague=="My teams" && (followedTeams.contains(game.home.id)||followedTeams.contains(game.away.id)) || game.league==sportsLeague)
            && (!sportsLiveOnly || game.live)
        }
    }
    func setLeague(_ id:String,enabled:Bool) {
        if enabled { enabledLeagues.insert(id) } else { enabledLeagues.remove(id) }
        prefs.set(Array(enabledLeagues),forKey:"leagues");loadSports()
    }
    func follow(_ team:Team) {
        if !followedTeams.insert(team.id).inserted { followedTeams.remove(team.id) }
        prefs.set(Array(followedTeams),forKey:"teams")
    }
    func savePreferences() { prefs.set(hideScores,forKey:"hideScores");prefs.set(lightAppearance,forKey:"lightAppearance") }
    func findStreams(_ game:Game,autoplay:Bool=false) {
        selectedGame=game;matching=true;showStreams=true;streamResults=[]
        matchTask?.cancel()
        let channels=channels,guide=guide
        matchTask=Task {
            let result=await Task.detached(priority:.userInitiated) { SportsMatcher.find(game,channels:channels,guide:guide) }.value
            guard !Task.isCancelled else { return }
            streamResults=result;matching=false
            if autoplay,let first=result.first { playMatch(first) }
        }
    }
    func playMatch(_ result:StreamMatch) {
        let alternatives=streamResults.filter { $0.id != result.id }.prefix(3).map(\.channel)
        play(result.channel,alternates:alternatives);showStreams=false;destination = .guide
    }
    func cancelMatch() { matchTask?.cancel();matching=false }
    private func loadDemo() async {
        let s=Playlist(id:"demo",name:"Preview playlist",kind:"m3u",address:"http://127.0.0.1:8767/playlist.m3u",allowHTTP:true)
        playlists=[s];activeID=s.id
        if let result=try? await PlaylistService.load(s) {
            await apply(result,source:s,generation:sourceGeneration)
            if !result.epgURL.isEmpty { guide=(try? await GuideService.load(result.epgURL,allowHTTP:true)) ?? [:] }
            if let first=result.channels.first { play(first) }
        }
        let mariners=Team(id:"MLB:12",name:"Seattle Mariners",nickname:"Mariners",abbreviation:"SEA",
            logo:"https://a.espncdn.com/i/teamlogos/mlb/500/sea.png")
        let yankees=Team(id:"MLB:10",name:"New York Yankees",nickname:"Yankees",abbreviation:"NYY",
            logo:"https://a.espncdn.com/i/teamlogos/mlb/500/nyy.png")
        games=[Game(id:"demo-game",league:"MLB",name:"Seattle Mariners at New York Yankees",start:Date().addingTimeInterval(-1800),
            state:"in",status:"Top 5th",home:yankees,away:mariners,homeScore:"2",awayScore:"3",networks:["ESPN"])]
    }
}
