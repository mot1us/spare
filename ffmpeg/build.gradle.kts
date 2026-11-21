// Spare's DTS and Dolby TrueHD decoder: AndroidX Media3 1.11.1's decoder_ffmpeg (Apache-2.0, audio classes only, from
// https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg) over FFmpeg n6.0.1 (LGPL-2.1-or-later) built
// by scripts/build-ffmpeg.sh with only those decoders.
plugins { id("com.android.library") }
android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.txt")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
        // 16 KB page alignment, which Android 15+ devices and Google Play require of native libraries.
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Without the FFmpeg build the module carries no native library and FfmpegLibrary.isAvailable() is false;
    // :app refuses to build a release that way.
    if (file("src/main/jni/ffmpeg/android-libs/.spare-build").isFile) {
        externalNativeBuild { cmake { path = file("src/main/jni/CMakeLists.txt"); version = "3.22.1" } }
    }
}
dependencyLocking { lockAllConfigurations() }
dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-decoder:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.annotation:annotation:1.8.1")
}
