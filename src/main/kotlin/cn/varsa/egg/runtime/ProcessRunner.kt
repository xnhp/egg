package cn.varsa.egg.runtime

import cn.varsa.cli.core.CliException
import java.nio.file.Path

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

interface ProcessRunner {
  fun run(workingDir: Path, command: List<String>): ProcessResult
}

class SystemProcessRunner : ProcessRunner {
  override fun run(workingDir: Path, command: List<String>): ProcessResult {
    val process = ProcessBuilder(command)
      .directory(workingDir.toFile())
      .start()

    val stdout = process.inputStream.bufferedReader().readText().trimEnd()
    val stderr = process.errorStream.bufferedReader().readText().trimEnd()
    val exitCode = process.waitFor()

    return ProcessResult(
      exitCode = exitCode,
      stdout = stdout,
      stderr = stderr
    )
  }
}

fun ProcessResult.requireSuccess(context: String): ProcessResult {
  if (exitCode == 0) return this
  val details = listOf(stdout, stderr)
    .filter { it.isNotBlank() }
    .joinToString("\n")
  val message = if (details.isBlank()) context else "$context\n$details"
  throw CliException(message, exitCode = exitCode)
}

fun ProcessRunner.runCaptureOrThrow(workingDir: Path, command: List<String>): String {
  val result = run(workingDir, command)
    .requireSuccess("Command failed: ${command.joinToString(" ")}")
  return result.stdout
}

fun ProcessRunner.runCaptureOrNull(workingDir: Path, command: List<String>): String? {
  val result = run(workingDir, command)
  if (result.exitCode != 0) return null
  return result.stdout
}
