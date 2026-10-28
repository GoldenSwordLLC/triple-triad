plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test"))
}
tasks.test {
    workingDir = rootProject.projectDir
    testLogging { showStandardStreams = true }
    System.getProperty("loopback.repeat")?.let { systemProperty("loopback.repeat", it) }
}
