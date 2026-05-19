plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

gradlePlugin {
    plugins {
        create("steamPathPlugin") {
            id = "io.stamethyst.steam-path"
            implementationClass = "SteamPathPlugin"
        }
        create("androidAppBuildPlugin") {
            id = "io.stamethyst.android-app-build"
            implementationClass = "StsAndroidAppBuildPlugin"
        }
    }
}
