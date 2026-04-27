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
