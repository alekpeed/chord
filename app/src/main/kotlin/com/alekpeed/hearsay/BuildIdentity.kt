package com.alekpeed.hearsay

/**
 * Which build this is, in one line.
 *
 * A screenshot or a report of what the app did has to be attributable to a commit. Without that, a
 * fix that is working cannot be told apart from a fix that was never installed — which is exactly
 * how a session was spent on a tempo the running APK did not yet contain the fix for.
 *
 * Shown on the front page rather than only in Settings, so it is on screen without being looked
 * for. The build number and the commit are both here because they answer different questions: the
 * build number orders two installs, the commit says what is in them.
 */
object BuildIdentity {

    /** The line the user sees. */
    val label: String = format(
        versionName = BuildConfig.VERSION_NAME,
        buildNumber = BuildConfig.BUILD_NUMBER,
        gitSha = BuildConfig.GIT_SHA,
    )

    /**
     * Kept apart from [label] so the wording can be tested without a generated `BuildConfig`, which
     * a unit test does not have.
     */
    fun format(versionName: String, buildNumber: Int, gitSha: String): String =
        "Hearsay $versionName · build $buildNumber · $gitSha"
}
