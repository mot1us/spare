import SwiftUI
import AppKit
import SpareCore

struct RootView:View {
    @EnvironmentObject var store:AppStore
    var body:some View {
        HStack(spacing:0) {
            if !store.fullScreen { Sidebar().frame(width:205);Divider() }
            GeometryReader { geometry in
                ZStack(alignment:.topTrailing) {
                    if store.fullScreen { Color.black }
                    else if store.destination == .guide { GuidePage(previewWidth:geometry.size.width*0.46) }
                    else { SportsPage() }
                    PlayerPane(player:store.player)
                        .frame(width:store.fullScreen ? geometry.size.width : geometry.size.width*0.46-20,
                               height:store.fullScreen ? geometry.size.height : 248)
                        .padding(store.fullScreen ? 0:16)
                        .opacity(store.fullScreen || store.destination == .guide ? 1:0)
                        .allowsHitTesting(store.fullScreen || store.destination == .guide)
                }
            }
        }
        .background(Color(nsColor:.windowBackgroundColor))
        .toolbar {
            if !store.fullScreen {
                ToolbarItem(placement:.navigation) {
                    Text(store.destination.rawValue).font(.headline)
                }
                ToolbarItemGroup(placement:.primaryAction) {
                    if store.destination == .guide {
                        HStack(spacing:7) {
                            Image(systemName:"magnifyingglass").foregroundStyle(.secondary)
                            TextField("Search channels",text:$store.query).textFieldStyle(.plain).frame(width:185)
                                .accessibilityIdentifier("channel-search")
                            if !store.query.isEmpty { Button { store.query="" } label: { Image(systemName:"xmark.circle.fill") }.buttonStyle(.plain) }
                        }.padding(7).background(.quaternary,in:RoundedRectangle(cornerRadius:7))
                    }
                    Menu {
                        ForEach(store.playlists) { source in
                            Button { store.activate(source) } label: {
                                if source.id==store.activeID { Label(source.name,systemImage:"checkmark") } else { Text(source.name) }
                            }
                        }
                        Divider()
                        Button("Manage Playlists…") { store.showPlaylists=true }
                        Button("Refresh Channels and Guide") { store.refreshPlaylist() }.disabled(store.source==nil || store.loading)
                    } label: { Label(store.source?.name ?? "Add playlist",systemImage:"list.bullet.rectangle") }
                    Button { store.showSettings=true } label: { Image(systemName:"gearshape") }.help("Settings")
                }
            }
        }
        .safeAreaInset(edge:.bottom,spacing:0) {
            if let notice=store.notice,!store.fullScreen {
                HStack {
                    Image(systemName:"info.circle")
                    Text(notice).font(.callout).lineLimit(3)
                    Spacer()
                    Button("Dismiss") { store.notice=nil }.buttonStyle(.borderless)
                }.padding(12).background(.regularMaterial)
            }
        }
        .sheet(isPresented:$store.showPlaylists) { PlaylistsView().environmentObject(store) }
        .sheet(isPresented:$store.showSettings) { PreferencesView().environmentObject(store) }
        .sheet(isPresented:$store.showStreams,onDismiss:store.cancelMatch) { StreamResultsView().environmentObject(store) }
        .onReceive(NotificationCenter.default.publisher(for:NSWindow.didEnterFullScreenNotification)) { _ in store.fullScreen=true }
        .onReceive(NotificationCenter.default.publisher(for:NSWindow.didExitFullScreenNotification)) { _ in store.fullScreen=false }
    }
}

