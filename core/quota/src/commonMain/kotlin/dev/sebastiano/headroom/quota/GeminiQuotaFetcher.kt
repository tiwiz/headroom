package dev.sebastiano.headroom.quota

import dev.sebastiano.headroom.model.Provider
import dev.sebastiano.headroom.model.QuotaErrorKind
import dev.sebastiano.headroom.model.QuotaResult
import dev.sebastiano.headroom.model.QuotaSnapshot
import dev.sebastiano.headroom.model.QuotaWindow
import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Reads Gemini subscription limits from the Code Assist API, with the same two calls the Gemini CLI
 * makes for its quota:
 * 1. `v1internal:loadCodeAssist` names the account's Code Assist project and its tier.
 * 2. `v1internal:retrieveUserQuota` returns one bucket per model, with the share left and when it
 *    resets.
 *
 * An account with no Code Assist project has no quota to read: that is reported as
 * [QuotaErrorKind.Access]. The buckets do not say how long their window is, so the windows have no
 * length.
 */
public class GeminiQuotaFetcher(
    private val httpClient: QuotaHttpClient,
    private val clock: Clock = Clock.System,
) : QuotaFetcher {
    override val provider: Provider = Provider.Gemini

    override suspend fun fetch(credentials: ProviderCredentials): QuotaResult {
        val baseUrl = resolveBaseUrl(credentials.baseUrl, DEFAULT_BASE_URL)
        val headers =
            mapOf(
                "Authorization" to bearer(credentials.accessToken),
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            )
        val load =
            when (val outcome = post(baseUrl, LOAD_CODE_ASSIST, headers, LOAD_BODY)) {
                is HttpOutcome.Ok -> outcome.body
                is HttpOutcome.Failed -> return outcome.failure
            }
        val loadRoot =
            parseOrNull { quotaJson.parseToJsonElement(load).jsonObject }
                ?: return parseFailure(PROVIDER_NAME)
        val project =
            loadRoot.nonBlankStringOrNull("cloudaicompanionProject")
                ?: return QuotaResult.Failure(
                    QuotaErrorKind.Access,
                    "$PROVIDER_NAME usage fetch failed: no Code Assist project",
                )
        val quotaBody = buildJsonObject { put("project", project) }.toString()
        val quota =
            when (val outcome = post(baseUrl, RETRIEVE_USER_QUOTA, headers, quotaBody)) {
                is HttpOutcome.Ok -> outcome.body
                is HttpOutcome.Failed -> return outcome.failure
            }
        val windows =
            parseOrNull { parseWindows(quotaJson.parseToJsonElement(quota).jsonObject) }
                ?: return parseFailure(PROVIDER_NAME)
        if (windows.isEmpty()) return parseFailure(PROVIDER_NAME)
        return QuotaResult.Success(
            QuotaSnapshot(
                provider = provider,
                accountId = credentials.accountId.orEmpty(),
                planLabel = planLabel(loadRoot),
                windows = windows,
                fetchedAt = clock.now(),
            )
        )
    }

    private suspend fun post(
        baseUrl: String,
        method: String,
        headers: Map<String, String>,
        body: String,
    ): HttpOutcome =
        httpClient.fetchBody(
            QuotaHttpRequest(
                url = "$baseUrl$API_PATH:$method",
                method = "POST",
                headers = headers,
                body = body,
            ),
            PROVIDER_NAME,
        )

    /** The paid tier's name (such as Google AI Pro) when there is one, or the current tier's. */
    private fun planLabel(root: JsonObject): String? =
        root.objectOrNull("paidTier")?.nonBlankStringOrNull("name")
            ?: root.objectOrNull("currentTier")?.nonBlankStringOrNull("name")

    /** One window per model; like the CLI, buckets without a model or a share left are skipped. */
    private fun parseWindows(root: JsonObject): List<QuotaWindow> =
        root
            .arrayOrNull("buckets")
            .orEmpty()
            .mapNotNull { element ->
                val bucket = element as? JsonObject ?: return@mapNotNull null
                val modelId = bucket.nonBlankStringOrNull("modelId") ?: return@mapNotNull null
                val remaining = bucket.doubleOrNull("remainingFraction") ?: return@mapNotNull null
                quotaWindow(
                    id = modelId,
                    label = modelId,
                    usedPercent =
                        ((1.0 - remaining) * MAX_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT),
                    resetsAt = bucket.nonBlankStringOrNull("resetTime")?.let(::parseInstantOrNull),
                    length = null,
                )
            }
            .distinctBy { it.id }

    private companion object {
        const val PROVIDER_NAME = "Gemini"
        const val DEFAULT_BASE_URL = "https://cloudcode-pa.googleapis.com"
        const val API_PATH = "/v1internal"
        const val LOAD_CODE_ASSIST = "loadCodeAssist"
        const val RETRIEVE_USER_QUOTA = "retrieveUserQuota"

        /** The client metadata the CLI sends; Code Assist needs it to find the project. */
        val LOAD_BODY: String = buildJsonObject {
            putJsonObject("metadata") {
                put("ideType", "IDE_UNSPECIFIED")
                put("platform", "PLATFORM_UNSPECIFIED")
                put("pluginType", "GEMINI")
            }
        }
            .toString()
    }
}
