package cn.varsa.egg.github

import cn.varsa.egg.runtime.ProcessResult
import cn.varsa.egg.runtime.ProcessRunner
import kotlin.test.Test
import kotlin.test.assertEquals

class GhCliGitHubApiTest {
  @Test
  fun `current pr id uses gh pr view json number`() {
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number") to ProcessResult(0, "42", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val id = api.currentPrId(java.nio.file.Path.of("."))

    assertEquals("42", id)
    assertEquals(listOf("gh", "pr", "view", "--json", "number", "--jq", ".number"), runner.commands.single())
  }

  @Test
  fun `reply posts one api call per comment`() {
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner") to ProcessResult(0, "octo/repo", ""),
        listOf("gh", "api", "repos/octo/repo/pulls/comments/10/replies", "-f", "body=ok", "--jq", "[.pull_request_review_id, .pull_request_url] | @tsv") to ProcessResult(0, "11\thttps://api.github.com/repos/octo/repo/pulls/20", ""),
        listOf("gh", "api", "repos/octo/repo/pulls/comments/12/replies", "-f", "body=ok", "--jq", "[.pull_request_review_id, .pull_request_url] | @tsv") to ProcessResult(0, "13\thttps://api.github.com/repos/octo/repo/pulls/21", ""),
        listOf("gh", "api", "-X", "POST", "repos/octo/repo/pulls/20/reviews/11/events", "-f", "event=COMMENT") to ProcessResult(0, "", ""),
        listOf("gh", "api", "-X", "POST", "repos/octo/repo/pulls/21/reviews/13/events", "-f", "event=COMMENT") to ProcessResult(0, "", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val output = api.replyToReviewComments(
      java.nio.file.Path.of("."),
      ReplyRequest(repo = null, body = "ok", bodyFile = null, commentIds = listOf("10", "12"))
    )

    assertEquals("Replied: 2", output)
    assertEquals(5, runner.commands.size)
  }

  @Test
  fun `review status returns empty list when current pr is missing`() {
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number") to ProcessResult(1, "", "no pull requests found")
      )
    )
    val api = GhCliGitHubApi(runner)

    val output = api.currentPrReviewStatus(java.nio.file.Path.of("."), json = true)

    assertEquals("[]", output)
  }

  private class RecordingProcessRunner(
    private val responses: Map<List<String>, ProcessResult>
  ) : ProcessRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(workingDir: java.nio.file.Path, command: List<String>): ProcessResult {
      commands += command
      return responses[command] ?: ProcessResult(0, "", "")
    }
  }
}
