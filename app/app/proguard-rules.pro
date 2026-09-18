# R8 rules for the kiosk app - see build.gradle.kts's release buildType
# and docs/performance-notes.md's "Enable R8/code shrinking" section.
#
# Deliberately empty beyond what's below - broad `-keep class ** { *; }`-
# style rules erase most of R8's benefit, and none of this app's own code
# is touched via reflection. Media3 and
# NanoHTTPD ship their own consumer-proguard-rules in their AARs/jar
# manifests, applied automatically - no manual keep rules needed for them
# here unless a real release-build crash proves otherwise.
