package org.gradle.bot.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "unused")
class DefaultGithubSignatureCheckerTest {
    private val payload = javaClass.classLoader.getResource("github_payload.json").readText().trim()

    @ParameterizedTest(name = "signature with {0} returns {3}")
    @CsvSource(value = [
        "correct value, sha1=f7ceac8e913abc39bd7e318955c67db290e64f8b, hello,      true",
        "correct value, sha1=a4847957ad54fd147c8a13c9da234b4727165018, gradle-bot, true",
        "fake secret,   sha1=a4847957ad54fd147c8a13c9da234b4727165018, fakeKey,    false",
        "fake sha1,     sha1=iamfakesha1aaaaaaaaaaaaaaaaaaaaaaaaaaaaa, gradle-bot, false",
        "null signature,                                             , gradle-bot, false"
    ])
    @Suppress("UNUSED_PARAMETER")
    fun testVerifySignature(desc: String, signature: String?, secret: String, expected: Boolean) {
        val githubSignatureChecker = Sha1GitHubSignatureChecker(secret)
        assertEquals(expected, githubSignatureChecker.verifySignature(payload, signature))
    }
}
