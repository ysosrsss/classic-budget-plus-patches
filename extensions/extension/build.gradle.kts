extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.classicbudget.extension"
}

dependencies {
    compileOnly(project(":stubs"))
    // WebDAV/Koofr transport (bundled into the extension; needs minSdk 21 on the app).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
