# Third-party notices

Spare is MIT-licensed. Its AndroidX, Kotlin, Guava, JSpecify, and JetBrains annotation dependencies are Apache-2.0 components; their notices and license text are bundled under `app/src/main/assets/licenses/` and accessible in Settings → Open-source licenses. Exact production and test versions are locked in `app/gradle.lockfile`.

The test-only video `app/src/androidTest/assets/sample.mp4` comes from AndroidX Media3 tag 1.11.1:
https://github.com/androidx/media/blob/1.11.1/libraries/test_data/src/test/assets/media/mp4/sample.mp4

Its SHA-256 is `6aecd03e411743feec6e9d2bb0209ada82f176ad45a4a44cbd2df0ee8290c8f6`. The Media3 Apache-2.0 license is included in the bundled license directory. The fixture is never included in the production APK, and the app ships no IPTV channels.

Spare's DTS and Dolby TrueHD decoder links FFmpeg n6.0.1 (https://github.com/FFmpeg/FFmpeg/tree/n6.0.1, commit `c41ff724ede7da657762d61097e26fac296c53bf`), LGPL-2.1-or-later. `scripts/build-ffmpeg.sh` downloads that exact commit and builds only the `dca`, `truehd` and `mlp` decoders with libavutil and libswresample, without GPL or non-free components; the libraries are linked into `libffmpegJNI.so` and are not committed. The `ffmpeg` module's Java and JNI code is AndroidX Media3 1.11.1 `libraries/decoder_ffmpeg` (Apache-2.0, https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg, commit `8c6678b657ede1e7883fc164ef73ed483c7796c3`), audio classes only, with the checker-framework annotation removed from `FfmpegLibrary` and size-reducing link options added to its `CMakeLists.txt`. The LGPL-2.1 text is bundled under `app/src/main/assets/licenses/` and shown in Settings → Open-source licenses.

The test-only `dts-hd-ma.mkv` is Media3 1.11.1 `sample_with_dts_hd_ma.mkv` (Apache-2.0): https://github.com/androidx/media/blob/1.11.1/libraries/test_data/src/test/assets/media/mkv/sample_with_dts_hd_ma.mkv

SHA-256: `522b99f24c104c4273beede93f7cdffe794e7c36376149706dcf41a9ed0c7df5`. It is excluded from production APKs.

Test tooling also uses JUnit 4 (EPL-1.0), Hamcrest (BSD-3-Clause), kXML (BSD-style), JSON-java (public domain), and ZXing core (Apache-2.0, to check the QR codes Spare draws). These are development/test dependencies and are not redistributed in production APKs.

The test-only `no-aud.ts` is Media3 1.11.1 `sample_h264_no_access_unit_delimiters.ts`, under the same Apache-2.0 license: https://github.com/androidx/media/blob/1.11.1/libraries/test_data/src/test/assets/media/ts/sample_h264_no_access_unit_delimiters.ts

SHA-256: `098bcf016ac3be26ff355860da62fbfd6b5322130394e2737f7af9237fbd2342`. It is excluded from production APKs.

The test-only `stall-av.ts`, downloaded by the `prepareStreamFixture` task with a pinned checksum, is AndroidX Media3 1.11.1 `bbb_2500ms.ts`: https://github.com/androidx/media/blob/1.11.1/libraries/test_data/src/test/assets/media/ts/bbb_2500ms.ts

It contains a short excerpt of Big Buck Bunny, © 2008 Blender Foundation, licensed CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/; https://peach.blender.org/). The test creates timestamp-shifted repetitions in memory to simulate a network stall. It is excluded from production APKs. SHA-256: `454aa16b73fb477ee971540bff53948749a96117643994df1265e9f6b456b5bb`.
