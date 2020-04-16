package org.gradle.bot.webhookhandlers

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import javax.inject.Inject
import javax.inject.Singleton
import org.gradle.bot.endWithJson
import org.gradle.bot.security.GithubSignatureChecker
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Singleton
class GitHubWebHookHandler @Inject constructor(
    private val vertx: Vertx,
    private val githubSignatureChecker: GithubSignatureChecker
) : Handler<RoutingContext> {
    private val logger = LoggerFactory.getLogger(javaClass)
    override fun handle(context: RoutingContext?) {
        logger.debug("Received webhook to ${GitHubWebHookHandler::class.java.simpleName}")

        context.parsePayloadEvent(githubSignatureChecker)?.apply {
            logger.debug("Start handling $this")
            vertx.eventBus().publish(this.first, this.second)
        } ?: logger.info("Received invalid GitHub webhook, discard.")

        context?.response()?.endWithJson(emptyMap<String, Any>())
    }
}

val logger: Logger = LoggerFactory.getLogger(RoutingContext::class.java)
private fun RoutingContext?.parsePayloadEvent(githubSignatureChecker: GithubSignatureChecker): Pair<String, String>? {
    return this?.let {
        val signature = request().getHeader("x-hub-signature")
        if (!githubSignatureChecker.verifySignature(bodyAsString, signature)) {
            logger.warn("Receive request {} with bad signature {}", bodyAsString, signature)
            return null
        }
    }?.let {
        logger.debug("Get GitHub webhook {}", bodyAsString)
        request().getHeader("X-GitHub-Event")
    }?.let {
        "github.$it" to bodyAsString
    }
}
