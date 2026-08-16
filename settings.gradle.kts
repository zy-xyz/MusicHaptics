pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/google/")
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin/")
        }
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        maven {
            url = uri("https://dl.google.com/android/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("local-maven")
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/google/")
        }
        maven {
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        maven {
            url = uri("https://dl.google.com/android/maven2/")
        }
        mavenCentral()
    }
}
rootProject.name = "MusicHapticsX"
include(":app")
