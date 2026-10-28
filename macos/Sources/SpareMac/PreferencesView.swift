import SwiftUI
import SpareCore

struct PreferencesView:View {
    @EnvironmentObject var store:AppStore
    @Environment(\.dismiss) var dismiss
    @State private var section="Sports"
    var body:some View {
        HStack(spacing:0) {
            VStack(alignment:.leading,spacing:12) {
                Text("Settings").font(.title2.weight(.semibold)).padding(.bottom,14)
                ForEach(["Sports","Appearance","Playback","About"],id:\.self) { value in
                    Button { section=value } label: {
                        Text(value).frame(maxWidth:.infinity,alignment:.leading).padding(10)
                            .background(section==value ? Color.accentColor.opacity(0.15):.clear,in:RoundedRectangle(cornerRadius:8))
                    }.buttonStyle(.plain)
                }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(22).frame(width:190).background(.ultraThinMaterial)
            Divider()
            ScrollView {
                VStack(alignment:.leading,spacing:22) {
                    Text(section).font(.title2.weight(.semibold))
                    if section=="Sports" {
                        Text("Choose the leagues you want to see. Right-click a game card to follow either team.")
                            .font(.callout).foregroundStyle(.secondary)
                        ForEach(League.all) { league in
                            Toggle(league.name,isOn:Binding(get:{store.enabledLeagues.contains(league.id)},
                                set:{store.setLeague(league.id,enabled:$0)}))
                        }
                        Divider()
                        Toggle("Hide scores",isOn:$store.hideScores).onChange(of:store.hideScores) { _ in store.savePreferences() }
                        Text("Applies to cards and the featured game, including final scores.").font(.caption).foregroundStyle(.secondary)
                        if !store.followedTeams.isEmpty {
                            let teams=Array(Set(store.games.flatMap { [$0.home,$0.away] })).filter { store.followedTeams.contains($0.id) }.sorted { $0.name<$1.name }
                            Text("My teams").font(.headline)
                            ForEach(teams) { team in
                                HStack { Artwork(url:team.logo).frame(width:25,height:25);Text(team.name);Spacer();Button("Unfollow") { store.follow(team) } }
                            }
                        }
                    } else if section=="Appearance" {
                        Toggle("Light appearance",isOn:$store.lightAppearance).onChange(of:store.lightAppearance) { _ in store.savePreferences() }
                        Text("Spare follows your Mac’s text rendering and uses a compact guide designed for a mouse and keyboard.")
                            .foregroundStyle(.secondary)
                    } else if section=="Playback" {
                        Text("VLC playback engine").font(.headline)
                        Text("Hardware decoding when supported, a 2.5-second network buffer, and bounded automatic reconnects. Sports can fall back to another matched stream if its first choice cannot start.")
                            .foregroundStyle(.secondary)
                        HStack { Image(systemName:"speaker.wave.2");Slider(value:Binding(get:{store.player.volume},set:{store.player.volume=$0}),in:0...100);Text("\(Int(store.player.volume))%").monospacedDigit().frame(width:45) }
                        Text("Audio and subtitle tracks are available from the captions menu on the video. FPS is learned from active playback; browsing Sports does not probe additional streams.")
                            .font(.callout).foregroundStyle(.secondary)
                        Divider()
                        Text("Keyboard controls").font(.headline)
                        Text("⌘1  Live TV\n⌘2  Sports\n⌘←  Previous channel\n⇧⌘P  Play / pause\n⇧⌘R  Reconnect\n⌃⌘F  Full screen\n⌘O  Playlists")
                            .font(.system(.callout,design:.monospaced)).lineSpacing(8)
                    } else {
                        Text("spare tv").font(.system(size:34,weight:.semibold,design:.rounded))
                        Text("macOS preview · \(Bundle.main.object(forInfoDictionaryKey:"CFBundleShortVersionString") as? String ?? "development")")
                        Text("A minimal, free, open-source IPTV player. Bring your own playlist. Credentials stay in this Mac’s Keychain; lineup and guide caches are encrypted.")
                            .foregroundStyle(.secondary)
                        Link("Source, releases, and feedback",destination:URL(string:"https://github.com/mot1us/spare")!)
                        Link("VideoLAN VLC · source and licenses",destination:URL(string:"https://www.videolan.org/vlc/libvlc.html")!)
                        Text("Scores and schedules are provided by ESPN’s public endpoints and may occasionally be unavailable.").font(.caption).foregroundStyle(.secondary)
                        Text("This preview includes Live TV and Sports. The Android TV app also offers Library, catch-up, multiview, and the full theme editor.")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer(minLength:12)
                }.padding(30).frame(maxWidth:.infinity,alignment:.leading)
            }
        }.frame(width:760,height:600)
    }
}

struct PlaylistsView:View {
    @EnvironmentObject var store:AppStore
    @Environment(\.dismiss) var dismiss
    @State private var draft=Playlist()
    @State private var saving=false
    @State private var selectedID:String?
    var body:some View {
        HStack(spacing:0) {
            VStack(alignment:.leading,spacing:14) {
                Text("Playlists").font(.title2.weight(.semibold))
                List(selection:$selectedID) {
                    ForEach(store.playlists) { source in
                        HStack {
                            Text(source.name).lineLimit(1)
                            Spacer()
                            if source.id==store.activeID { Image(systemName:"checkmark").foregroundStyle(Color.accentColor) }
                        }.tag(source.id)
                    }
                }.listStyle(.sidebar)
                    .onChange(of:selectedID) { id in if let source=store.playlists.first(where:{$0.id==id}) { draft=source } }
                Button { draft=Playlist();selectedID=nil } label: { Label("Add Playlist",systemImage:"plus") }
            }.padding(20).frame(width:220).background(.ultraThinMaterial)
            Divider()
            VStack(alignment:.leading,spacing:18) {
                HStack { Text(store.playlists.contains(where:{$0.id==draft.id}) ? "Edit playlist":"Add a playlist").font(.title2.weight(.semibold));Spacer();Button("Done") { dismiss() }.keyboardShortcut(.cancelAction) }
                Text("Your provider details are saved in Keychain.").font(.callout).foregroundStyle(.secondary)
                Form {
                    TextField("Name",text:$draft.name)
                    Picker("Type",selection:$draft.kind) { Text("Xtream Codes").tag("xtream");Text("M3U URL").tag("m3u") }.pickerStyle(.segmented)
                    TextField(draft.kind=="xtream" ? "Server URL":"Playlist URL",text:$draft.address)
                        .autocorrectionDisabled()
                    if draft.kind=="xtream" {
                        TextField("Username",text:$draft.username).autocorrectionDisabled()
                        SecureField("Password",text:$draft.password)
                    }
                    TextField("XMLTV URL (optional)",text:$draft.epgURL).autocorrectionDisabled()
                    Toggle("Allow this playlist to use HTTP",isOn:$draft.allowHTTP)
                    if draft.allowHTTP {
                        Text("HTTP sends provider credentials and video without encryption. HTTPS addresses are preserved.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }.formStyle(.grouped).frame(maxHeight:.infinity)
                if let notice=store.notice { Text(notice).font(.callout).foregroundStyle(.orange).lineLimit(3) }
                HStack {
                    if store.playlists.contains(where:{$0.id==draft.id}) {
                        Button("Delete",role:.destructive) { Task { await store.deletePlaylist(draft);draft=Playlist();selectedID=nil } }
                    }
                    Spacer()
                    if saving { ProgressView().controlSize(.small) }
                    Button("Save & Open") {
                        saving=true
                        var value=draft
                        value.address=value.address.trimmingCharacters(in:.whitespacesAndNewlines)
                        value.epgURL=value.epgURL.trimmingCharacters(in:.whitespacesAndNewlines)
                        if value.name.trimmingCharacters(in:.whitespaces).isEmpty { value.name="My playlist" }
                        Task { _=await store.savePlaylist(value);saving=false }
                    }.buttonStyle(.borderedProminent).disabled(saving || draft.address.isEmpty).keyboardShortcut(.defaultAction)
                }
            }.padding(24)
        }.frame(width:800,height:590)
    }
}
