plugins {
    id("java")
}

group = "com.stolengalaxy"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("org.web3j:core:6.1.0")
    implementation("com.google.genai:google-genai:1.80.0")
    implementation("com.google.code.gson:gson:2.15.0")
}

tasks.test {
    useJUnitPlatform()
}
