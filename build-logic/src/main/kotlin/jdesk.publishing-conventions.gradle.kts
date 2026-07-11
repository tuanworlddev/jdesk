// Shared publishing configuration: POM metadata, a configurable target repository, and
// optional signing. Consumed by jdesk.library-conventions and the BOM. Credentials are
// supplied at publish time (never checked in):
//
//   ./gradlew publish \
//     -PjdeskPublishUrl=https://maven.pkg.github.com/tuanworlddev/jdesk \
//     -PjdeskPublishUser=<user> -PjdeskPublishToken=<token>
//
// Signing is enabled when the in-memory PGP key is provided (spec 12.5):
//   -PsigningKey="$(cat key.asc)" -PsigningPassword=<pw>
//
// Without a target URL, `publishToMavenLocal` still works for local verification.
plugins {
    `maven-publish`
    signing
}

fun prop(name: String): String? =
    (project.findProperty(name) as String?) ?: System.getenv(name)

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("${project.group}:${project.name}")
            description.set(project.description ?: "JDesk framework module")
            url.set("https://github.com/tuanworlddev/jdesk")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("jdesk")
                    name.set("JDesk contributors")
                }
            }
            scm {
                url.set("https://github.com/tuanworlddev/jdesk")
                connection.set("scm:git:https://github.com/tuanworlddev/jdesk.git")
                developerConnection.set("scm:git:ssh://git@github.com/tuanworlddev/jdesk.git")
            }
        }
    }
    repositories {
        val publishUrl = prop("jdeskPublishUrl")
        if (publishUrl != null) {
            maven {
                name = "jdeskTarget"
                url = uri(publishUrl)
                credentials {
                    username = prop("jdeskPublishUser")
                    password = prop("jdeskPublishToken")
                }
            }
        }
    }
}

signing {
    val signingKey = prop("signingKey")
    val signingPassword = prop("signingPassword")
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
