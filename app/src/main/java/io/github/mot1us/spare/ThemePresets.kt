package io.github.mot1us.spare

/** Named looks shared by Theme settings and the welcome screens. Applying one resets the finer theme controls. */
object ThemePresets {
    data class Preset(val name: String, val palette: String, val style: String, val font: String, val description: String)
    val all = listOf(
        Preset("Classic", "Monochrome", "Flat", "Default", "Black and white · square edges"),
        Preset("Glass", "Monochrome", "Glass", "Default", "Soft translucent panels"),
        Preset("Midnight", "Ocean", "Cinema", "Compact", "Deep blue · compact lettering"),
        Preset("Arena", "Mint", "Broadcast", "Condensed bold", "Clean sports broadcast"),
        Preset("Warm", "Amber", "Soft", "Serif", "Warm colors · rounded panels"),
        Preset("Nostalgia", "Phosphor", "Nostalgia", "Monospace", "Retro green · pixel-inspired guide"),
        Preset("Neon Night", "Neon", "Glass", "Default", "Electric cyan on deep navy glass"),
        Preset("Synthwave", "Synthwave", "Rounded", "Default", "Hot pink on violet · rounded"),
        Preset("Stadium", "Crimson", "Broadcast", "Condensed bold", "Red sports-broadcast graphics"),
        Preset("Gilded", "Gold", "Cinema", "Serif", "Gold accents · cinematic panels"),
        Preset("Aurora", "Aurora", "Soft", "Default", "Glowing teal on midnight blue"),
        Preset("Ember", "Tangerine", "Flat", "Condensed bold", "Bold orange · flat edges"),
        Preset("Emerald", "Emerald", "Outline", "Default", "Vivid green outlines"),
        Preset("Ultraviolet", "Ultraviolet", "Glass", "Compact", "Purple glass · compact lettering"),
    )

    fun apply(store: SecureStore, preset: Preset) {
        store.theme = preset.palette; store.themeStyle = preset.style; store.font = preset.font
        store.customAccent = 0; store.customBackground = -1; store.cornerRadius = -1; store.borderWidth = -1
        store.guideTextScale = 100; store.guideRowHeight = 32; store.guideRowGap = 3; store.guideOpacity = SecureStore.DEFAULT_GUIDE_OPACITY
    }

    fun applied(store: SecureStore, preset: Preset) = store.theme == preset.palette && store.themeStyle == preset.style && store.font == preset.font &&
        store.customAccent == 0 && store.customBackground == -1 && store.cornerRadius == -1 && store.borderWidth == -1 &&
        store.guideRowHeight == 32 && store.guideRowGap == 3
}
