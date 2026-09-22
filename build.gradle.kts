plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP's built-in Kotlin does not carry the Compose compiler; the version tracks that Kotlin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
