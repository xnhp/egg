package cn.varsa.egg.commands

import cn.varsa.cli.core.CliException
import cn.varsa.egg.ci.CiStatusRequest
import cn.varsa.egg.git.RewordMode
import cn.varsa.egg.git.RewordRequest
import cn.varsa.egg.github.IssuesPullRequest
import cn.varsa.egg.github.IssuesPushRequest
import cn.varsa.egg.github.ReplyRequest
import cn.varsa.egg.github.ResolveRequest
import cn.varsa.egg.github.ThreadPullRequest
import cn.varsa.egg.github.ThreadPushRequest

object CiStatusArgsParser {
  fun parse(args: Array<String>): CiStatusRequest {
    if (args.size > 2) throw CliException("Usage: egg ci status [<job/repo>] [<branch/pr>]", 2)
    if (args.isEmpty()) return CiStatusRequest(jobOrRepo = null, branchOrPr = null)
    if (args.size == 2) return CiStatusRequest(jobOrRepo = args[0], branchOrPr = args[1])

    val arg = args[0]
    if (arg.matches(Regex("(?i)^pr-\\d+$")) || arg.matches(Regex("^\\d+$"))) {
      return CiStatusRequest(jobOrRepo = null, branchOrPr = arg)
    }
    return CiStatusRequest(jobOrRepo = arg, branchOrPr = null)
  }
}

object ReplyArgsParser {
  fun parse(args: Array<String>): ReplyRequest {
    var repo: String? = null
    var body: String? = null
    var bodyFile: String? = null
    val commentIds = mutableListOf<String>()

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--repo" -> repo = optionValue(args, ++idx, "--repo")
        "--body" -> body = optionValue(args, ++idx, "--body")
        "--body-file" -> bodyFile = optionValue(args, ++idx, "--body-file")
        else -> {
          if (arg.startsWith("--")) throw CliException("Unknown option: $arg", 2)
          commentIds += arg
        }
      }
      idx++
    }

    return ReplyRequest(repo = repo, body = body, bodyFile = bodyFile, commentIds = commentIds)
  }
}

object ResolveArgsParser {
  fun parse(args: Array<String>): ResolveRequest {
    var repo: String? = null
    var pr: String? = null
    val commentIds = mutableListOf<String>()

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--repo" -> repo = optionValue(args, ++idx, "--repo")
        "--pr" -> pr = optionValue(args, ++idx, "--pr")
        else -> {
          if (arg.startsWith("--")) throw CliException("Unknown option: $arg", 2)
          commentIds += arg
        }
      }
      idx++
    }

    return ResolveRequest(repo = repo, pr = pr, commentIds = commentIds)
  }
}

object PrFeedbackArgsParser {
  fun parse(args: Array<String>): Pair<String?, String> {
    var repo: String? = null
    var pr: String? = null

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--repo" -> repo = optionValue(args, ++idx, "--repo")
        else -> {
          if (arg.startsWith("--")) throw CliException("Unknown option: $arg", 2)
          if (pr != null) throw CliException("Usage: egg gh pr feedback [--repo owner/name] <pr-number>", 2)
          pr = arg
        }
      }
      idx++
    }

    if (pr == null) throw CliException("Usage: egg gh pr feedback [--repo owner/name] <pr-number>", 2)
    return repo to pr
  }
}

object ThreadPullArgsParser {
  fun parse(args: Array<String>): ThreadPullRequest {
    var repo: String? = null
    var pr: String? = null
    var json = false

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--repo" -> repo = optionValue(args, ++idx, "--repo")
        "--pr" -> pr = optionValue(args, ++idx, "--pr")
        "--json" -> json = true
        else -> throw CliException("Unknown option: $arg", 2)
      }
      idx++
    }

    return ThreadPullRequest(repo = repo, pr = pr, json = json)
  }
}

object ThreadPushArgsParser {
  fun parse(args: Array<String>, entityJson: String): ThreadPushRequest {
    var repo: String? = null
    var pr: String? = null
    var dryRun = false
    var json = false

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--repo" -> repo = optionValue(args, ++idx, "--repo")
        "--pr" -> pr = optionValue(args, ++idx, "--pr")
        "--dry-run" -> dryRun = true
        "--json" -> json = true
        else -> throw CliException("Unknown option: $arg", 2)
      }
      idx++
    }

    return ThreadPushRequest(repo = repo, pr = pr, dryRun = dryRun, json = json, entityJson = entityJson)
  }
}

object IssuesPullArgsParser {
  fun parse(args: Array<String>): IssuesPullRequest {
    var repo: String? = null
    var issue: String? = null
    var state: String? = null
    var json = false

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--repo" -> repo = optionValue(args, ++idx, "--repo")
        "--issue" -> issue = optionValue(args, ++idx, "--issue")
        "--state" -> state = optionValue(args, ++idx, "--state")
        "--json" -> json = true
        else -> throw CliException("Unknown option: $arg", 2)
      }
      idx++
    }

    return IssuesPullRequest(repo = repo, issue = issue, state = state, json = json)
  }
}

