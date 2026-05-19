package cn.varsa.egg

import cn.varsa.cli.core.CliMain
import cn.varsa.egg.ci.JenkinsCiApi
import cn.varsa.egg.commands.EggApp
import cn.varsa.egg.git.GitCliApi
import cn.varsa.egg.github.GhCliGitHubApi
import cn.varsa.egg.output.StdOutput
import cn.varsa.egg.runtime.SystemProcessRunner
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
  loadDotEnv()
  val processRunner = SystemProcessRunner()
  val gitHubApi = GhCliGitHubApi(processRunner)
  val gitApi = GitCliApi(processRunner)
  val ciApi = JenkinsCiApi(processRunner)
  val app = EggApp(gitHubApi = gitHubApi, gitApi = gitApi, ciApi = ciApi, output = StdOutput())
  val exitCode = CliMain.run(app.commandTree(), args)
  if (exitCode != 0) {
    System.exit(exitCode)
  }
}

private fun loadDotEnv() {
  val values = mutableMapOf<String, String>()
  dotenvCandidates().forEach { file ->
    if (!Files.isRegularFile(file)) return@forEach
    Files.readAllLines(file).forEach { line ->
      val parsed = parseDotEnvLine(line) ?: return@forEach
      values[parsed.first] = parsed.second
    }
  }

  values.forEach { (key, value) ->
    val envValue = System.getenv(key)?.trim().orEmpty()
    val propertyValue = System.getProperty(key)?.trim().orEmpty()
    if (envValue.isBlank() && propertyValue.isBlank()) {
      System.setProperty(key, value)
    }
  }
}

private fun dotenvCandidates(): List<Path> {
  val candidates = mutableListOf<Path>()
  val home = System.getProperty("user.home")
  val cwd = System.getProperty("user.dir")
  if (!home.isNullOrBlank()) {
    candidates.add(Path.of(home, ".config", "egg", ".env"))
    candidates.add(Path.of(home, ".egg", ".env"))
  }
  val appHome = System.getenv("APP_HOME")
  if (!appHome.isNullOrBlank()) {
    val appHomePath = Path.of(appHome)
    candidates.add(appHomePath.resolve(".env"))
    candidates.add(Path.of(appHome, "..", "..", "..", ".env").normalize())
  }
  if (!cwd.isNullOrBlank()) {
    candidates.add(Path.of(cwd, ".env"))
  }
  return candidates
}

private fun parseDotEnvLine(line: String): Pair<String, String>? {
  val trimmed = line.trim()
  if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

  val declaration = if (trimmed.startsWith("export ")) trimmed.removePrefix("export ").trimStart() else trimmed
  val separator = declaration.indexOf('=')
  if (separator <= 0) return null

  val key = declaration.substring(0, separator).trim()
  if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) return null

  val rawValue = declaration.substring(separator + 1).trim()
  val value = parseDotEnvValue(rawValue)
  return key to value
}

private fun parseDotEnvValue(raw: String): String {
  if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
    return raw.substring(1, raw.length - 1)
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")
      .replace("\\\"", "\"")
      .replace("\\\\", "\\")
  }

  if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') {
    return raw.substring(1, raw.length - 1)
  }

  val inlineCommentIndex = raw.indexOf(" #")
  return if (inlineCommentIndex >= 0) raw.substring(0, inlineCommentIndex).trimEnd() else raw
}
