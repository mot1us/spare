# Media3 and AndroidX supply their own consumer rules.

# Settings > About Spare > Performance report names the code that blocked the screen or was busy during a playback
# hiccup. Those names must stay readable in the published build, so code is still shrunk and optimised but not renamed.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
