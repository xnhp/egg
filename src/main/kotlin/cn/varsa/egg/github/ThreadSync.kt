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
  val url: String?,
  val minimizedReason: String? = null,
  val minimizedReasonSet: Boolean = false,
  val nodeId: String? = null
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

enum class CommentVisibilityOpType { MINIMIZE, UNMINIMIZE }

data class CommentVisibilityOp(
  val commentId: Long,
  val type: CommentVisibilityOpType,
  val reason: String?
)

data class ThreadDelta(
  val deletedCommentIds: List<Long>,
  val appendedBodies: List<String>,
  val visibilityOps: List<CommentVisibilityOp>,
  val resolutionOp: ResolutionOpType?
)

private val AllowedMinimizeReasons = setOf(
  "ABUSE",
  "OFF_TOPIC",
  "OUTDATED",
  "RESOLVED",
  "SPAM",
  "DUPLICATE"
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
            "updatedAt" to stringOrNull(comment.updatedAt),
            "minimizedReason" to stringOrNull(comment.minimizedReason)
          )
        )
      })
    )
  )
}

object ThreadDeltaDeriver {
  fun derive(remote: ThreadSnapshot, local: ThreadSnapshot): ThreadDelta {
    var localIndex = 0
    var remoteIndex = 0
    val deletedIds = mutableListOf<Long>()

    while (localIndex < local.comments.size && remoteIndex < remote.comments.size) {
      val localComment = local.comments[localIndex]
      val remoteComment = remote.comments[remoteIndex]
      if (localComment.matchesRemote(remoteComment)) {
        localIndex += 1
        remoteIndex += 1
        continue
      }

      val localId = localComment.id
      if (localId == null) {
        break
      }

      val foundAt = remote.comments.indexOfFirst { it.id == localId }
      if (foundAt < remoteIndex) {
        throw CliException("Unsupported reordering of existing comments", 2)
      }
      if (foundAt < 0) {
        throw CliException("Local comment references unknown remote id $localId", 2)
      }

      for (i in remoteIndex until foundAt) {
        remote.comments[i].id?.let { deletedIds += it }
      }

      val matched = remote.comments[foundAt]
      if (!localComment.matchesRemote(matched)) {
        throw CliException("Unsupported edit for existing comments; only appending/removal is allowed", 2)
      }
      localIndex += 1
      remoteIndex = foundAt + 1
    }

    if (localIndex < local.comments.size && remoteIndex < remote.comments.size) {
      val remainingLocalWithIds = local.comments.drop(localIndex).any { it.id != null }
      if (remainingLocalWithIds) {
        throw CliException("Existing comments with id must stay in original order", 2)
      }
      throw CliException("Unsupported edit for existing comments; only appending/removal is allowed", 2)
    }

    for (i in remoteIndex until remote.comments.size) {
      remote.comments[i].id?.let { deletedIds += it }
    }

    val deletedSet = deletedIds.toSet()
    val remoteById = remote.comments.mapNotNull { comment -> comment.id?.let { it to comment } }.toMap()
    val visibilityOps = mutableListOf<CommentVisibilityOp>()
    local.comments.forEachIndexed { index, localComment ->
      val localId = localComment.id
      if (localId == null) {
        if (localComment.minimizedReasonSet) {
          throw CliException("Appended comment at index $index cannot set minimizedReason", 2)
        }
        return@forEachIndexed
      }
      if (deletedSet.contains(localId)) return@forEachIndexed
      val remoteComment = remoteById[localId] ?: throw CliException("Local comment references unknown remote id $localId", 2)
      if (!localComment.minimizedReasonSet) return@forEachIndexed

      val desiredReason = localComment.minimizedReason?.trim()?.takeIf { it.isNotEmpty() }
      if (desiredReason != null && !AllowedMinimizeReasons.contains(desiredReason)) {
        throw CliException("Unsupported minimizedReason '$desiredReason' for comment $localId", 2)
      }

      val currentReason = remoteComment.minimizedReason?.trim()?.takeIf { it.isNotEmpty() }
      if (desiredReason == currentReason) return@forEachIndexed

      if (desiredReason == null) {
        visibilityOps += CommentVisibilityOp(commentId = localId, type = CommentVisibilityOpType.UNMINIMIZE, reason = null)
      } else {
        visibilityOps += CommentVisibilityOp(commentId = localId, type = CommentVisibilityOpType.MINIMIZE, reason = desiredReason)
      }
    }

    val appended = local.comments.drop(localIndex).mapIndexed { index, comment ->
      if (comment.id != null) {
        throw CliException("Existing comments with id must stay before appended comments", 2)
      }
      val body = comment.body?.trim()
      if (body.isNullOrEmpty()) {
        throw CliException("Appended comment at index ${localIndex + index} must include non-empty body", 2)
      }
      body
    }

    val resolution = when {
      !remote.isResolved && local.isResolved -> ResolutionOpType.RESOLVE
      remote.isResolved && !local.isResolved -> ResolutionOpType.UNRESOLVE
      else -> null
    }

    return ThreadDelta(
      deletedCommentIds = deletedIds,
      appendedBodies = appended,
      visibilityOps = visibilityOps,
      resolutionOp = resolution
    )
  }

  private fun ThreadCommentSnapshot.matchesRemote(remote: ThreadCommentSnapshot): Boolean {
    if (body != remote.body) return false
    if (id != null && id != remote.id) return false
    if (author != null && author != remote.author) return false
    if (createdAt != null && createdAt != remote.createdAt) return false
    if (updatedAt != null && updatedAt != remote.updatedAt) return false
    if (url != null && url != remote.url) return false
    return true
  }
}

private fun stringOrNull(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

private fun intOrNull(value: Int?) = value?.let(::JsonPrimitive) ?: JsonNull

private fun longOrNull(value: Long?) = value?.let(::JsonPrimitive) ?: JsonNull
