import SwiftUI
import SpareCore

struct SportsPage:View {
    @EnvironmentObject var store:AppStore
    @State private var focusedGame:Game?
    @State private var showCalendar=false
    private var hero:Game? {
        if let focusedGame,store.displayedGames.contains(where:{$0.id==focusedGame.id}) { return focusedGame }
        return store.displayedGames.first { $0.live && (store.followedTeams.contains($0.home.id)||store.followedTeams.contains($0.away.id)) }
            ?? store.displayedGames.first
    }
    var body:some View {
        VStack(alignment:.leading,spacing:0) {
            HStack(spacing:10) {
                Button("Today") { store.sportsDate=Date();store.sportsLiveOnly=false;store.loadSports() }
                    .buttonStyle(.borderedProminent).tint(!store.sportsLiveOnly ? .accentColor:.gray)
                Button { store.sportsLiveOnly.toggle();if store.sportsLiveOnly { store.sportsDate=Date();store.loadSports() } } label: {
                    Label("Live",systemImage:"dot.radiowaves.left.and.right")
                }.tint(store.sportsLiveOnly ? .green:.secondary)
                Button { showCalendar.toggle() } label: { Label("Calendar",systemImage:"calendar") }
                    .popover(isPresented:$showCalendar) {
                        VStack(alignment:.leading,spacing:18) {
                            Text("Choose a match day").font(.headline)
                            DatePicker("Date",selection:$store.sportsDate,displayedComponents:.date)
                            HStack {
                                Button("Yesterday") { store.sportsDate=Calendar.current.date(byAdding:.day,value:-1,to:Date())! }
                                Button("Tomorrow") { store.sportsDate=Calendar.current.date(byAdding:.day,value:1,to:Date())! }
                            }
                            Button("Show Games") { store.sportsLiveOnly=false;store.loadSports();showCalendar=false }
                                .buttonStyle(.borderedProminent)
                        }.padding(24).frame(width:320)
                    }
                Text(store.sportsDate,format:.dateTime.weekday(.wide).month(.abbreviated).day())
                    .font(.callout).foregroundStyle(.secondary).padding(.leading,8)
                Spacer()
                if store.sportsLoading { ProgressView().controlSize(.small).help("Refreshing fixtures and scores") }
                Button { store.showSettings=true } label: { Image(systemName:"slider.horizontal.3") }.help("Sports settings")
            }.padding(24)
            if let error=store.sportsError {
                Label(error,systemImage:"wifi.exclamationmark").font(.caption).foregroundStyle(.secondary).padding(.horizontal,24).padding(.bottom,12)
            }
            ScrollView {
                VStack(alignment:.leading,spacing:24) {
                    if let hero {
                        heroView(hero).frame(height:300)
                    } else if store.sportsLoading {
                        RoundedRectangle(cornerRadius:16).fill(.quaternary).frame(height:300)
                            .overlay { VStack(spacing:12) { ProgressView();Text("Loading fixtures…").font(.callout).foregroundStyle(.secondary) } }
                    }
                    HStack {
                        Text(store.sportsLiveOnly ? "Live now":store.sportsLeague).font(.title3.weight(.semibold))
                        Text("\(store.displayedGames.count) games").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                    }
                    if store.displayedGames.isEmpty && !store.sportsLoading {
                        VStack(spacing:14) {
                            Image(systemName:"sportscourt").font(.system(size:38,weight:.ultraLight)).foregroundStyle(.secondary)
                            Text(store.sportsLeague=="My teams" ? "No games for your teams on this day.":"No games scheduled in this view.").font(.headline)
                            Text(store.sportsLeague=="My teams" ? "Follow a team from a game card, or choose another date." : "Choose another league or open the calendar to see another day.")
                                .foregroundStyle(.secondary)
                            if store.sportsLiveOnly { Button("Show all games today") { store.sportsLiveOnly=false } }
                        }.frame(maxWidth:.infinity).padding(.vertical,70)
                    }
                    LazyVGrid(columns:[GridItem(.adaptive(minimum:255,maximum:380),spacing:16)],spacing:16) {
                        ForEach(store.displayedGames) { game in
                            GameCard(game:game).onHover { if $0 { focusedGame=game } }
                        }
                    }
                    Text("Scores and schedules: ESPN · Streams come from your playlist. Network matches may carry a different event.")
                        .font(.caption2).foregroundStyle(.tertiary).padding(.top,8)
                }.padding(.horizontal,24).padding(.bottom,30)
            }
        }
        .task { if !store.demo { store.loadSports() } }
    }
    private func heroView(_ game:Game)->some View {
        ZStack(alignment:.leading) {
            LinearGradient(colors:[Color.accentColor.opacity(0.22),Color.primary.opacity(0.025)],startPoint:.topLeading,endPoint:.bottomTrailing)
            HStack(spacing:28) {
                VStack(alignment:.leading,spacing:14) {
                    HStack(spacing:10) {
                        Text(game.league).font(.system(size:11,weight:.bold)).tracking(1.5)
                        StatusBadge(game:game)
                    }
                    Text("\(game.away.name)\n\(game.home.name)").font(.system(size:30,weight:.semibold)).lineLimit(4).minimumScaleFactor(0.75)
                    Text(game.start,format:.dateTime.weekday(.wide).month(.abbreviated).day().hour().minute()).font(.callout).foregroundStyle(.secondary)
                    if game.live {
                        HStack {
                            Button("Watch Best") { store.findStreams(game,autoplay:true) }.buttonStyle(.borderedProminent)
                            Button("Find Streams") { store.findStreams(game) }
                        }
                    } else {
                        Text(game.completed ? "Final · \(store.hideScores ? "scores hidden":game.awayScore+" – "+game.homeScore)" : "Upcoming · "+game.networks.joined(separator:", "))
                            .font(.callout).foregroundStyle(.secondary)
                    }
                }.frame(maxWidth:.infinity,alignment:.leading)
                HStack(spacing:10) {
                    Artwork(url:game.away.logo).frame(width:112,height:112)
                    Text("vs").font(.title3).foregroundStyle(.tertiary)
                    Artwork(url:game.home.logo).frame(width:112,height:112)
                }.padding(.trailing,15)
            }.padding(30)
        }.clipShape(RoundedRectangle(cornerRadius:16))
            .overlay(RoundedRectangle(cornerRadius:16).stroke(Color.primary.opacity(0.06)))
    }
}

