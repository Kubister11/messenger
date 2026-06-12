plugins {
    kotlin("jvm") version "2.3.21"
    `java-library`
    `maven-publish`
}

group = "dev.kubister11"
version = "1.0.0"

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
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("messenger")
                description.set("Transport-agnostic pub/sub and request/response messaging for the JVM (Redis, NATS).")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
