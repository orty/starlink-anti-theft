// Intentionally minimal.
//
// Plugins are declared in each module's own build file rather than being listed here with
// `apply false`. A root-level `alias(...) apply false` still forces Gradle to resolve that
// plugin's marker artifact during root configuration, which would drag the Android Gradle
// Plugin (and therefore dl.google.com) into every build — including `-PskipAndroidApp=true`
// builds of `:core` on machines with no Android SDK and no access to Google's Maven.
