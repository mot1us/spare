# Third-party notices

Spare is MIT-licensed. Its AndroidX, Kotlin, Guava, JSpecify, and JetBrains annotation dependencies are Apache-2.0 components; their notices and license text are bundled under `app/src/main/assets/licenses/` and accessible in Settings → Open-source licenses. Exact production and test versions are locked in `app/gradle.lockfile`.

The test-only video `app/src/androidTest/assets/sample.mp4` comes from AndroidX Media3 tag 1.11.1:
https://github.com/androidx/media/blob/1.11.1/libraries/test_data/src/test/assets/media/mp4/sample.mp4

Its SHA-256 is `6aecd03e411743feec6e9d2bb0209ada82f176ad45a4a44cbd2df0ee8290c8f6`. The Media3 Apache-2.0 license is included in the bundled license directory. The fixture is never included in the production APK, and the app ships no IPTV channels.

Test tooling also uses JUnit 4 (EPL-1.0), Hamcrest (BSD-3-Clause), kXML (BSD-style), and JSON-java (public domain). These are development/test dependencies and are not redistributed in production APKs.

The test-only `no-aud.ts` is Media3 1.11.1 `sample_h264_no_access_unit_delimiters.ts`, under the same Apache-2.0 license: https://github.com/androidx/media/blob/1.11.1/libraries/test_data/src/test/assets/media/ts/sample_h264_no_access_unit_delimiters.ts

SHA-256: `098bcf016ac3be26ff355860da62fbfd6b5322130394e2737f7af9237fbd2342`. It is excluded from production APKs.
