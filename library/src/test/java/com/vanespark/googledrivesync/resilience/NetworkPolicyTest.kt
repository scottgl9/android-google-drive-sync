package com.vanespark.googledrivesync.resilience

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NetworkPolicyTest {

    @Nested
    inner class NetworkPolicyTests {

        @Test
        fun `all NetworkPolicy values are defined`() {
            val policies = NetworkPolicy.entries

            assertThat(policies).containsExactly(
                NetworkPolicy.ANY,
                NetworkPolicy.UNMETERED_ONLY,
                NetworkPolicy.WIFI_ONLY,
                NetworkPolicy.NOT_ROAMING
            )
        }

        @Test
        fun `ANY policy is least restrictive`() {
            assertThat(NetworkPolicy.ANY.name).isEqualTo("ANY")
        }

        @Test
        fun `UNMETERED_ONLY policy exists`() {
            assertThat(NetworkPolicy.UNMETERED_ONLY.name).isEqualTo("UNMETERED_ONLY")
        }

        @Test
        fun `WIFI_ONLY policy exists`() {
            assertThat(NetworkPolicy.WIFI_ONLY.name).isEqualTo("WIFI_ONLY")
        }

        @Test
        fun `NOT_ROAMING policy exists`() {
            assertThat(NetworkPolicy.NOT_ROAMING.name).isEqualTo("NOT_ROAMING")
        }
    }

    @Nested
    inner class RateLimitExceptionTests {

        @Test
        fun `RateLimitException contains retry after value`() {
            val exception = RateLimitException(
                message = "Rate limit exceeded",
                retryAfter = "60"
            )

            assertThat(exception.message).isEqualTo("Rate limit exceeded")
            assertThat(exception.retryAfter).isEqualTo("60")
        }

        @Test
        fun `RateLimitException can include cause`() {
            val cause = RuntimeException("API error")
            val exception = RateLimitException(
                message = "Rate limit",
                retryAfter = "30",
                cause = cause
            )

            assertThat(exception.cause).isEqualTo(cause)
        }

        @Test
        fun `RateLimitException getRetryDelay parses seconds`() {
            val exception = RateLimitException(
                message = "Rate limit",
                retryAfter = "120"
            )

            assertThat(exception.getRetryDelay().inWholeSeconds).isEqualTo(120)
        }

        @Test
        fun `RateLimitException getRetryDelay returns default for null`() {
            val exception = RateLimitException(
                message = "Rate limit",
                retryAfter = null
            )

            assertThat(exception.getRetryDelay().inWholeSeconds).isEqualTo(60)
        }
    }
}
