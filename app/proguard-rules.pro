# What R8 cannot see.
#
# The core is reached through JNA: the generated layer declares an interface
# whose methods JNA binds to symbols in the native library at run time, and
# hands Rust structures whose fields JNA reads by reflection. Nothing in the
# Kotlin sources refers to those members directly, so to the reachability
# analysis they are all unused — and a member R8 removes or renames fails at
# the first call into the core, not at build time.
#
# What holds them is tools/check-apk.sh, which reads the built APK back and
# fails when what these rules name is not in it. The instrumented tests cannot:
# they run against debug, and running them against the shrunk build is not
# something AGP supports on its own — the test APK carries no copy of what the
# application already ships, so every class R8 drops from the application takes
# the runner down with it (androidx.tracing.Trace, then kotlin.LazyKt, and so
# on). Closing that would mean a plugin that infers keep rules from the
# compiled tests; see ADR-0016 for why the check reads the APK instead.

# The four lines JNA's own FAQ gives for a project that shrinks.
-dontwarn java.awt.*
-keep class com.sun.jna.* { *; }
-keep class * extends com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# Those cover what extends a JNA class. The generated layer also implements
# two of its interfaces: the library binding, whose methods are the native
# symbols, and the callbacks Rust calls back through. Neither is reached from
# Kotlin at all.
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }

# And the generated package itself, whose enums, records and status structures
# cross the boundary by layout and by name. UniFFI publishes no rules of its
# own, so this keeps the layer whole rather than guessing which of it JNA
# touches; it is a few hundred kilobytes of the dex, against the megabytes the
# shrinking is for.
-keep class uniffi.markdown_org_ffi.** { *; }
