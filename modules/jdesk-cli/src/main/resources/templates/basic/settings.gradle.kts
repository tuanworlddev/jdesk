pluginManagement {
@SOURCE_INCLUDE@    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "@PROJECT_NAME@"
@SOURCE_BUILD_INCLUDE@
