package org.gradle.bot.eventhandlers.teamcity

import com.fasterxml.jackson.annotation.JsonProperty
import io.vertx.core.eventbus.Message
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import org.gradle.bot.client.GitHubClient
import org.gradle.bot.client.TeamCityClient
import org.gradle.bot.eventhandlers.WebHookEventHandler
import org.gradle.bot.eventhandlers.github.pullrequest.ciStatusContext
import org.gradle.bot.eventhandlers.github.pullrequest.ciStatusDesc
import org.gradle.bot.model.BuildStage
import org.gradle.bot.model.CommitStatusState
import org.gradle.bot.model.CommitStatusState.Companion.of
import org.gradle.bot.model.ListOpenPullRequestsResponse
import org.gradle.bot.objectMapper
import org.jetbrains.teamcity.rest.Build
import org.jetbrains.teamcity.rest.BuildStatus
import org.slf4j.LoggerFactory

interface TeamCityEventHandler : WebHookEventHandler {
    override val eventPrefix: String
        get() = "teamcity"
}

enum class BuildEventStatus {
    SUCCESS,
    FAILURE,
    RUNNING
}

/*
{
"text":" success - *Test Coverage - Quick Java8 Oracle Windows (pluginUse_2)* <https://builds.gradle.org/viewLog.html?buildTypeId=Gradle_Check_Quick_2_bucket30&buildId=33115638|#1503> (triggered by Snapshot dependency; Jendrik Johannes; Gradle / Check / Ready for Merge (Trigger))"
}
 */

val buildEventPattern = "(success|failure|running) - .*buildTypeId=(\\w+)&buildId=(\\d+)\\|.*".toRegex()

class TeamCityBuildEvent(@JsonProperty("text") val text: String) {
    val buildStatus: BuildEventStatus
    val buildTypeId: String
    val buildId: String

    init {
        val result: MatchResult = buildEventPattern.find(text)!!
        buildStatus = BuildEventStatus.valueOf(result.groupValues[1].toUpperCase())
        buildTypeId = result.groupValues[2]
        buildId = result.groupValues[3]
    }
}

abstract class AbstractTeamCityEventHandler : TeamCityEventHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val eventType: String
        get() = "build"

    override fun handle(event: Message<String>?) {
        try {
            handleEvent(objectMapper.readValue(event?.body(), TeamCityBuildEvent::class.java))
        } catch (e: Throwable) {
            logger.error(event?.body(), e)
        }
    }

    abstract fun handleEvent(event: TeamCityBuildEvent)
}

fun ListOpenPullRequestsResponse.Node.getHeadCommit() = headRef.target.oid

// 2018-11-26T22:23:45Z
fun ListOpenPullRequestsResponse.Node.lastCommittedDate() = ZonedDateTime.parse(commits.nodes[0].commit.committedDate)
fun ListOpenPullRequestsResponse.Node.isStale() = Duration.between(lastCommittedDate().toInstant(), Instant.now()).toDays() > 30
fun ListOpenPullRequestsResponse.Node.getTargetBranch() = baseRefName

/**
 * Upon Ready for Nightly builds finish, update all open pull request's head commit with the build status
 */
@Singleton
class UpdateCIStatusForAllOpenPullRequests @Inject constructor(
    private val gitHubClient: GitHubClient,
    private val teamCityClient: TeamCityClient
) : AbstractTeamCityEventHandler() {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val repoName = "gradle/gradle"
    private val acceptedStatus = listOf(BuildStatus.SUCCESS.toString(), BuildStatus.FAILURE.toString())
    private val acceptedBranches = listOf("master", "release")
    private val acceptedBuildTypeId = BuildStage.READY_FOR_NIGHTLY.buildTypeId
    override fun handleEvent(event: TeamCityBuildEvent) {
        if (!acceptedStatus.contains(event.buildStatus.toString()) ||
            event.buildTypeId != acceptedBuildTypeId) {
            logger.debug("Skip teamcity event {}", event.text)
            return
        }
        teamCityClient.findBuild(event.buildId).onSuccess { build ->
            if (build!!.branch.name !in acceptedBranches) {
                logger.debug("Skip teamcity build event on branch {}", build.branch.name)
                return@onSuccess
            }
            gitHubClient.listOpenPullRequests(repoName).onSuccess {
                it.data.repository.pullRequests.nodes.forEach { pr ->
                    updateCIStatusFor(build, pr)
                }
            }
        }
    }

    private fun updateCIStatusFor(build: Build, pr: ListOpenPullRequestsResponse.Node) {
        if (pr.isStale()) {
            logger.debug("Skip stale PR: {}", pr.url)
            return
        }
        val latestCIStatus: CommitStatusState? = pr.commits?.nodes?.get(0)?.commit?.status?.contexts
            ?.find { it.context == ciStatusContext }?.state?.let(::of)

        val targetStatus = of(build.status.toString())

        if (targetStatus != latestCIStatus && build.branch.name.equals(pr.getTargetBranch())) {
            logger.debug("Update CI status {} to {} {}", targetStatus, pr.url, pr.getHeadCommit())
            gitHubClient.createCommitStatus(
                repoName,
                pr.getHeadCommit(),
                targetStatus,
                build.getHomeUrl(),
                ciStatusDesc(build, targetStatus),
                ciStatusContext
            )
        }
    }
}
