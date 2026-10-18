package dev.sebastiano.headroom.quota

import dev.sebastiano.headroom.model.Provider
import dev.sebastiano.headroom.model.QuotaErrorKind
import dev.sebastiano.headroom.model.QuotaResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GeminiQuotaFetcherTest {
    private val server = MockWebServer()
    private val fetcher = GeminiQuotaFetcher(KtorQuotaHttpClient(), FIXED_CLOCK)

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun credentials(baseUrl: String = server.baseUrl()) =
        ProviderCredentials(accessToken = "test-gemini-token", baseUrl = baseUrl)

    private fun enqueueBoth() {
        server.enqueueJson(fixture("gemini/load-code-assist.json"))
        server.enqueueJson(fixture("gemini/retrieve-user-quota.json"))
    }

    @Test
    fun `loads the Code Assist project, then reads its quota`() = runTest {
        enqueueBoth()

        fetcher.fetch(credentials())

        val load = server.takeRequest()
        assertEquals("POST", load.method)
        assertEquals("/v1internal:loadCodeAssist", load.target)
        assertEquals("Bearer test-gemini-token", load.headers["Authorization"])
        val metadata =
            Json.parseToJsonElement(checkNotNull(load.body?.utf8())).jsonObject.getValue("metadata")
        assertEquals("GEMINI", metadata.jsonObject.getValue("pluginType").jsonPrimitive.content)

        val quota = server.takeRequest()
        assertEquals("POST", quota.method)
        assertEquals("/v1internal:retrieveUserQuota", quota.target)
        assertEquals("Bearer test-gemini-token", quota.headers["Authorization"])
        assertEquals("""{"project":"test-project-123"}""", quota.body?.utf8())
    }

    @Test
    fun `maps each model's bucket to a window`() = runTest {
        enqueueBoth()

        val snapshot = assertIs<QuotaResult.Success>(fetcher.fetch(credentials())).snapshot

        assertEquals(Provider.Gemini, snapshot.provider)
        assertEquals("Google AI Pro", snapshot.planLabel)
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), snapshot.windows.map { it.id })
        assertEquals(listOf(25.0, 60.0), snapshot.windows.map { it.usedPercent })
        assertEquals(
            listOf(Instant.parse("2026-10-19T07:00:00Z"), Instant.parse("2026-10-19T07:00:00Z")),
            snapshot.windows.map { it.resetsAt },
        )
    }

    @Test
    fun `names the plan after the current tier without a paid tier`() = runTest {
        server.enqueueJson(
            """{"currentTier":{"name":"Gemini Code Assist for individuals"},"cloudaicompanionProject":"p"}"""
        )
        server.enqueueJson(fixture("gemini/retrieve-user-quota.json"))

        val snapshot = assertIs<QuotaResult.Success>(fetcher.fetch(credentials())).snapshot

        assertEquals("Gemini Code Assist for individuals", snapshot.planLabel)
    }

    @Test
    fun `maps an account without a Code Assist project to Access`() = runTest {
        server.enqueueJson("""{"currentTier":{"name":"Gemini Code Assist"}}""")

        val failure = assertIs<QuotaResult.Failure>(fetcher.fetch(credentials()))

        assertEquals(QuotaErrorKind.Access, failure.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `maps a quota without usable buckets to Parse`() = runTest {
        for (body in
            listOf(
                """{"buckets":[]}""",
                """{"buckets":[{"modelId":"gemini-2.5-pro"}]}""",
                """{"other":{}}""",
                "not-json",
            )) {
            server.enqueueJson(fixture("gemini/load-code-assist.json"))
            server.enqueueJson(body)

            val failure = assertIs<QuotaResult.Failure>(fetcher.fetch(credentials()))

            assertEquals(QuotaErrorKind.Parse, failure.kind, body)
        }
    }

    @Test
    fun `maps HTTP 401 on either call to Auth`() = runTest {
        server.enqueueStatus(401)
        val onLoad = assertIs<QuotaResult.Failure>(fetcher.fetch(credentials()))

        server.enqueueJson(fixture("gemini/load-code-assist.json"))
        server.enqueueStatus(401)
        val onQuota = assertIs<QuotaResult.Failure>(fetcher.fetch(credentials()))

        assertEquals(QuotaErrorKind.Auth, onLoad.kind)
        assertEquals(QuotaErrorKind.Auth, onQuota.kind)
        assertNull(onQuota.authReason)
    }

    @Test
    fun `maps transport failure to Network`() = runTest {
        val creds = credentials()
        server.close()

        val failure = assertIs<QuotaResult.Failure>(fetcher.fetch(creds))

        assertEquals(QuotaErrorKind.Network, failure.kind)
        assertTrue(failure.message.startsWith("Gemini"))
    }
}
