// Top-level build file. Configuration shared by all modules lives here.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// JDK 17 on Windows cannot reliably read Cyrillic paths from Gradle's worker
// argument files. build.ps1 can keep outputs in an ASCII path without moving sources.
val externalBuildRoot = providers.gradleProperty("pivoskanBuildDir")
subprojects {
    if (externalBuildRoot.isPresent) {
        layout.buildDirectory.set(file(externalBuildRoot.get()).resolve(name))
    }
}
