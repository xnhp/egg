package cn.varsa.egg.github

import cn.varsa.cli.core.CliException
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ThreadCommentSnapshot(
  val id: Long?,
  val author: String?,
  val body: String?,
  val createdAt: String?,
  val updatedAt: String?,
  val url: String?
)

data class ThreadSnapshot(
  val id: String,
  val isResolved: Boolean,
  val isOutdated: Boolean,
  val path: String?,
  val line: Int?,
  val comments: List<ThreadCommentSnapshot>
)

enum class ResolutionOpType { RESOLVE, UNRESOLVE }

data class ThreadDelta(
  val appendedBodies: List<String>,
  val resolutionOp: ResolutionOpType?
)

object ThreadSyncFingerprint {
  fun compute(snapshot: ThreadSnapshot): String {
    val canonical = canonicalSnapshot(snapshot).toString()
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(canonical.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    return "sha256:$digest"
  }

  private fun canonicalSnapshot(snapshot: ThreadSnapshot): JsonObject = JsonObject(
    mapOf(
      "id" to JsonPrimitive(snapshot.id),
      "isResolved" to JsonPrimitive(snapshot.isResolved),
      "isOutdated" to JsonPrimitive(snapshot.isOutdated),
      "path" to stringOrNull(snapshot.path),
      "line" to intOrNull(snapshot.line),
      "comments" to JsonArray(snapshot.comments.map { comment ->
        JsonObject(
          mapOf(
            "id" to longOrNull(comment.id),
            "author" to stringOrNull(comment.author),
            "body" to stringOrNull(comment.body),
            "createdAt" to stringOrNull(comment.createdAt),
            "updatedAt" to stringOrNull(comment.updatedAt)
          )
        )
      })
    )
  )
}

object ThreadDeltaDeriver {
  fun derive(remote: ThreadSnapshot, local: ThreadSnapshot): ThreadDelta {
    if (local.comments.size < remote.comments.size) {
      throw CliException("Local comments removed existing remote comments", 2)
    }

    remote.comments.indices.forEach { index ->
      if (remote.comments[index].canonical() != local.comments[index].canonical()) {
        throw CliException("Unsupported edit for existing comments; only appending is allowed", 2)
      }
    }

    val appended = local.comments.drop(remote.comments.size).mapIndexed { index, comment ->
      val body = comment.body?.trim()
      if (body.isNullOrEmpty()) {
        throw CliException("Appended comment at index ${remote.comments.size + index} must include non-empty body", 2)
      }
      body
    }

    val resolution = when {
      !remote.isResolved && local.isResolved -> ResolutionOpType.RESOLVE
      remote.isResolved && !local.isResolved -> ResolutionOpType.UNRESOLVE
      else -> null
    }

    return ThreadDelta(appendedBodies = appended, resolutionOp = resolution)
  }

  private fun ThreadCommentSnapshot.canonical(): String = listOf(
    id?.toString() ?: "",
    author ?: "",
    body ?: "",
    createdAt ?: "",
    updatedAt ?: ""
  ).joinToString("\u0000")
}

private fun stringOrNull(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

private fun intOrNull(value: Int?) = value?.let(::JsonPrimitive) ?: JsonNull

private fun longOrNull(value: Long?) = value?.let(::JsonPrimitive) ?: JsonNull
