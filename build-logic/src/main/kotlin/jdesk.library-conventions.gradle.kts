plugins {
    id("jdesk.java-conventions")
    `java-library`
    id("jdesk.publishing-conventions")
}

java {
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
