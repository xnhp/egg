plugins {
  kotlin("jvm") version "2.2.0"
  application
}

group = "cn.varsa"
version = "0.1.0-SNAPSHOT"

repositories {
  mavenLocal()
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation("cn.varsa:cli-core:0.1.0-SNAPSHOT")
  implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
  testImplementation(kotlin("test"))
}

application {
  mainClass.set("cn.varsa.egg.MainKt")
}

tasks.test {
  useJUnitPlatform()
}

kotlin {
  jvmToolchain(17)
}