private struct Sidebar:View {
    @EnvironmentObject var store:AppStore
    var body:some View {
        VStack(alignment:.leading,spacing:0) {
            HStack(spacing:8) {
                Text("spare").font(.system(size:29,weight:.semibold,design:.rounded)).tracking(-1)
                Image(systemName:"tv").font(.system(size:20,weight:.light))
            }.padding(.horizontal,20).padding(.top,20).padding(.bottom,24)
            VStack(spacing:5) {
                navigation(.guide,icon:"play.rectangle")
                navigation(.sports,icon:"trophy")
            }.padding(.horizontal,10)
            Divider().padding(.vertical,14).padding(.horizontal,16)
            if store.destination == .guide {
                Text("CHANNELS").font(.system(size:10,weight:.semibold)).foregroundStyle(.secondary).padding(.horizontal,20).padding(.bottom,8)
                ScrollView {
                    LazyVStack(alignment:.leading,spacing:3) {
                        category("All channels",icon:"rectangle.grid.1x2")
                        category("Favorites",icon:"star")
                        ForEach(store.groups,id:\.self) { value in category(value,icon:nil) }
                    }.padding(.horizontal,10)
                }
            } else {
                Text("LEAGUES").font(.system(size:10,weight:.semibold)).foregroundStyle(.secondary).padding(.horizontal,20).padding(.bottom,8)
                ScrollView {
                    VStack(spacing:3) {
                        league("All leagues",name:"All leagues",icon:"square.grid.2x2")
                        league("My teams",name:"My teams",icon:"heart")
                        ForEach(League.all.filter { store.enabledLeagues.contains($0.id) }) { value in league(value.id,name:value.name,icon:nil) }
                    }.padding(.horizontal,10)
                }
            }
            Spacer(minLength:12)
            Button { store.showSettings=true } label: { Label("Settings",systemImage:"gearshape").frame(maxWidth:.infinity,alignment:.leading).padding(10) }
                .buttonStyle(.plain).padding(10)
        }.background(.ultraThinMaterial)
    }
    private func navigation(_ destination:Destination,icon:String)->some View {
        Button { store.destination=destination } label: {
            Label(destination.rawValue,systemImage:icon).font(.system(size:13,weight:.medium))
                .frame(maxWidth:.infinity,alignment:.leading).padding(.horizontal,12).padding(.vertical,10)
                .background(store.destination==destination ? Color.accentColor.opacity(0.19):.clear,in:RoundedRectangle(cornerRadius:8))
        }.buttonStyle(.plain)
    }
    private func category(_ value:String,icon:String?)->some View {
        Button { store.selectCategory(value) } label: {
            HStack(spacing:9) {
                if let icon { Image(systemName:icon).frame(width:15) }
                Text(value).lineLimit(1)
                Spacer(minLength:0)
                if store.category==value { Capsule().fill(Color.accentColor).frame(width:3,height:14) }
            }.font(.system(size:12)).padding(.horizontal,12).padding(.vertical,9)
                .background(store.category==value ? Color.primary.opacity(0.08):.clear,in:RoundedRectangle(cornerRadius:7))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).help(value)
    }
    private func league(_ id:String,name:String,icon:String?)->some View {
        Button { store.sportsLeague=id } label: {
            HStack(spacing:9) { if let icon { Image(systemName:icon) };Text(name);Spacer(minLength:0) }
                .font(.system(size:12)).padding(.horizontal,12).padding(.vertical,10)
                .background(store.sportsLeague==id ? Color.accentColor.opacity(0.16):.clear,in:RoundedRectangle(cornerRadius:7))
        }.buttonStyle(.plain)
    }
}