private struct GameCard:View {
    @EnvironmentObject var store:AppStore
    let game:Game
    var body:some View {
        Button { if game.live { store.findStreams(game) } } label: {
            VStack(alignment:.leading,spacing:18) {
                HStack { Text(game.league).font(.system(size:10,weight:.semibold)).foregroundStyle(.secondary);Spacer();StatusBadge(game:game) }
                team(game.away,score:game.awayScore)
                team(game.home,score:game.homeScore)
                Divider().opacity(0.5)
                HStack {
                    Text(game.live ? game.status : game.start.formatted(date:.abbreviated,time:.shortened)).lineLimit(1)
                    Spacer(minLength:3)
                    if game.live { Image(systemName:"play.fill") }
                }.font(.caption).foregroundStyle(.secondary)
            }.padding(18).frame(maxWidth:.infinity,alignment:.leading)
                .background(Color.primary.opacity(0.035),in:RoundedRectangle(cornerRadius:12))
                .overlay(RoundedRectangle(cornerRadius:12).stroke(Color.primary.opacity(0.07)))
                .contentShape(RoundedRectangle(cornerRadius:12))
        }.buttonStyle(.plain)
            .contextMenu {
                Button(store.followedTeams.contains(game.away.id) ? "Unfollow \(game.away.name)":"Follow \(game.away.name)") { store.follow(game.away) }
                Button(store.followedTeams.contains(game.home.id) ? "Unfollow \(game.home.name)":"Follow \(game.home.name)") { store.follow(game.home) }
                if game.live { Button("Watch Best") { store.findStreams(game,autoplay:true) } }
            }
    }
    private func team(_ team:Team,score:String)->some View {
        HStack(spacing:11) {
            Artwork(url:team.logo).frame(width:34,height:34)
            Text(team.name).font(.system(size:13,weight:.medium)).lineLimit(1)
            if store.followedTeams.contains(team.id) { Image(systemName:"heart.fill").font(.system(size:9)).foregroundStyle(.pink) }
            Spacer(minLength:0)
            if !store.hideScores && (game.live || game.completed) { Text(score).font(.system(size:20,weight:.semibold,design:.rounded)).monospacedDigit() }
        }
    }
}
private struct StatusBadge:View {
    let game:Game
    var body:some View {
        HStack(spacing:4) {
            if game.live { Circle().fill(.green).frame(width:5,height:5) }
            Text(game.live ? "LIVE":game.completed ? "FINAL":"UPCOMING").font(.system(size:9,weight:.bold)).tracking(0.6)
        }.foregroundStyle(game.live ? Color.green:.secondary)
            .padding(.horizontal,7).padding(.vertical,5)
            .background(game.live ? Color.green.opacity(0.10):Color.primary.opacity(0.05),in:Capsule())
    }
}

struct StreamResultsView:View {
    @EnvironmentObject var store:AppStore
    @Environment(\.dismiss) var dismiss
    var body:some View {
        VStack(alignment:.leading,spacing:18) {
            HStack {
                VStack(alignment:.leading,spacing:6) {
                    Text("Find a stream").font(.title2.weight(.semibold))
                    Text(store.selectedGame?.name ?? "").foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            if store.matching {
                VStack(spacing:14) { ProgressView();Text("Matching your playlist and guide…").foregroundStyle(.secondary) }
                    .frame(maxWidth:.infinity,maxHeight:.infinity)
            } else if store.streamResults.isEmpty {
                VStack(spacing:14) {
                    Image(systemName:"magnifyingglass").font(.largeTitle).foregroundStyle(.secondary)
                    Text("No matching channels yet").font(.headline)
                    Text("Check that your playlist includes this league. Search uses channel titles, current guide listings, and broadcast networks.")
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                }.padding(30).frame(maxWidth:.infinity,maxHeight:.infinity)
            } else {
                Text("Ordered by game coverage, then resolution. Unknown FPS remains playable. Only the channel you choose is opened.")
                    .font(.caption).foregroundStyle(.secondary)
                List(store.streamResults) { match in
                    Button { store.playMatch(match) } label: {
                        HStack(spacing:14) {
                            Artwork(url:match.channel.logo).frame(width:32,height:32)
                            VStack(alignment:.leading,spacing:5) {
                                Text(match.channel.name).font(.body.weight(.medium))
                                Text(match.reason).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            VStack(alignment:.trailing,spacing:5) {
                                Text(match.channel.quality.isEmpty ? "Quality unknown":match.channel.quality).font(.caption.weight(.semibold))
                                if match.channel.fps>0 { Text(String(format:"%.2f fps",match.channel.fps)).font(.caption).foregroundStyle(.secondary) }
                            }
                            Image(systemName:"play.circle").font(.title2).foregroundStyle(Color.accentColor)
                        }.padding(.vertical,7).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }.listStyle(.inset)
            }
        }.padding(24).frame(width:700,height:570)
    }
}
