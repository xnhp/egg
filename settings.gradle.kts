rootProject.name = "egg"

val cliCorePath = providers.gradleProperty("cliCorePath").orNull
val cliCoreBuild = listOfNotNull(
  cliCorePath?.let { file(it) },
  file("../cli-core")
).firstOrNull { it.exists() }

if (cliCoreBuild != null) {
  includeBuild(cliCoreBuild)
}
