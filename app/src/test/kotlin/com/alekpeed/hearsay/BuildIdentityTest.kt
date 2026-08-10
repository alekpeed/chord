package com.alekpeed.hearsay

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version line is only worth showing if it carries both identifiers. A build number alone
 * orders two installs without saying what is in them; a commit alone does not say which install is
 * newer. A test rather than an eye check because the line is easy to shorten by accident and the
 * cost of losing it is paid much later, in a session spent measuring the wrong build.
 */
class BuildIdentityTest {

    @Test
    fun `carries the version, the build number and the commit`() {
        val label = BuildIdentity.format(versionName = "0.1.0", buildNumber = 44, gitSha = "3e92703")

        assertTrue(label, label.contains("0.1.0"))
        assertTrue(label, label.contains("44"))
        assertTrue(label, label.contains("3e92703"))
    }

    @Test
    fun `says which build number is which, so an unset one is visible as 1`() {
        // The Gradle default when HEARSAY_VERSION_CODE is unset, i.e. a local build rather than CI.
        val local = BuildIdentity.format(versionName = "0.1.0", buildNumber = 1, gitSha = "abc1234")

        assertTrue(local, local.contains("build 1"))
    }
}
