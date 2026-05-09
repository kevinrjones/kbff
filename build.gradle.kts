import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.kotlin.dsl.invoke

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.gitVersion)
    java
    alias(libs.plugins.vanniktech.maven.publish)
}

val gitVersion: groovy.lang.Closure<String> by extra

fun getAppVersion(): String {
    val propVersion = providers.gradleProperty("appVersion").orNull
    if (propVersion != null) return propVersion

    val envVersion = providers.environmentVariable("ACS_API_APP_VERSION").orNull
    if (envVersion != null) return envVersion

    return try {
        gitVersion().replace(".dirty", "")
    } catch (_: Exception) {
        "0.0.0-dev"
    }
}

group = "com.knowledgespike"
version = getAppVersion()

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.hsts)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.nimbus.oauth2.oidc.sdk)
    implementation(libs.logback)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.ktor)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.mockk)
    testImplementation(libs.strikt.core)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}


mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    pom {
        name.set("KBFF")
        description.set("Ktor Backend-for-Frontend Library")
        url.set("https://github.com/kevinrjones/kbff")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("kevinrjones")
                name.set("Kevin Jones")
                email.set("kevin@knowledgespike.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/kevinrjones/kbff.git")
            developerConnection.set("scm:git:ssh://github.com/kevinrjones/kbff.git")
            url.set("https://github.com/kevinrjones/kbff")
        }
    }
}
