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
  runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
  testImplementation(kotlin("test"))
}

application {
  mainClass.set("cn.varsa.egg.MainKt")
}

val mcpStartScript = layout.buildDirectory.file("mcp/egg-mcp")

tasks.register("mcpStartScripts") {
  notCompatibleWithConfigurationCache("Generates a local absolute-classpath launcher script.")
  dependsOn(tasks.named("jar"))
  inputs.files(tasks.named<Jar>("jar"), configurations.runtimeClasspath)
  outputs.file(mcpStartScript)
  doLast {
    val classpath = (files(tasks.named<Jar>("jar")) + configurations.runtimeClasspath.get())
      .joinToString(":") { it.absolutePath }
    val script = mcpStartScript.get().asFile
    script.parentFile.mkdirs()
    script.writeText(
      """#!/usr/bin/env sh
exec java -cp '$classpath' cn.varsa.egg.EggMcpServerKt "$@"
"""
    )
    script.setExecutable(true)
  }
}

tasks.test {
  useJUnitPlatform()
}

kotlin {
  jvmToolchain(21)
}
