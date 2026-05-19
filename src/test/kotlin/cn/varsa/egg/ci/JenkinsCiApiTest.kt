package cn.varsa.egg.ci

import cn.varsa.egg.runtime.ProcessResult
import cn.varsa.egg.runtime.ProcessRunner
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JenkinsCiApiTest {
  @AfterTest
  fun clearJenkinsProperties() {
    System.clearProperty("JENKINS_SITE")
    System.clearProperty("JENKINS_USER")
    System.clearProperty("JENKINS_TOKEN")
    System.clearProperty("EGG_CI_MAX_FAILED_TESTS")
  }

  @Test
  fun `status infers repo and PR from cwd`() {
    System.setProperty("JENKINS_SITE", "jenkins.knime.com")
    System.setProperty("JENKINS_USER", "user")
    System.setProperty("JENKINS_TOKEN", "token")

    val runner = RecordingRunner(
      responses = mapOf(
        listOf("gh", "repo", "view", "--json", "name", "-q", ".name") to ProcessResult(0, "knime-gateway", ""),
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number") to ProcessResult(0, "79", "")
      )
    )
    val http = RecordingJenkinsHttp(
      responses = mapOf(
        "https://jenkins.knime.com/job/knime-gateway/view/change-requests/job/PR-79/lastBuild/api/json?tree=number,url,fullDisplayName,result,building" to
          JenkinsResponse(
            200,
            """
            {"number":101,"url":"https://jenkins.knime.com/job/knime-gateway/view/change-requests/job/PR-79/101/","fullDisplayName":"knime-gateway/PR-79 #101","result":"SUCCESS","building":false}
            """.trimIndent()
          ),
        "https://jenkins.knime.com/job/knime-gateway/view/change-requests/job/PR-79/101/wfapi/describe" to
          JenkinsResponse(
            200,
            """
            {"stages":[{"name":"Checkout","status":"SUCCESS"},{"name":"Tests","status":"FAILED"}]}
            """.trimIndent()
          ),
        "https://jenkins.knime.com/job/knime-gateway/view/change-requests/job/PR-79/101/testReport/api/json?tree=suites[cases[className,name,status,errorDetails,errorStackTrace]]" to
          JenkinsResponse(
            200,
            """
            {"suites":[{"cases":[{"className":"a.b.C","name":"fails","status":"FAILED","errorDetails":"expected <1> but was <2>\nstack"}]}]}
            """.trimIndent()
          )
      )
    )
    val api = JenkinsCiApi(runner, http)

    val output = api.status(Path.of("."), CiStatusRequest(jobOrRepo = null, branchOrPr = null))

    assertTrue(output.contains("Pipeline: SUCCESS"))
    assertTrue(output.contains("Branch/PR: PR-79"))
    assertTrue(output.contains("- [OK] Checkout: SUCCESS"))
    assertTrue(output.contains("- [FAIL] Tests: FAILED"))
    assertTrue(output.contains("- a.b.C.fails: expected <1> but was <2>"))
    assertEquals(2, runner.commands.size)
  }

  @Test
  fun `status truncates failed tests based on configured maximum`() {
    System.setProperty("JENKINS_SITE", "jenkins.knime.com")
    System.setProperty("JENKINS_USER", "user")
    System.setProperty("JENKINS_TOKEN", "token")
    System.setProperty("EGG_CI_MAX_FAILED_TESTS", "2")

    val runner = RecordingRunner(
      responses = mapOf(
        listOf("gh", "repo", "view", "--json", "name", "-q", ".name") to ProcessResult(0, "knime-gateway", ""),
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number") to ProcessResult(0, "79", "")
      )
    )
    val http = RecordingJenkinsHttp(
      responses = mapOf(
        "https://jenkins.knime.com/job/knime-gateway/view/change-requests/job/PR-79/lastBuild/api/json?tree=number,url,fullDisplayName,result,building" to
          JenkinsResponse(
            200,
            """
            {"number":101,"url":"https://jenkins.knime.com/job/knime-gateway/view/change-requests/job/PR-79/101/","fullDisplayName":"knime-gateway/PR-79 #101","result":"FAILURE","building":false}
            """.trimIndent()
          ),
        "https://jenkins.knime.com/job/knime-gateway/view/change-requests/job/PR-79/101/wfapi/describe" to
          JenkinsResponse(200, "{" + "\"stages\":[{\"name\":\"Tests\",\"status\":\"FAILED\"}]}"),
        "https://jenkins.knime.com/job/knime-gateway/view/change-requests/job/PR-79/101/testReport/api/json?tree=suites[cases[className,name,status,errorDetails,errorStackTrace]]" to
          JenkinsResponse(
            200,
            """
            {"suites":[{"cases":[
              {"className":"suite.A","name":"test1","status":"FAILED","errorDetails":"boom 1"},
              {"className":"suite.A","name":"test2","status":"FAILED","errorDetails":"boom 2"},
              {"className":"suite.A","name":"test3","status":"FAILED","errorDetails":"boom 3"}
            ]}]}
            """.trimIndent()
          )
      )
    )
    val api = JenkinsCiApi(runner, http)

    val output = api.status(Path.of("."), CiStatusRequest(jobOrRepo = null, branchOrPr = null))

    assertTrue(output.contains("- suite.A.test1: boom 1"))
    assertTrue(output.contains("- suite.A.test2: boom 2"))
    assertFalse(output.contains("suite.A.test3"))
    assertTrue(output.contains("- ... and 1 more"))
  }

  @Test
  fun `status falls back to git branch when current PR is unavailable`() {
    System.setProperty("JENKINS_SITE", "https://jenkins.knime.com")
    System.setProperty("JENKINS_USER", "user")
    System.setProperty("JENKINS_TOKEN", "token")

    val runner = RecordingRunner(
      responses = mapOf(
        listOf("gh", "repo", "view", "--json", "name", "-q", ".name") to ProcessResult(0, "knime-gateway", ""),
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number") to ProcessResult(1, "", "no pull requests found"),
        listOf("git", "branch", "--show-current") to ProcessResult(0, "AP-25868-new-kai-api", "")
      )
    )
    val http = RecordingJenkinsHttp(
      responses = mapOf(
        "https://jenkins.knime.com/job/knime-gateway/job/AP-25868-new-kai-api/lastBuild/api/json?tree=number,url,fullDisplayName,result,building" to
          JenkinsResponse(
            200,
            """
            {"number":55,"url":"https://jenkins.knime.com/job/knime-gateway/job/AP-25868-new-kai-api/55/","fullDisplayName":"knime-gateway/AP-25868-new-kai-api #55","result":"FAILURE","building":false}
            """.trimIndent()
          ),
        "https://jenkins.knime.com/job/knime-gateway/job/AP-25868-new-kai-api/55/wfapi/describe" to JenkinsResponse(404, ""),
        "https://jenkins.knime.com/job/knime-gateway/job/AP-25868-new-kai-api/55/testReport/api/json?tree=suites[cases[className,name,status,errorDetails,errorStackTrace]]" to JenkinsResponse(404, "")
      )
    )
    val api = JenkinsCiApi(runner, http)

    val output = api.status(Path.of("."), CiStatusRequest(jobOrRepo = null, branchOrPr = null))

    assertTrue(output.contains("Pipeline: FAILURE"))
    assertTrue(output.contains("Branch/PR: AP-25868-new-kai-api"))
    assertTrue(output.contains("Stages: none reported"))
    assertTrue(output.contains("Failed tests: none"))
    assertEquals(3, runner.commands.size)
  }

  @Test
  fun `status defaults to master when PR and branch cannot be inferred`() {
    System.setProperty("JENKINS_SITE", "https://jenkins.knime.com")
    System.setProperty("JENKINS_USER", "user")
    System.setProperty("JENKINS_TOKEN", "token")

    val runner = RecordingRunner(
      responses = mapOf(
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number") to ProcessResult(1, "", "no pull requests found"),
        listOf("git", "branch", "--show-current") to ProcessResult(1, "", "not a git repository")
      )
    )
    val http = RecordingJenkinsHttp(
      responses = mapOf(
        "https://jenkins.knime.com/job/knime-gateway/job/master/lastBuild/api/json?tree=number,url,fullDisplayName,result,building" to
          JenkinsResponse(
            200,
            """
            {"number":11,"url":"https://jenkins.knime.com/job/knime-gateway/job/master/11/","fullDisplayName":"knime-gateway/master #11","result":"SUCCESS","building":false}
            """.trimIndent()
          ),
        "https://jenkins.knime.com/job/knime-gateway/job/master/11/wfapi/describe" to JenkinsResponse(404, ""),
        "https://jenkins.knime.com/job/knime-gateway/job/master/11/testReport/api/json?tree=suites[cases[className,name,status,errorDetails,errorStackTrace]]" to JenkinsResponse(404, "")
      )
    )
    val api = JenkinsCiApi(runner, http)

    val output = api.status(Path.of("."), CiStatusRequest(jobOrRepo = "knime-gateway", branchOrPr = null))

    assertTrue(output.contains("Branch/PR: master"))
    assertTrue(output.contains("Pipeline: SUCCESS"))
  }

  private class RecordingRunner(
    private val responses: Map<List<String>, ProcessResult>
  ) : ProcessRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(workingDir: Path, command: List<String>): ProcessResult {
      commands += command
      return responses[command] ?: ProcessResult(1, "", "Missing stub for: ${command.joinToString(" ")}")
    }
  }

  private class RecordingJenkinsHttp(
    private val responses: Map<String, JenkinsResponse>
  ) : JenkinsHttp {
    val urls = mutableListOf<String>()

    override fun get(url: String, authHeader: String): JenkinsResponse {
      urls += url
      return responses[url] ?: JenkinsResponse(404, "")
    }
  }
}
