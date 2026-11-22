import java.util.Properties

// Compile-only stand-ins for YNAB Classic classes the extension implements or uses.
// Never packaged: the real classes come from the patched app.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// Android framework classes for the stubs' signatures (compile only).
val androidSdk: String = Properties().also { p ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
}.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: error("Android SDK not found (local.properties sdk.dir or ANDROID_HOME)")

dependencies {
    compileOnly(files("$androidSdk/platforms/android-36/android.jar"))
}
