package cn.varsa.egg.commands

import cn.varsa.cli.core.CliException
import cn.varsa.egg.github.ReplyRequest
import cn.varsa.egg.github.ResolveRequest
import cn.varsa.egg.github.ThreadPullRequest
import cn.varsa.egg.github.ThreadPushRequest

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

private fun optionValue(args: Array<String>, idx: Int, name: String): String {
  if (idx >= args.size || args[idx].startsWith("--")) {
    throw CliException("Missing value for $name", 2)
  }
  return args[idx]
}
