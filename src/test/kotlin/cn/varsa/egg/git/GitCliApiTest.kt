package cn.varsa.egg.git

import cn.varsa.egg.runtime.ProcessResult
import cn.varsa.egg.runtime.ProcessRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitCliApiTest {
  @Test
  fun `changed paths handles null-delimited output`() {
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("git", "rev-parse", "--show-toplevel") to ProcessResult(0, "/repo", ""),
        listOf("git", "diff", "--name-only", "-z") to ProcessResult(0, "a.txt\u0000dir/b.txt\u0000", "")
      )
    )
    val api = GitCliApi(runner)

    val output = api.changedPaths(java.nio.file.Path.of("."), staged = false, range = null)

    assertEquals("/repo/a.txt\n/repo/dir/b.txt", output)
  }

  @Test
  fun `generate commit message extracts text event payload`() {
    val runner = RecordingProcessRunner(
      fallback = { command ->
        when {
          command == listOf("git", "rev-parse", "--is-inside-work-tree") -> ProcessResult(0, "true", "")
          command == listOf("git", "diff", "--cached", "--quiet") -> ProcessResult(1, "", "")
          command == listOf("git", "diff", "--cached", "--binary") -> ProcessResult(0, "diff --git", "")
          command.firstOrNull() == "opencode" -> {
            ProcessResult(0, "{\"type\":\"text\",\"part\":{\"text\":\"feat: tighten parsing\"}}", "")
          }
          else -> ProcessResult(0, "", "")
        }
      }
    )
    val api = GitCliApi(runner)

    val output = api.generateCommitMessage(java.nio.file.Path.of("."))

    assertEquals("feat: tighten parsing", output)
    assertTrue(runner.commands.any { it.contains("--format") && it.contains("json") })
  }

  private class RecordingProcessRunner(
    private val responses: Map<List<String>, ProcessResult> = emptyMap(),
    private val fallback: (List<String>) -> ProcessResult = { ProcessResult(0, "", "") }
  ) : ProcessRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(workingDir: java.nio.file.Path, command: List<String>): ProcessResult {
      commands += command
      return responses[command] ?: fallback(command)
    }
  }
}
