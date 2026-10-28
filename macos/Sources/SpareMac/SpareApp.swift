import SwiftUI
import AppKit

final class AppDelegate:NSObject,NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender:NSApplication)->Bool { true }
}
@main struct SpareApp:App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var store=AppStore()
    var body:some Scene {
        WindowGroup {
            RootView().environmentObject(store)
                .frame(minWidth:1000,minHeight:680)
                .preferredColorScheme(store.lightAppearance ? .light:.dark)
        }
        .defaultSize(width:1320,height:860)
        .commands {
            CommandGroup(replacing:.newItem) {
                Button("Manage Playlists…") { store.showPlaylists=true }.keyboardShortcut("o")
            }
            CommandGroup(replacing:.appSettings) {
                Button("Settings…") { store.showSettings=true }.keyboardShortcut(",")
            }
            CommandMenu("Playback") {
                Button("Play / Pause") { store.player.togglePause() }.keyboardShortcut("p",modifiers:[.command,.shift])
                Button("Previous Channel") { store.previousChannel() }.keyboardShortcut(.leftArrow,modifiers:.command)
                Button("Reconnect") { store.player.retry() }.keyboardShortcut("r",modifiers:[.command,.shift])
                Button("Stop") { store.player.stop() }.keyboardShortcut(".",modifiers:.command)
                Button("Full Screen") { NSApp.keyWindow?.toggleFullScreen(nil) }.keyboardShortcut("f",modifiers:[.command,.control])
            }
            CommandMenu("Navigate") {
                Button("Live TV") { store.destination = .guide }.keyboardShortcut("1")
                Button("Sports") { store.destination = .sports }.keyboardShortcut("2")
            }
        }
    }
}
