group = "app.classicbudget"

val bundleProfile = if (providers.gradleProperty("productionOnly").orNull == "true") "release" else "lab"
kotlin.sourceSets.named("main") {
    kotlin.srcDir("src/$bundleProfile/kotlin")
}

patches {
    about {
        name = "Classic Budget Plus"
        description = "Patches for YNAB Classic (the YNAB 4 Android companion): usability fixes and optional modules"
        source = "https://github.com/ysosrsss/classic-budget-plus-patches"
        author = "Classic Budget contributors"
        contact = "na"
        website = "na"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
