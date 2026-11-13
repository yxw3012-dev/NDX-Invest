package com.ndxinvest.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NetworkContractsTest {
    @Test
    fun `Provider requests require clean HTTPS endpoints and non-credential query configuration`() {
        ProviderRequest(
            endpoint = "HTTPS://example.invalid/data",
            query = mapOf("symbol" to ".NDX"),
        )

        listOf(
            "http://example.invalid/data",
            "https://user@example.invalid/data",
            "https://example.invalid/data?symbol=.NDX",
            "https://example.invalid/data#fragment",
            "relative/data",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException> { ProviderRequest(endpoint) }
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest("https://example.invalid/data", query = mapOf("access_token" to "inline"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest("https://example.invalid/data", query = mapOf("symbol" to "line\nbreak"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest("https://example.invalid/data", query = mapOf("symbol" to "tab\tvalue"))
        }
    }

    @Test
    fun `static request headers allow public routing metadata but reject secrets controls and collisions`() {
        ProviderRequest(
            endpoint = "https://example.invalid/data",
            headers = mapOf("Referer" to "https://example.invalid/finance/"),
        )

        listOf("Authorization", "X-Api-Key", "Cookie").forEach { name ->
            assertFailsWith<IllegalArgumentException> {
                ProviderRequest("https://example.invalid/data", headers = mapOf(name to "inline"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest("https://example.invalid/data", headers = mapOf("Bad Header" to "value"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest("https://example.invalid/data", headers = mapOf("Referer" to "line\nbreak"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest(
                endpoint = "https://example.invalid/data",
                headers = mapOf("X-Public-Route" to "ordinary"),
                credential = CredentialBinding(
                    CredentialReference("secure-store:provider"),
                    CredentialLocation.HEADER,
                    "x-public-route",
                ),
            )
        }
    }

    @Test
    fun `credential binding validates its location and cannot duplicate a configured query`() {
        val reference = CredentialReference("secure-store:provider:sample")
        CredentialBinding(reference, CredentialLocation.HEADER, "Authorization")
        CredentialBinding(reference, CredentialLocation.QUERY, "token")
        CredentialBinding(reference, CredentialLocation.JSON_BODY, "token")

        assertFailsWith<IllegalArgumentException> {
            CredentialBinding(reference, CredentialLocation.HEADER, "Bad Header")
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest(
                endpoint = "https://example.invalid/data",
                query = mapOf("token" to "ordinary-value"),
                credential = CredentialBinding(reference, CredentialLocation.QUERY, "token"),
            )
        }
    }

    @Test
    fun `JSON POST accepts structured non-secret fields and reserves credential-like keys for runtime binding`() {
        val request = ProviderRequest(
            endpoint = "https://api.example.invalid/market/snapshot",
            method = ProviderHttpMethod.POST_JSON,
            jsonBody = mapOf(
                "date" to ProviderJsonValue.Text("2026-09-11"),
                "symbols" to ProviderJsonValue.TextArray(listOf(".NDX")),
                "fields" to ProviderJsonValue.TextArray(
                    listOf("close", "changeRate"),
                ),
            ),
            credential = CredentialBinding(
                CredentialReference("secure-store:provider:sample"),
                CredentialLocation.JSON_BODY,
                "token",
            ),
        )

        assertEquals(ProviderHttpMethod.POST_JSON, request.method)
        assertFalse(request.toString().contains("user-entered-value"))
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest(
                endpoint = "https://example.invalid/data",
                method = ProviderHttpMethod.POST_JSON,
                jsonBody = mapOf("token" to ProviderJsonValue.Text("inline")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest(
                endpoint = "https://example.invalid/data",
                method = ProviderHttpMethod.GET,
                jsonBody = mapOf("date" to ProviderJsonValue.Text("2026-09-11")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest(
                endpoint = "https://example.invalid/data",
                method = ProviderHttpMethod.POST_JSON,
                jsonBody = mapOf("date" to ProviderJsonValue.Text("2026-09-11")),
                credential = CredentialBinding(
                    CredentialReference("secure-store:provider:sample"),
                    CredentialLocation.JSON_BODY,
                    "date",
                ),
            )
        }
    }

    @Test
    fun `resolved credentials are redacted and become inaccessible after clearing`() {
        val credential = ResolvedCredential.from(charArrayOf('v', 'a', 'l', 'u', 'e'))

        assertEquals("[REDACTED]", credential.toString())
        assertEquals("value", credential.reveal())
        credential.close()

        assertFailsWith<IllegalStateException> { credential.reveal() }
        assertFailsWith<IllegalArgumentException> { ResolvedCredential.from(charArrayOf('a', '\t', 'b')) }
    }
}
