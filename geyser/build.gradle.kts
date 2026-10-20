plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation(project(":core"))
    compileOnly("org.geysermc.geyser:api:2.12.0-SNAPSHOT")
    testImplementation("org.geysermc.geyser:api:2.12.0-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("extension.yml") {
        expand(mapOf("version" to project.version.toString()))
    }
}

tasks.jar {
    archiveBaseName.set("fettle")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    // Geyser loads one extension JAR; embed the JVM core and Kotlin runtime.
    from(configurations.runtimeClasspath.get().map { file ->
        if (file.isDirectory) file else zipTree(file)
    })
}
