rootProject.name = "egg"

val cliCoreDir = file("/home/ben/repos/cli-core")
if (cliCoreDir.exists()) {
  includeBuild(cliCoreDir)
}
