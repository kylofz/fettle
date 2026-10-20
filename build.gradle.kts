plugins {
    kotlin("jvm") version "2.4.20" apply false
}

val releaseVersion = providers.gradleProperty("releaseVersion").get()

allprojects {
    group = "me.kylofz"
    version = releaseVersion
}
