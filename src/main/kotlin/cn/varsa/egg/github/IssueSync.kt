package cn.varsa.egg.github

import cn.varsa.cli.core.CliException
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class IssueSnapshot(
  val id: String,
  val number: Int,
  val title: String,
  val body: String,
  val state: String,
  val stateReason: String?,
  val assignees: List<String>,
  val labels: List<String>,
  val milestoneNumber: Int?,
  val milestoneTitle: String?,
  val url: String?,
  val author: String?,
  val createdAt: String?,
  val updatedAt: String?,
  val closedAt: String?,
  val comments: List<IssueCommentSnapshot>,
  val parentIssueNumber: Int? = null
)

data class IssueCommentSnapshot(
  val id: String?,
  val author: String?,
  val body: String,
  val createdAt: String?,
  val updatedAt: String?,
  val url: String?
)

data class IssueDelta(
  val changedTitle: String?,
  val changedBody: String?,
  val changedState: String?,
  val changedStateReason: String?,
  val changedAssignees: List<String>?,
  val changedLabels: List<String>?,
  val changedMilestoneNumber: Int?,
  val milestoneChanged: Boolean,
  val appendedCommentBodies: List<String>
) {
  fun hasNoChanges(): Boolean =
    changedTitle == null &&
      changedBody == null &&
      changedState == null &&
      changedStateReason == null &&
      changedAssignees == null &&
      changedLabels == null &&
      !milestoneChanged &&
      appendedCommentBodies.isEmpty()
}

object IssueSyncFingerprint {
  fun compute(snapshot: IssueSnapshot): String {
    val canonical = JsonObject(
      mapOf(
        "id" to JsonPrimitive(snapshot.id),
        "number" to JsonPrimitive(snapshot.number),
        "title" to JsonPrimitive(snapshot.title),
        "body" to JsonPrimitive(snapshot.body),
        "state" to JsonPrimitive(snapshot.state),
        "stateReason" to stringOrNull(snapshot.stateReason),
        "assignees" to JsonArray(snapshot.assignees.sorted().map(::JsonPrimitive)),
        "labels" to JsonArray(snapshot.labels.sorted().map(::JsonPrimitive)),
        "milestoneNumber" to intOrNull(snapshot.milestoneNumber),
        "parentIssueNumber" to intOrNull(snapshot.parentIssueNumber)
      )
    ).toString()
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(canonical.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    return "sha256:$digest"
  }
}

object IssueDeltaDeriver {
  fun derive(remote: IssueSnapshot, local: IssueSnapshot): IssueDelta {
    if (remote.id != local.id) {
      throw CliException("issue.id must match remote issue id", 2)
    }
    if (remote.number != local.number) {
      throw CliException("issue.number must match remote issue number", 2)
    }
    if (local.parentIssueNumber != remote.parentIssueNumber) {
      throw CliException("Updating relationships.parentIssueNumber is not supported yet", 2)
    }

    val normalizedState = normalizeState(local.state)
    val normalizedStateReason = normalizeStateReason(local.stateReason)
    val normalizedAssignees = local.assignees.distinct().sorted()
    val normalizedLabels = local.labels.distinct().sorted()
    val appendedCommentBodies = deriveAppendedCommentBodies(remote.comments, local.comments)

    return IssueDelta(
      changedTitle = local.title.takeIf { it != remote.title },
      changedBody = local.body.takeIf { it != remote.body },
      changedState = normalizedState.takeIf { it != remote.state },
      changedStateReason = normalizedStateReason.takeIf { it != normalizeStateReason(remote.stateReason) },
      changedAssignees = normalizedAssignees.takeIf { it != remote.assignees.sorted() },
      changedLabels = normalizedLabels.takeIf { it != remote.labels.sorted() },
      changedMilestoneNumber = local.milestoneNumber,
      milestoneChanged = local.milestoneNumber != remote.milestoneNumber,
      appendedCommentBodies = appendedCommentBodies
    )
  }

  private fun deriveAppendedCommentBodies(
    remote: List<IssueCommentSnapshot>,
    local: List<IssueCommentSnapshot>
  ): List<String> {
    var localIndex = 0
    var remoteIndex = 0

    while (localIndex < local.size && remoteIndex < remote.size) {
      val localComment = local[localIndex]
      val localId = localComment.id
      if (localId == null) break

      var foundAt = remoteIndex
      while (foundAt < remote.size && remote[foundAt].id != localId) {
        foundAt++
      }
      if (foundAt >= remote.size) {
        throw CliException("Unknown issue comment id in local entity: $localId", 2)
      }

      val remoteComment = remote[foundAt]
      if (localComment.body != remoteComment.body) {
        throw CliException("Editing existing issue comments is not supported", 2)
      }

      remoteIndex = foundAt + 1
      localIndex++
    }

    val appendedBodies = mutableListOf<String>()
    var seenAppended = false
    for (idx in localIndex until local.size) {
      val comment = local[idx]
      if (comment.id != null) {
        if (seenAppended) {
          throw CliException("Existing issue comments must stay before appended comments", 2)
        }
        throw CliException("Reordering existing issue comments is not supported", 2)
      }
      seenAppended = true
      val body = comment.body.trim()
      if (body.isEmpty()) {
        throw CliException("Appended issue comments must include a non-empty body", 2)
      }
      appendedBodies += body
    }
    return appendedBodies
  }

  private fun normalizeState(value: String): String {
    val normalized = value.trim().uppercase()
    if (normalized != "OPEN" && normalized != "CLOSED") {
      throw CliException("issue.state must be OPEN or CLOSED", 2)
    }
    return normalized
  }

  private fun normalizeStateReason(value: String?): String? {
    if (value == null) return null
    val normalized = value.trim().uppercase().ifEmpty { return null }
    if (normalized != "COMPLETED" && normalized != "NOT_PLANNED" && normalized != "REOPENED") {
      throw CliException("issue.stateReason must be one of COMPLETED, NOT_PLANNED, REOPENED", 2)
    }
    return normalized
  }
}

private fun stringOrNull(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

private fun intOrNull(value: Int?) = value?.let(::JsonPrimitive) ?: JsonNull
