package com.alekpeed.hearsay.tools.desktop.capture

/**
 * The order of prompts and what has been answered.
 *
 * Kept free of any UI so the rule that decides whether a take counts can be tested directly. A take
 * is written only when it is accepted, which is what lets progress be read back off the file.
 */
class CaptureSession(
    items: List<CaptureItem>,
    private val store: CaptureStore,
) {

    private val all = items
    private val queue = ArrayDeque(items.filterNot { it.id in store.completedIds() })

    val total: Int get() = all.size
    val done: Int get() = all.size - queue.size
    val current: CaptureItem? get() = queue.firstOrNull()
    val finished: Boolean get() = queue.isEmpty()

    /**
     * Judges [attempt] against the current prompt, keeping it if it passes.
     *
     * Returns the verdict so the screen can say what was wrong. A rejected take is not written: the
     * corpus contains only what was verified, which is the entire point of prompting.
     */
    fun submit(attempt: ChordAttempt): Verdict {
        val item = current ?: return Verdict.Rejected("Nothing left to play.")
        val verdict = Verifier.verify(item, attempt)
        if (verdict is Verdict.Accepted) {
            store.append(item, attempt)
            queue.removeFirst()
        }
        return verdict
    }

    /** Sends the current prompt to the back rather than dropping it, so nothing is lost by moving on. */
    fun skip() {
        val item = queue.removeFirstOrNull() ?: return
        queue.addLast(item)
    }
}
