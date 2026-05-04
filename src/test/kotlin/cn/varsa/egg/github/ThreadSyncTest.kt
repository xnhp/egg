package cn.varsa.egg.github

import cn.varsa.cli.core.CliException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ThreadSyncTest {
  @Test
  fun `fingerprint is stable for same snapshot`() {
    val snapshot = baseThread()

    val first = ThreadSyncFingerprint.compute(snapshot)
    val second = ThreadSyncFingerprint.compute(snapshot.copy())

    assertEquals(first, second)
    assertEquals(true, first.startsWith("sha256:"))
  }

  @Test
  fun `fingerprint changes when resolution changes`() {
    val baseline = ThreadSyncFingerprint.compute(baseThread(isResolved = false))
    val changed = ThreadSyncFingerprint.compute(baseThread(isResolved = true))

    assertNotEquals(baseline, changed)
  }

  @Test
  fun `delta derive append and resolve`() {
    val remote = baseThread(isResolved = false)
    val local = remote.copy(
      isResolved = true,
      comments = remote.comments + ThreadCommentSnapshot(null, null, "new reply", null, null, null)
    )

    val delta = ThreadDeltaDeriver.derive(remote, local)

    assertEquals(emptyList(), delta.deletedCommentIds)
    assertEquals(listOf("new reply"), delta.appendedBodies)
    assertEquals(emptyList(), delta.visibilityOps)
    assertEquals(ResolutionOpType.RESOLVE, delta.resolutionOp)
  }

  @Test
  fun `delta derive unresolve and noop`() {
    val remote = baseThread(isResolved = true)
    val local = remote.copy(isResolved = false)
    val delta = ThreadDeltaDeriver.derive(remote, local)

    assertEquals(emptyList(), delta.deletedCommentIds)
    assertEquals(emptyList(), delta.appendedBodies)
    assertEquals(emptyList(), delta.visibilityOps)
    assertEquals(ResolutionOpType.UNRESOLVE, delta.resolutionOp)

    val noop = ThreadDeltaDeriver.derive(remote, remote)
    assertEquals(emptyList(), noop.deletedCommentIds)
    assertEquals(emptyList(), noop.appendedBodies)
    assertEquals(emptyList(), noop.visibilityOps)
    assertEquals(null, noop.resolutionOp)
  }

  @Test
  fun `delta derive treats missing local metadata as equivalent for existing comments`() {
    val remote = baseThread(
      comments = listOf(
        ThreadCommentSnapshot(
          id = 101,
          author = "alice",
          body = "root",
          createdAt = "2026-01-01T00:00:00Z",
          updatedAt = "2026-01-01T00:00:00Z",
          url = "https://example.test/c/101"
        )
      )
    )
    val local = remote.copy(
      comments = listOf(
        ThreadCommentSnapshot(
          id = null,
          author = null,
          body = "root",
          createdAt = null,
          updatedAt = null,
          url = null
        )
      )
    )

    val delta = ThreadDeltaDeriver.derive(remote, local)

    assertEquals(emptyList(), delta.deletedCommentIds)
    assertEquals(emptyList(), delta.appendedBodies)
    assertEquals(emptyList(), delta.visibilityOps)
    assertEquals(null, delta.resolutionOp)
  }

  @Test
  fun `delta derive rejects modification of existing comment`() {
    val remote = baseThread()
    val local = remote.copy(
      comments = listOf(remote.comments.first().copy(body = "changed"))
    )

    val error = assertFailsWith<CliException> { ThreadDeltaDeriver.derive(remote, local) }
    assertEquals(2, error.exitCode)
  }

  @Test
  fun `delta derive supports deletion of existing comment by id`() {
    val remote = baseThread(
      comments = listOf(
        ThreadCommentSnapshot(1, "alice", "a", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null),
        ThreadCommentSnapshot(2, "bob", "b", "2026-01-01T00:01:00Z", "2026-01-01T00:01:00Z", null)
      )
    )
    val local = remote.copy(comments = listOf(remote.comments.first()))

    val delta = ThreadDeltaDeriver.derive(remote, local)

    assertEquals(listOf(2L), delta.deletedCommentIds)
    assertEquals(emptyList(), delta.appendedBodies)
    assertEquals(emptyList(), delta.visibilityOps)
    assertEquals(null, delta.resolutionOp)
  }

  @Test
  fun `delta derive supports minimize and unminimize operations`() {
    val remote = baseThread(
      comments = listOf(
        ThreadCommentSnapshot(
          id = 1,
          author = "alice",
          body = "a",
          createdAt = "2026-01-01T00:00:00Z",
          updatedAt = "2026-01-01T00:00:00Z",
          url = null,
          minimizedReason = null,
          minimizedReasonSet = true,
          nodeId = "NODE_1"
        ),
        ThreadCommentSnapshot(
          id = 2,
          author = "bob",
          body = "b",
          createdAt = "2026-01-01T00:01:00Z",
          updatedAt = "2026-01-01T00:01:00Z",
          url = null,
          minimizedReason = "OUTDATED",
          minimizedReasonSet = true,
          nodeId = "NODE_2"
        )
      )
    )
    val local = remote.copy(
      comments = listOf(
        remote.comments[0].copy(minimizedReason = "OFF_TOPIC", minimizedReasonSet = true),
        remote.comments[1].copy(minimizedReason = null, minimizedReasonSet = true)
      )
    )

    val delta = ThreadDeltaDeriver.derive(remote, local)

    assertEquals(emptyList(), delta.deletedCommentIds)
    assertEquals(emptyList(), delta.appendedBodies)
    assertEquals(
      listOf(
        CommentVisibilityOp(commentId = 1, type = CommentVisibilityOpType.MINIMIZE, reason = "OFF_TOPIC"),
        CommentVisibilityOp(commentId = 2, type = CommentVisibilityOpType.UNMINIMIZE, reason = null)
      ),
      delta.visibilityOps
    )
    assertEquals(null, delta.resolutionOp)
  }

  @Test
  fun `delta derive rejects reordering of existing comments`() {
    val remote = baseThread(
      comments = listOf(
        ThreadCommentSnapshot(1, "alice", "a", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null),
        ThreadCommentSnapshot(2, "bob", "b", "2026-01-01T00:01:00Z", "2026-01-01T00:01:00Z", null)
      )
    )
    val local = remote.copy(comments = remote.comments.reversed())

    val error = assertFailsWith<CliException> { ThreadDeltaDeriver.derive(remote, local) }
    assertEquals(2, error.exitCode)
  }

  private fun baseThread(
    isResolved: Boolean = false,
    comments: List<ThreadCommentSnapshot> = listOf(
      ThreadCommentSnapshot(
        id = 101,
        author = "alice",
        body = "root",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        url = "https://example.test/c/101"
      )
    )
  ) = ThreadSnapshot(
    id = "THREAD_1",
    isResolved = isResolved,
    isOutdated = false,
    path = "src/Foo.kt",
    line = 42,
    comments = comments
  )
}
