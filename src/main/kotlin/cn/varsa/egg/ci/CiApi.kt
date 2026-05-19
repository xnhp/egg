package cn.varsa.egg.ci

import cn.varsa.cli.core.CliException
import cn.varsa.egg.runtime.ProcessRunner
import cn.varsa.egg.runtime.runCaptureOrNull
import cn.varsa.egg.runtime.runCaptureOrThrow
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CiStatusRequest(
  val jobOrRepo: String?,
  val branchOrPr: String?
)

interface CiApi {
  fun status(workingDir: Path, request: CiStatusRequest): String
}

object UnsupportedCiApi : CiApi {
  override fun status(workingDir: Path, request: CiStatusRequest): String {
    throw CliException("CI API is not configured", 1)
  }
}

class JenkinsCiApi(
  private val processRunner: ProcessRunner,
  private val http: JenkinsHttp = DefaultJenkinsHttp()
) : CiApi {
  private val defaultMaxFailedTests = 20
  private val json = Json { ignoreUnknownKeys = true }

  override fun status(workingDir: Path, request: CiStatusRequest): String {
    val site = readConfig("JENKINS_SITE")
    val user = readConfig("JENKINS_USER")
    val token = readConfig("JENKINS_TOKEN")
    val authHeader = "Basic ${basicAuth(user, token)}"
    val baseUrl = normalizeBaseUrl(site)

    val repoJob = request.jobOrRepo?.trim().orEmpty().ifBlank { inferRepoJob(workingDir) }
    val rawRef = request.branchOrPr?.trim().orEmpty().ifBlank { inferBranchOrPr(workingDir) }
    val normalizedRef = normalizeRef(rawRef)

    val probeCandidates = buildCandidateJobUrls(baseUrl, repoJob, normalizedRef)
    val build = resolveBuild(probeCandidates, authHeader)
    val stages = fetchStages(build.url, authHeader)
    val failures = fetchTestFailures(build.url, authHeader)
    val maxFailedTests = readMaxFailedTests()

    val lines = mutableListOf<String>()
    lines += "Pipeline: ${build.overallStatus}"
    lines += "Job: ${build.jobName}"
    lines += "Branch/PR: $normalizedRef"
    lines += "Build: #${build.number}"
    lines += "URL: ${build.url}"

    if (stages.isEmpty()) {
      lines += "Stages: none reported"
    } else {
      lines += "Stages:"
      stages.forEach { stage ->
        lines += "- ${stageStatusPrefix(stage.status)} ${stage.name}: ${stage.status}"
      }
    }

    if (failures.isEmpty()) {
      lines += "Failed tests: none"
    } else {
      lines += "Failed tests:"
      failures.take(maxFailedTests).forEach { failure ->
        lines += "- $failure"
      }
      if (failures.size > maxFailedTests) {
        lines += "- ... and ${failures.size - maxFailedTests} more"
      }
    }

    return lines.joinToString("\n")
  }

  private fun readMaxFailedTests(): Int {
    val raw = System.getenv("EGG_CI_MAX_FAILED_TESTS")?.trim().orEmpty().ifBlank {
      System.getProperty("EGG_CI_MAX_FAILED_TESTS")?.trim().orEmpty()
    }
    if (raw.isBlank()) return defaultMaxFailedTests
    return raw.toIntOrNull()?.takeIf { it > 0 } ?: defaultMaxFailedTests
  }

  private fun stageStatusPrefix(status: String): String = when (status.uppercase()) {
    "SUCCESS" -> "[OK]"
    "FAILED", "FAILURE", "ERROR" -> "[FAIL]"
    "IN_PROGRESS", "RUNNING", "PAUSED_PENDING_INPUT" -> "[RUN]"
    "NOT_EXECUTED", "SKIPPED", "ABORTED" -> "[SKIP]"
    else -> "[?]"
  }

  private fun inferRepoJob(workingDir: Path): String {
    val ghRepoName = processRunner.runCaptureOrNull(
      workingDir,
      listOf("gh", "repo", "view", "--json", "name", "-q", ".name")
    )?.trim().orEmpty()
    if (ghRepoName.isNotBlank()) return ghRepoName

    val remote = processRunner.runCaptureOrNull(
      workingDir,
      listOf("git", "remote", "get-url", "origin")
    )?.trim().orEmpty()
    val repoName = remote
      .substringAfterLast('/')
      .substringAfterLast(':')
      .removeSuffix(".git")
      .trim()
    if (repoName.isNotBlank()) return repoName

    throw CliException("Could not infer Jenkins job/repo from current directory", 2)
  }

  private fun inferBranchOrPr(workingDir: Path): String {
    val prId = processRunner.runCaptureOrNull(
      workingDir,
      listOf("gh", "pr", "view", "--json", "number", "--jq", ".number")
    )?.trim().orEmpty()
    if (prId.matches(Regex("\\d+"))) return "PR-$prId"

    val branch = processRunner.runCaptureOrNull(
      workingDir,
      listOf("git", "branch", "--show-current")
    )?.trim().orEmpty()
    if (branch.isNotBlank()) return branch

    throw CliException("Could not infer branch/pr from current directory", 2)
  }

  private fun readConfig(key: String): String {
    val value = System.getenv(key)?.trim().orEmpty().ifBlank { System.getProperty(key)?.trim().orEmpty() }
    if (value.isBlank()) {
      throw CliException("Missing $key. Set it in environment or .env", 2)
    }
    return value
  }

  private fun normalizeBaseUrl(site: String): String {
    val trimmed = site.trim().removeSuffix("/")
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      trimmed
    } else {
      "https://$trimmed"
    }
  }

  private fun normalizeRef(rawRef: String): String {
    val trimmed = rawRef.trim()
    if (trimmed.matches(Regex("\\d+"))) return "PR-$trimmed"
    if (trimmed.matches(Regex("(?i)^pr-\\d+$"))) return "PR-${trimmed.substringAfter('-')}"
    return trimmed
  }

  private fun buildCandidateJobUrls(baseUrl: String, jobOrRepo: String, ref: String): List<String> {
    val parsedRepo = parseRepoJob(jobOrRepo)
    val repoSegment = encodePathSegment(parsedRepo)
    val refSegment = encodePathSegment(ref)
    val isPr = ref.matches(Regex("^PR-\\d+$"))
    val candidates = mutableListOf<String>()

    if (isPr) {
      candidates += "$baseUrl/job/$repoSegment/view/change-requests/job/$refSegment/"
    }
    candidates += "$baseUrl/job/$repoSegment/job/$refSegment/"

    return candidates
  }

  private fun parseRepoJob(jobOrRepo: String): String {
    val trimmed = jobOrRepo.trim().removeSuffix("/")
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      val path = URI(trimmed).path.trim('/').split('/').filter { it.isNotBlank() }
      val jobIndex = path.indexOf("job")
      if (jobIndex >= 0 && path.size > jobIndex + 1) {
        return path[jobIndex + 1]
      }
      throw CliException("Could not parse Jenkins repo job from URL: $jobOrRepo", 2)
    }

    if (trimmed.contains('/')) {
      return trimmed.substringAfterLast('/').ifBlank {
        throw CliException("Could not parse Jenkins repo job from: $jobOrRepo", 2)
      }
    }

    return trimmed
  }

  private fun resolveBuild(candidateJobUrls: List<String>, authHeader: String): BuildSummary {
    val buildTree = "number,url,fullDisplayName,result,building"
    val probes = mutableListOf<String>()
    candidateJobUrls.forEach { jobUrl ->
      val url = "${jobUrl}lastBuild/api/json?tree=$buildTree"
      probes += url
      val response = http.get(url, authHeader)
      if (response.status == 404) return@forEach
      if (response.status != 200) {
        throw CliException("Jenkins request failed (${response.status}) for $url", 1)
      }

      val root = json.parseToJsonElement(response.body).jsonObject
      val number = root["number"]?.jsonPrimitive?.intOrNull
        ?: throw CliException("Jenkins response missing build number for $jobUrl", 1)
      val buildUrl = root["url"]?.jsonPrimitive?.contentOrNull
        ?: throw CliException("Jenkins response missing build url for $jobUrl", 1)
      val fullDisplayName = root["fullDisplayName"]?.jsonPrimitive?.contentOrNull.orEmpty()
      val result = root["result"]?.jsonPrimitive?.contentOrNull
      val building = root["building"]?.jsonPrimitive?.booleanOrNull == true
      val status = when {
        building -> "RUNNING"
        result.isNullOrBlank() -> "UNKNOWN"
        else -> result
      }
      val jobName = fullDisplayName.substringBefore(" #").ifBlank { jobUrl.trimEnd('/').substringAfterLast('/') }
      return BuildSummary(jobName = jobName, number = number, url = buildUrl, overallStatus = status)
    }

    val attempted = probes.joinToString("\n")
    throw CliException("Could not find Jenkins build for given job/ref. Tried:\n$attempted", 2)
  }

  private fun fetchStages(buildUrl: String, authHeader: String): List<StageStatus> {
    val response = http.get("${buildUrl}wfapi/describe", authHeader)
    if (response.status == 404) return emptyList()
    if (response.status != 200) {
      throw CliException("Jenkins stage request failed (${response.status}) for ${buildUrl}wfapi/describe", 1)
    }

    val root = json.parseToJsonElement(response.body).jsonObject
    val stages = root["stages"]?.jsonArray ?: return emptyList()
    return stages.mapNotNull { item ->
      val stage = item.jsonObject
      val name = stage["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      val status = stage["status"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
      StageStatus(name = name, status = status)
    }
  }

  private fun fetchTestFailures(buildUrl: String, authHeader: String): List<String> {
    val tree = "suites[cases[className,name,status,errorDetails,errorStackTrace]]"
    val response = http.get("${buildUrl}testReport/api/json?tree=$tree", authHeader)
    if (response.status == 404) return emptyList()
    if (response.status != 200) {
      throw CliException("Jenkins test report request failed (${response.status}) for ${buildUrl}testReport/api/json", 1)
    }

    val root = json.parseToJsonElement(response.body).jsonObject
    val suites = root["suites"]?.jsonArray ?: return emptyList()
    val failures = mutableListOf<String>()

    suites.forEach { suiteElement ->
      val suite = suiteElement.jsonObject
      val cases = suite["cases"]?.jsonArray ?: return@forEach
      cases.forEach { caseElement ->
        val case = caseElement.jsonObject
        val status = case["status"]?.jsonPrimitive?.contentOrNull.orEmpty().uppercase()
        if (status != "FAILED" && status != "REGRESSION") return@forEach

        val className = case["className"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val testName = case["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val details = case["errorDetails"]?.jsonPrimitive?.contentOrNull
          ?: case["errorStackTrace"]?.jsonPrimitive?.contentOrNull
          ?: status
        val oneLine = details.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { status }
        val qualifiedName = listOf(className, testName).filter { it.isNotBlank() }.joinToString(".")
        failures += "$qualifiedName: $oneLine"
      }
    }

    return failures
  }

  private fun basicAuth(user: String, token: String): String {
    val raw = "$user:$token"
    return Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
  }

  private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private data class BuildSummary(
    val jobName: String,
    val number: Int,
    val url: String,
    val overallStatus: String
  )

  private data class StageStatus(
    val name: String,
    val status: String
  )
}

interface JenkinsHttp {
  fun get(url: String, authHeader: String): JenkinsResponse
}

data class JenkinsResponse(val status: Int, val body: String)

class DefaultJenkinsHttp : JenkinsHttp {
  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

  override fun get(url: String, authHeader: String): JenkinsResponse {
    val request = HttpRequest.newBuilder(URI(url))
      .header("Accept", "application/json")
      .header("Authorization", authHeader)
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return JenkinsResponse(status = response.statusCode(), body = response.body())
  }
}
