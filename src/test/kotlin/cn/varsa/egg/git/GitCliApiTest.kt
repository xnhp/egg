package cn.varsa.egg.git

import cn.varsa.egg.runtime.ProcessResult
import cn.varsa.egg.runtime.ProcessRunner
import kotlin.io.path.Path
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitCliApiTest {
  @Test
  fun `make worktree forwards force flag when override is enabled`() {
    val runner = RecordingProcessRunner(
      fallback = { command ->
        when (command) {
          listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/enh/NXT-4439") -> ProcessResult(0, "", "")
          listOf("git", "show-ref", "--verify", "--quiet", "refs/remotes/origin/enh/NXT-4439") -> ProcessResult(1, "", "")
          listOf("git", "worktree", "list", "--porcelain") -> ProcessResult(
            0,
            "worktree /tmp/wt/knime-ui_enh_NXT-4439\nHEAD abc\nbranch refs/heads/enh/NXT-4439\n",
            ""
          )
          else -> ProcessResult(0, "", "")
        }
      }
    )
    val api = GitCliApi(runner)

    api.makeWorktree(Path("/tmp/wt"), repoName = "knime-ui", branch = "enh/NXT-4439", subdir = null, override = true)

    assertTrue(
      runner.commands.contains(
        listOf("git", "worktree", "add", "-f", "/tmp/wt/knime-ui_enh_NXT-4439", "enh/NXT-4439")
      )
    )
  }

  @Test
  fun `make worktree logs info when overriding registered path`() {
    val runner = RecordingProcessRunner(
      fallback = { command ->
        when (command) {
          listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/enh/NXT-4439") -> ProcessResult(0, "", "")
          listOf("git", "show-ref", "--verify", "--quiet", "refs/remotes/origin/enh/NXT-4439") -> ProcessResult(1, "", "")
          listOf("git", "worktree", "list", "--porcelain") -> ProcessResult(
            0,
            "worktree /tmp/wt/knime-ui_enh_NXT-4439\nHEAD abc\nbranch refs/heads/enh/NXT-4439\n",
            ""
          )
          else -> ProcessResult(0, "", "")
        }
      }
    )
    val api = GitCliApi(runner)
    val oldOut = System.out
    val out = ByteArrayOutputStream()
    try {
      System.setOut(PrintStream(out))
      api.makeWorktree(Path("/tmp/wt"), repoName = "knime-ui", branch = "enh/NXT-4439", subdir = null, override = true)
    } finally {
      System.setOut(oldOut)
    }

    assertTrue(out.toString().contains("[INFO] Overriding existing registered worktree path: /tmp/wt/knime-ui_enh_NXT-4439"))
  }

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

  @Test
  fun `reword remove WIP rewrites matching commits in chronological order`() {
    val runner = RecordingProcessRunner(
      fallback = { command ->
        when (command) {
          listOf("git", "status", "--porcelain") -> ProcessResult(0, "", "")
          listOf("git", "log", "-2", "--format=%H", "--author", "dev@knime.com") -> ProcessResult(0, "c2\nc1", "")
          listOf("git", "log", "-1", "--format=%B", "c1") -> ProcessResult(0, "WIP: first\n\nBody", "")
          listOf("git", "log", "-1", "--format=%B", "c2") -> ProcessResult(0, "second", "")
          listOf("git", "branch", "--show-current") -> ProcessResult(0, "todo_NXT-4632", "")
          listOf("git", "rev-parse", "c1^") -> ProcessResult(0, "p1", "")
          listOf("git", "checkout", "-b", "temp-reword-c1", "c1") -> ProcessResult(0, "", "")
          else -> when {
            command == listOf("git", "commit", "--amend", "-m", "first\n\nBody") -> ProcessResult(0, "", "")
            command == listOf("git", "rev-parse", "HEAD") -> ProcessResult(0, "newc1", "")
            command == listOf("git", "checkout", "todo_NXT-4632") -> ProcessResult(0, "", "")
            command == listOf("git", "rebase", "--onto", "newc1", "p1") -> ProcessResult(0, "", "")
            command == listOf("git", "branch", "-D", "temp-reword-c1") -> ProcessResult(0, "", "")
            else -> ProcessResult(0, "", "")
          }
        }
      }
    )
    val api = GitCliApi(runner)

    api.reword(Path("."), RewordRequest(mode = RewordMode.REMOVE_WIP, numCommits = 2, authorEmail = "dev@knime.com"))

    val amendIndex = runner.commands.indexOf(listOf("git", "commit", "--amend", "-m", "first\n\nBody"))
    val c1ReadIndex = runner.commands.indexOf(listOf("git", "log", "-1", "--format=%B", "c1"))
    val c2ReadIndex = runner.commands.indexOf(listOf("git", "log", "-1", "--format=%B", "c2"))
    assertTrue(c1ReadIndex in 0 until c2ReadIndex)
    assertTrue(amendIndex > c1ReadIndex)
  }

  @Test
  fun `reword refreshes commit list after each rewrite`() {
    var logCalls = 0
    lateinit var runner: RecordingProcessRunner
    runner = RecordingProcessRunner(
      fallback = { command ->
        when (command) {
          listOf("git", "status", "--porcelain") -> ProcessResult(0, "", "")
          listOf("git", "log", "-2", "--format=%H", "--author", "dev@knime.com") -> {
            logCalls += 1
            when (logCalls) {
              1 -> ProcessResult(0, "c2\nc1", "")
              2 -> ProcessResult(0, "n2\nn1", "")
              else -> ProcessResult(0, "f2\nf1", "")
            }
          }
          listOf("git", "log", "-1", "--format=%B", "c1") -> ProcessResult(0, "WIP: first", "")
          listOf("git", "log", "-1", "--format=%B", "n1") -> ProcessResult(0, "first", "")
          listOf("git", "log", "-1", "--format=%B", "n2") -> ProcessResult(0, "WIP: second", "")
          listOf("git", "log", "-1", "--format=%B", "f1") -> ProcessResult(0, "first", "")
          listOf("git", "log", "-1", "--format=%B", "f2") -> ProcessResult(0, "second", "")
          listOf("git", "branch", "--show-current") -> ProcessResult(0, "todo_NXT-4632", "")
          listOf("git", "rev-parse", "c1^") -> ProcessResult(0, "p1", "")
          listOf("git", "rev-parse", "n2^") -> ProcessResult(0, "n1", "")
          listOf("git", "checkout", "-b", "temp-reword-c1", "c1") -> ProcessResult(0, "", "")
          listOf("git", "checkout", "-b", "temp-reword-n2", "n2") -> ProcessResult(0, "", "")
          listOf("git", "commit", "--amend", "-m", "first") -> ProcessResult(0, "", "")
          listOf("git", "commit", "--amend", "-m", "second") -> ProcessResult(0, "", "")
          listOf("git", "rev-parse", "HEAD") -> {
            val amends = runner.commands.count { it == listOf("git", "commit", "--amend", "-m", "first") || it == listOf("git", "commit", "--amend", "-m", "second") }
            when (amends) {
              1 -> ProcessResult(0, "n1", "")
              else -> ProcessResult(0, "f2", "")
            }
          }
          listOf("git", "checkout", "todo_NXT-4632") -> ProcessResult(0, "", "")
          listOf("git", "rebase", "--onto", "n1", "p1") -> ProcessResult(0, "", "")
          listOf("git", "rebase", "--onto", "f2", "n1") -> ProcessResult(0, "", "")
          listOf("git", "branch", "-D", "temp-reword-c1") -> ProcessResult(0, "", "")
          listOf("git", "branch", "-D", "temp-reword-n2") -> ProcessResult(0, "", "")
          else -> ProcessResult(0, "", "")
        }
      }
    )
    val api = GitCliApi(runner)

    api.reword(Path("."), RewordRequest(mode = RewordMode.REMOVE_WIP, numCommits = 2, authorEmail = "dev@knime.com"))

    assertTrue(runner.commands.contains(listOf("git", "commit", "--amend", "-m", "first")))
    assertTrue(runner.commands.contains(listOf("git", "commit", "--amend", "-m", "second")))
    assertTrue(logCalls >= 2)
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