struct PlayerPane:View {
    @EnvironmentObject var store:AppStore
    @ObservedObject var player:Player
    @State private var controls=true
    var body:some View {
        ZStack {
            Color.black
            VideoSurface(player:player)
            if store.current==nil {
                VStack(spacing:12) {
                    Image(systemName:"play.tv").font(.system(size:34,weight:.ultraLight))
                    Text("Choose a channel").font(.callout)
                }.foregroundStyle(.white.opacity(0.5)).allowsHitTesting(false)
            }
            if player.loading {
                ProgressView().controlSize(.small).padding(12).background(.black.opacity(0.4),in:Circle())
                    .frame(maxWidth:.infinity,maxHeight:.infinity,alignment:.center).allowsHitTesting(false)
            }
            if let error=player.error {
                VStack(spacing:12) {
                    Image(systemName:"wifi.exclamationmark").font(.title2)
                    Text(error).font(.callout).multilineTextAlignment(.center)
                    Button("Reconnect") { player.retry() }
                }.padding(24).frame(maxWidth:400).background(.regularMaterial,in:RoundedRectangle(cornerRadius:12))
            }
            if controls && store.current != nil {
                VStack {
                    Spacer()
                    HStack(spacing:12) {
                        Button { player.togglePause() } label: { Image(systemName:player.paused ? "play.fill":"pause.fill") }
                        Button { store.previousChannel() } label: { Image(systemName:"backward.end") }.disabled(store.previous==nil).help("Previous channel · ⌘←")
                        if store.fullScreen {
                            VStack(alignment:.leading,spacing:3) {
                                Text(store.current?.name ?? "").font(.headline).lineLimit(1)
                                if !player.quality.isEmpty { Text(player.quality).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                        Spacer(minLength:0)
                        Menu {
                            Text("Audio")
                            ForEach(player.audioTracks) { track in Button(track.name) { player.selectTrack(track.id,subtitles:false) } }
                            Divider()
                            Text("Subtitles")
                            Button("Off") { player.selectTrack(-1,subtitles:true) }
                            ForEach(player.subtitleTracks.filter { $0.id != -1 }) { track in Button(track.name) { player.selectTrack(track.id,subtitles:true) } }
                            if player.subtitleTracks.filter({$0.id != -1}).isEmpty { Text("No subtitle tracks detected") }
                        } label: { Image(systemName:"captions.bubble") }.menuStyle(.borderlessButton).frame(width:25)
                        Button { player.volume=player.volume>0 ? 0:85 } label: { Image(systemName:player.volume>0 ? "speaker.wave.2":"speaker.slash") }
                        Button { NSApp.keyWindow?.toggleFullScreen(nil) } label: { Image(systemName:store.fullScreen ? "arrow.down.right.and.arrow.up.left":"arrow.up.left.and.arrow.down.right") }.help("Full screen · ⌃⌘F")
                    }.font(.system(size:13)).buttonStyle(.plain).padding(14)
                        .background(LinearGradient(colors:[.clear,.black.opacity(0.85)],startPoint:.top,endPoint:.bottom))
                }.foregroundStyle(.white)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius:store.fullScreen ? 0:10))
        .overlay(RoundedRectangle(cornerRadius:store.fullScreen ? 0:10).stroke(.white.opacity(0.08),lineWidth:1))
        .shadow(color:.black.opacity(store.fullScreen ? 0:0.18),radius:12,y:4)
        .onHover { controls=$0 }
        .onTapGesture(count:2) { NSApp.keyWindow?.toggleFullScreen(nil) }
        .onTapGesture { controls.toggle() }
    }
}

struct GuidePage:View {
    @EnvironmentObject var store:AppStore
    let previewWidth:CGFloat
    var body:some View {
        VStack(alignment:.leading,spacing:0) {
            HStack(alignment:.top,spacing:20) {
                VStack(alignment:.leading,spacing:12) {
                    HStack {
                        Text("NOW PLAYING").font(.system(size:10,weight:.semibold)).tracking(1.6).foregroundStyle(.secondary)
                        if store.guideLoading { ProgressView().controlSize(.mini);Text("Refreshing guide").font(.caption2).foregroundStyle(.secondary) }
                    }
                    Text(store.current?.name ?? "Your television, simply.")
                        .font(.system(size:25,weight:.semibold)).lineLimit(2)
                    if let programme=store.currentProgramme {
                        Text(programme.title).font(.system(size:16,weight:.medium)).lineLimit(2)
                        Text("\(programme.start.formatted(date:.omitted,time:.shortened)) – \(programme.end.formatted(date:.omitted,time:.shortened))")
                            .font(.caption).foregroundStyle(.secondary)
                        Text(programme.detail).font(.callout).foregroundStyle(.secondary).lineLimit(3)
                    } else {
                        Text(store.current==nil ? "Add your playlist, then choose something to watch." : "Program details will appear here when your provider supplies them.")
                            .font(.callout).foregroundStyle(.secondary).lineLimit(3)
                    }
                    Spacer(minLength:0)
                    LiveQuality(player:store.player)
                }.frame(maxWidth:.infinity,alignment:.leading)
                Color.clear.frame(width:previewWidth-20)
            }.padding(24).frame(height:280)
            Divider().padding(.horizontal,16)
            HStack {
                Text(store.category).font(.system(size:14,weight:.semibold))
                Text("\(store.visibleChannels.count.formatted()) channels").font(.caption).foregroundStyle(.secondary)
                Spacer()
                if store.loading { ProgressView().controlSize(.small);Text(store.loadingMessage).font(.caption).foregroundStyle(.secondary) }
                else { Text(Date(),style:.time).font(.caption).monospacedDigit().foregroundStyle(.secondary) }
            }.padding(.horizontal,24).padding(.vertical,14)
            if store.channels.isEmpty {
                VStack(spacing:14) {
                    if store.loading { ProgressView();Text(store.loadingMessage) }
                    else {
                        Image(systemName:"tv").font(.system(size:38,weight:.ultraLight))
                        Text("Bring your own playlist").font(.title3)
                        Text("M3U or Xtream Codes · saved securely on this Mac").foregroundStyle(.secondary)
                        Button("Add Playlist") { store.showPlaylists=true }.buttonStyle(.borderedProminent)
                    }
                }.frame(maxWidth:.infinity,maxHeight:.infinity)
            } else if store.visibleChannels.isEmpty {
                VStack(spacing:10) {
                    Image(systemName:store.category=="Favorites" ? "star":"magnifyingglass").font(.largeTitle)
                    Text(store.category=="Favorites" ? "Star a channel to keep it here." : "No channels match this search.")
                    if !store.query.isEmpty { Button("Clear search") { store.query="" } }
                }.foregroundStyle(.secondary).frame(maxWidth:.infinity,maxHeight:.infinity)
            } else {
                GeometryReader { geometry in
                    let width=max(260,geometry.size.width-270),start=Date(timeIntervalSince1970:floor(Date().timeIntervalSince1970/1800)*1800)
                    VStack(spacing:0) {
                        HStack(spacing:0) {
                            Text("CHANNEL").font(.system(size:10,weight:.medium)).frame(width:230,alignment:.leading)
                            ForEach(0..<4) { index in
                                Text(start.addingTimeInterval(Double(index)*1800),format:.dateTime.hour().minute())
                                    .font(.caption).frame(width:width/4,alignment:.leading)
                            }
                        }.foregroundStyle(.secondary).padding(.horizontal,20).padding(.bottom,8)
                        List {
                            ForEach(store.visibleChannels) { channel in
                                GuideRow(channel:channel,width:width,start:start)
                                    .listRowSeparator(.hidden).listRowInsets(EdgeInsets(top:3,leading:8,bottom:3,trailing:8))
                                    .onAppear { store.warmEPG(channel) }
                                    .onDisappear { store.warmEPG(channel,visible:false) }
                            }
                        }.listStyle(.plain).scrollContentBackground(.hidden)
                    }
                }
            }
        }
    }
}
private struct LiveQuality:View {
    @ObservedObject var player:Player
    var body:some View {
        if !player.quality.isEmpty {
            Label(player.quality,systemImage:"display").font(.caption).foregroundStyle(.secondary)
        }
    }
}
private struct GuideRow:View {
    @EnvironmentObject var store:AppStore
    let channel:Channel,width:CGFloat,start:Date
    var body:some View {
        HStack(spacing:10) {
            Button { store.play(channel) } label: {
                HStack(spacing:10) {
                    Artwork(url:channel.logo).frame(width:30,height:30)
                    VStack(alignment:.leading,spacing:4) {
                        Text(channel.name).font(.system(size:12,weight:.medium)).lineLimit(2)
                        if !channel.quality.isEmpty { Text(channel.quality).font(.system(size:9,weight:.semibold)).foregroundStyle(.secondary) }
                    }
                    Spacer(minLength:0)
                }.frame(width:180,alignment:.leading).contentShape(Rectangle())
            }.buttonStyle(.plain)
            Button { store.favorite(channel) } label: {
                Image(systemName:store.favorites.contains(channel.id) ? "star.fill":"star")
                    .foregroundStyle(store.favorites.contains(channel.id) ? Color.yellow:Color.secondary).frame(width:22,height:36)
            }.buttonStyle(.plain).help(store.favorites.contains(channel.id) ? "Remove favorite":"Add favorite")
                .accessibilityLabel(store.favorites.contains(channel.id) ? "Remove \(channel.name) from favorites":"Favorite \(channel.name)")
            Button { store.play(channel) } label: {
                ZStack(alignment:.leading) {
                    let programmes=store.guide[GuideService.key(channel),default:[]].filter { $0.end>start && $0.start<start.addingTimeInterval(7200) }.prefix(12)
                    if programmes.isEmpty {
                        Text(store.guideLoading ? "Loading guide…":"Program information unavailable").font(.caption).foregroundStyle(.tertiary).padding(.leading,12)
                    }
                    ForEach(Array(programmes.enumerated()),id:\.offset) { _,programme in
                        let x=max(0,programme.start.timeIntervalSince(start))/7200*width
                        let end=min(7200,programme.end.timeIntervalSince(start))/7200*width
                        Text(programme.title).font(.system(size:12)).lineLimit(2).padding(.horizontal,10)
                            .frame(width:max(0,end-x-4),height:48,alignment:.leading)
                            .background(Color.primary.opacity(0.045),in:RoundedRectangle(cornerRadius:5)).offset(x:x)
                    }
                }.frame(width:width,height:52,alignment:.leading).clipped().contentShape(Rectangle())
            }.buttonStyle(.plain)
        }
        .padding(.horizontal,10).padding(.vertical,5)
        .background(store.current?.id==channel.id ? Color.accentColor.opacity(0.13):Color.primary.opacity(0.025),in:RoundedRectangle(cornerRadius:8))
        .contextMenu {
            Button("Play Full Screen") { store.play(channel);NSApp.keyWindow?.toggleFullScreen(nil) }
            Button(store.favorites.contains(channel.id) ? "Remove Favorite":"Add Favorite") { store.favorite(channel) }
        }
    }
}

struct Artwork:View {
    let url:String
    @State private var image:NSImage?
    var body:some View {
        Group {
            if let image { Image(nsImage:image).resizable().scaledToFit() }
            else { Image(systemName:"tv").resizable().scaledToFit().padding(5).foregroundStyle(.tertiary) }
        }.task(id:url) {
            image=nil
            if let data=await ArtworkCache.shared.load(url),!Task.isCancelled { image=NSImage(data:data) }
        }.accessibilityHidden(true)
    }
}
private actor ArtworkCache {
    static let shared=ArtworkCache()
    private var cache=[String:Data]()
    func load(_ url:String) async -> Data? {
        if let value=cache[url] { return value }
        guard (try? HTTP.validate(url,allowHTTP:false)) != nil else { return nil }
        let folder=FileManager.default.urls(for:.cachesDirectory,in:.userDomainMask)[0].appendingPathComponent("io.github.mot1us.spare.mac/artwork")
        let file=folder.appendingPathComponent(TextMatch.stableID(url))
        if let data=try? Data(contentsOf:file),data.count<2*1024*1024 { cache[url]=data;return data }
        guard let data=try? await HTTP.get(url,limit:2*1024*1024,timeout:8) else { return nil }
        if cache.count>128 { cache.removeAll(keepingCapacity:true) }
        cache[url]=data
        try? FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
        try? data.write(to:file,options:.atomic)
        return data
    }
}