object IssuesPushArgsParser {
  fun parse(args: Array<String>, entityJson: String): IssuesPushRequest {
    var repo: String? = null
    var issue: String? = null
    var dryRun = false
    var json = false

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--repo" -> repo = optionValue(args, ++idx, "--repo")
        "--issue" -> issue = optionValue(args, ++idx, "--issue")
        "--dry-run" -> dryRun = true
        "--json" -> json = true
        else -> throw CliException("Unknown option: $arg", 2)
      }
      idx++
    }

    return IssuesPushRequest(repo = repo, issue = issue, dryRun = dryRun, json = json, entityJson = entityJson)
  }
}

object ChangedPathsArgsParser {
  fun parse(args: Array<String>): Pair<Boolean, String?> {
    var staged = false
    var range: String? = null

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--staged" -> staged = true
        else -> {
          if (arg.startsWith("--")) throw CliException("Unknown option: $arg", 2)
          if (range != null) throw CliException("Usage: egg git changed-paths [--staged] [<git-range>]", 2)
          range = arg
        }
      }
      idx++
    }
    return staged to range
  }
}

object WorktreeMakeArgsParser {
  data class ParseResult(
    val repoName: String,
    val branch: String,
    val subdir: String?,
    val override: Boolean
  )

  fun parse(args: Array<String>): ParseResult {
    var override = false
    val positional = mutableListOf<String>()

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--override" -> override = true
        else -> {
          if (arg.startsWith("--")) throw CliException("Unknown option: $arg", 2)
          positional += arg
        }
      }
      idx += 1
    }

    if (positional.size !in 2..3) {
      throw CliException("Usage: egg git worktree make [--override] <repo> <branch> [subdir]", 2)
    }

    return ParseResult(
      repoName = positional[0],
      branch = positional[1],
      subdir = positional.getOrNull(2),
      override = override
    )
  }
}

object RewordArgsParser {
  private const val defaultAuthor = "benjamin.moser@knime.com"
  private const val defaultNumCommits = 3

  data class ParseResult(val request: RewordRequest, val defaultedMode: Boolean)

  fun parse(args: Array<String>): ParseResult {
    if (args.isEmpty()) {
      return ParseResult(
        request = RewordRequest(mode = RewordMode.ISSUE_IDS, numCommits = defaultNumCommits, authorEmail = defaultAuthor),
        defaultedMode = true
      )
    }

    var mode: RewordMode? = null
    var numCommits = defaultNumCommits
    var author = defaultAuthor
    var positionalNumCommits: Int? = null

    var idx = 0
    while (idx < args.size) {
      when (val arg = args[idx]) {
        "--issue-ids" -> mode = setMode(mode, RewordMode.ISSUE_IDS)
        "--remove-WIP" -> mode = setMode(mode, RewordMode.REMOVE_WIP)
        "--author" -> author = optionValue(args, ++idx, "--author").trim()
        "--num-commits" -> {
          val raw = optionValue(args, ++idx, "--num-commits")
          numCommits = parsePositiveInt(raw, "--num-commits")
        }
        else -> {
          if (arg.startsWith("--")) throw CliException("Unknown option: $arg", 2)
          if (positionalNumCommits != null) throw CliException("Usage: egg git reword [--issue-ids|--remove-WIP] [--author <email>] [--num-commits <n>] [n]", 2)
          positionalNumCommits = parsePositiveInt(arg, "num_commits")
        }
      }
      idx += 1
    }

    if (positionalNumCommits != null) {
      if (args.contains("--num-commits")) {
        throw CliException("Error: use either --num-commits or the positional num_commits, not both.", 1)
      }
      numCommits = positionalNumCommits
    }

    val hasOptionArgs = args.any { it.startsWith("-") }
    val onlyPositionalNum = positionalNumCommits != null && !hasOptionArgs
    val defaultedMode = mode == null
    val finalMode = if (onlyPositionalNum || defaultedMode) RewordMode.ISSUE_IDS else mode!!

    return ParseResult(
      request = RewordRequest(mode = finalMode, numCommits = numCommits, authorEmail = author),
      defaultedMode = defaultedMode || onlyPositionalNum
    )
  }

  private fun parsePositiveInt(raw: String, name: String): Int {
    val value = raw.toIntOrNull() ?: throw CliException("Invalid value for $name: $raw", 2)
    if (value <= 0) throw CliException("Error: --num-commits must be greater than 0.", 1)
    return value
  }

  private fun setMode(current: RewordMode?, next: RewordMode): RewordMode {
    if (current != null && current != next) {
      throw CliException("Error: --issue-ids and --remove-WIP are mutually exclusive", 1)
    }
    return next
  }
}

private fun optionValue(args: Array<String>, idx: Int, name: String): String {
  if (idx >= args.size || args[idx].startsWith("--")) {
    throw CliException("Missing value for $name", 2)
  }
  return args[idx]
}
