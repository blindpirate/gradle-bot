/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package org.gradle.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.reflect.ClassPath
import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.name.Names
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.Verticle
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.Json
import io.vertx.core.spi.VerticleFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.core.http.httpServerOptionsOf
import org.gradle.bot.client.GitHubClient
import org.gradle.bot.eventhandlers.github.GitHubEventHandler
import org.gradle.bot.model.GitHubEvent
import org.gradle.bot.webhookhandlers.GitHubWebHookHandler
import org.gradle.bot.webhookhandlers.TeamCityWebHookHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import javax.inject.Inject

val objectMapper: ObjectMapper = ObjectMapper()
val logger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)

fun main() {
    val vertx = Vertx.vertx()
    val injector = Guice.createInjector(GradleBotAppModule(vertx))
    registerEventHandlers(vertx, injector)
    vertx.exceptionHandler { logger.error("", it) }
    vertx.registerVerticleFactory(GuiceVerticleFactory(injector))
    vertx.deployVerticle(GradleBotVerticle::class.java.name)
}

class GuiceVerticleFactory(private val injector: Injector) : VerticleFactory {
    override fun createVerticle(verticleName: String?, classLoader: ClassLoader?, promise: Promise<Callable<Verticle>>?) {
        try {
            promise!!.complete(Callable {
                try {
                    injector.getInstance(GradleBotVerticle::class.java)
                } catch (e: Throwable) {
                    logger.error("", e)
                    throw e
                }
            })
        } catch (e: Throwable) {
            logger.error("", e)
            promise!!.fail(e)
        }
    }

    override fun prefix(): String = GradleBotVerticle::class.java.simpleName
}

@Suppress("UNCHECKED_CAST")
private fun registerEventHandlers(vertx: Vertx, injector: Injector) {
    val packageName = GradleBotVerticle::class.java.`package`.name
    ClassPath.from(GradleBotVerticle::class.java.classLoader).getTopLevelClassesRecursive(packageName).forEach {
        val klass = it.load()
        if (klass.isAssignableFrom(GitHubEventHandler::class.java) && klass != GitHubEventHandler::class.java) {
            val eventHandler: GitHubEventHandler<GitHubEvent> = injector.getInstance(klass) as GitHubEventHandler<GitHubEvent>
            vertx.eventBus().consumer<GitHubEvent>(eventHandler.eventType(), eventHandler)
        }
    }
}

class GradleBotAppModule(private val vertx: Vertx) : AbstractModule() {
    override fun configure() {
        bind(Vertx::class.java).toInstance(vertx)
        listOf("GITHUB_ACCESS_TOKEN", "GITHUB_WEBHOOK_SECRET", "TEAMCITY_ACCESS_TOKEN").forEach(this::bindEnv)
    }

    private fun bindEnv(envName: String) {
        val envValue = System.getenv(envName) ?: "" //throw IllegalStateException("Env $envName must be set!")
        bind(String::class.java).annotatedWith(Names.named(envName)).toInstance(envValue)
    }

}

class GradleBotVerticle @Inject constructor(private val gitHubWebHookHandler: GitHubWebHookHandler,
                                            private val teamCityWebHookHandler: TeamCityWebHookHandler,
                                            private val gitHubClient: GitHubClient) : AbstractVerticle() {
    private val logger: Logger = LoggerFactory.getLogger(GradleBotVerticle::class.java.name)
    private val port by lazy {
        System.getenv("HTTP_PORT")?.toInt() ?: 8080
    }

    override fun start(startFuture: Promise<Void>) {
        gitHubClient.init().onSuccess {
            logger.info("GitHub client initialized, I am {}", gitHubClient.whoAmI())

            val router = createRouter()

            val serverOptions = httpServerOptionsOf(port = port, ssl = false, compressionSupported = true)
            vertx.createHttpServer(serverOptions)
                    .requestHandler(router)
                    .listen { result ->
                        if (result.succeeded()) {
                            logger.info("App started.")
                            startFuture.complete()
                        } else {
                            logger.error("App failed to start.", result.cause())
                            startFuture.fail(result.cause())
                        }
                    }
        }
    }

    private fun createRouter() = Router.router(vertx).apply {
        route("/*").handler(BodyHandler.create())
        post("/github").handler(gitHubWebHookHandler)
        post("/teamcity").handler(teamCityWebHookHandler)
        errorHandler(500) { it?.failure()?.printStackTrace() }
    }

    /**
     * Extension to the HTTP response to output JSON objects.
     */

}

fun HttpServerResponse.endWithJson(obj: Any) {
    this.putHeader("Content-Type", "application/json; charset=utf-8").end(Json.encodePrettily(obj))
}
