plugins {
    kotlin("jvm") version "2.3.21"
    `java-library`
    `maven-publish`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "dev.kubister11"
version = "1.0.2"

repositories {
    mavenCentral()
}

dependencies {
    api("org.apache.fory:fory-core:1.1.0")
    api("org.apache.fory:fory-kotlin:1.1.0")

    api("org.slf4j:slf4j-api:2.0.16")

    compileOnly("io.nats:jnats:2.25.3")
    compileOnly("org.redisson:redisson:4.5.0")
    compileOnly("io.netty:netty-buffer:4.1.118.Final")

    testImplementation(kotlin("test"))
    testImplementation("io.nats:jnats:2.25.3")
    testImplementation("org.redisson:redisson:4.5.0")
    testImplementation("io.netty:netty-buffer:4.1.118.Final")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "messenger", version.toString())

    pom {
        name = "messenger"
        description = "Transport-agnostic pub/sub and request/response messaging for the JVM (Redis, NATS)."
        url = "https://github.com/Kubister11/messenger"

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("Kubister11")
                name.set("Kubister11")
                email.set("llukaszewicz.jakub@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/Kubister11/messenger")
            connection.set("scm:git:git://github.com/Kubister11/messenger.git")
        }
    }
}