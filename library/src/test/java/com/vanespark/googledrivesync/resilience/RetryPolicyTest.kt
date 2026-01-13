package com.vanespark.googledrivesync.resilience

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

    @BeforeEach
    fun setup() {
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    inner class PolicyPresetsTests {

        @Test
        fun `DEFAULT policy has expected values`() {
            val policy = RetryPolicy.DEFAULT

            assertThat(policy.maxAttempts).isEqualTo(3)
            assertThat(policy.multiplier).isEqualTo(2.0)
        }

        @Test
        fun `AGGRESSIVE policy has more attempts`() {
            val policy = RetryPolicy.AGGRESSIVE

            assertThat(policy.maxAttempts).isEqualTo(5)
            assertThat(policy.initialDelay).isEqualTo(500.milliseconds)
            assertThat(policy.maxDelay).isEqualTo(60.seconds)
        }

        @Test
        fun `MINIMAL policy has fewer attempts`() {
            val policy = RetryPolicy.MINIMAL

            assertThat(policy.maxAttempts).isEqualTo(2)
            assertThat(policy.initialDelay).isEqualTo(100.milliseconds)
            assertThat(policy.maxDelay).isEqualTo(1.seconds)
        }

        @Test
        fun `NONE policy has single attempt`() {
            val policy = RetryPolicy.NONE

            assertThat(policy.maxAttempts).isEqualTo(1)
        }
    }

    @Nested
    inner class ShouldRetryTests {

        @Test
        fun `shouldRetry returns true for IOException`() {
            val policy = RetryPolicy.DEFAULT

            assertThat(policy.shouldRetry(IOException("Network error"))).isTrue()
        }

        @Test
        fun `shouldRetry returns true for SocketTimeoutException`() {
            val policy = RetryPolicy.DEFAULT

            assertThat(policy.shouldRetry(SocketTimeoutException("Timeout"))).isTrue()
        }

        @Test
        fun `shouldRetry returns true for timeout message`() {
            val policy = RetryPolicy.DEFAULT

            assertThat(policy.shouldRetry(Exception("Connection timeout"))).isTrue()
        }

        @Test
        fun `shouldRetry returns true for 503 status`() {
            val policy = RetryPolicy.DEFAULT

            assertThat(policy.shouldRetry(Exception("HTTP 503 Service Unavailable"))).isTrue()
        }

        @Test
        fun `shouldRetry returns true for rate limit error`() {
            val policy = RetryPolicy.DEFAULT

            assertThat(policy.shouldRetry(Exception("429 Rate limit exceeded"))).isTrue()
        }

        @Test
        fun `shouldRetry returns false for IllegalArgumentException`() {
            val policy = RetryPolicy.DEFAULT

            assertThat(policy.shouldRetry(IllegalArgumentException("Bad argument"))).isFalse()
        }

        @Test
        fun `shouldRetry uses custom predicate when set`() {
            val policy = RetryPolicy(
                retryPredicate = { it.message?.contains("custom") == true }
            )

            assertThat(policy.shouldRetry(Exception("custom error"))).isTrue()
            assertThat(policy.shouldRetry(Exception("other error"))).isFalse()
        }

        @Test
        fun `custom predicate overrides default behavior`() {
            val policy = RetryPolicy(
                retryPredicate = { false } // Never retry
            )

            assertThat(policy.shouldRetry(IOException("Should not retry"))).isFalse()
        }
    }

    @Nested
    inner class CalculateDelayTests {

        @Test
        fun `calculateDelay returns initialDelay for first attempt`() {
            val policy = RetryPolicy(
                initialDelay = 100.milliseconds,
                multiplier = 2.0
            )

            val delay = policy.calculateDelay(1)

            assertThat(delay).isEqualTo(100.milliseconds)
        }

        @Test
        fun `calculateDelay applies multiplier for subsequent attempts`() {
            val policy = RetryPolicy(
                initialDelay = 100.milliseconds,
                multiplier = 2.0,
                maxDelay = 10.seconds
            )

            assertThat(policy.calculateDelay(1)).isEqualTo(100.milliseconds)
            assertThat(policy.calculateDelay(2)).isEqualTo(200.milliseconds)
            assertThat(policy.calculateDelay(3)).isEqualTo(400.milliseconds)
            assertThat(policy.calculateDelay(4)).isEqualTo(800.milliseconds)
        }

        @Test
        fun `calculateDelay respects maxDelay`() {
            val policy = RetryPolicy(
                initialDelay = 1.seconds,
                multiplier = 10.0,
                maxDelay = 5.seconds
            )

            val delay = policy.calculateDelay(5)

            assertThat(delay).isEqualTo(5.seconds)
        }
    }

    @Nested
    inner class WithRetryTests {

        @Test
        fun `withRetry returns result on first success`() = runTest {
            var attempts = 0

            val result = withRetry(RetryPolicy.DEFAULT) {
                attempts++
                "success"
            }

            assertThat(result).isEqualTo("success")
            assertThat(attempts).isEqualTo(1)
        }

        @Test
        fun `withRetry retries on retryable exception`() = runTest {
            var attempts = 0
            val policy = RetryPolicy(
                maxAttempts = 3,
                initialDelay = 1.milliseconds,
                maxDelay = 10.milliseconds
            )

            val result = withRetry(policy) {
                attempts++
                if (attempts < 3) throw IOException("Temporary error")
                "success"
            }

            assertThat(result).isEqualTo("success")
            assertThat(attempts).isEqualTo(3)
        }

        @Test
        fun `withRetry throws immediately for non-retryable exception`() = runTest {
            var attempts = 0
            val policy = RetryPolicy(
                maxAttempts = 3,
                initialDelay = 1.milliseconds
            )

            assertThrows<IllegalArgumentException> {
                withRetry(policy) {
                    attempts++
                    throw IllegalArgumentException("Bad input")
                }
            }

            assertThat(attempts).isEqualTo(1)
        }

        @Test
        fun `withRetry throws after max attempts exceeded`() = runTest {
            var attempts = 0
            val policy = RetryPolicy(
                maxAttempts = 3,
                initialDelay = 1.milliseconds,
                maxDelay = 10.milliseconds
            )

            assertThrows<IOException> {
                withRetry(policy) {
                    attempts++
                    throw IOException("Persistent error")
                }
            }

            assertThat(attempts).isEqualTo(3)
        }

        @Test
        fun `withRetry NONE policy does not retry`() = runTest {
            var attempts = 0

            assertThrows<IOException> {
                withRetry(RetryPolicy.NONE) {
                    attempts++
                    throw IOException("Error")
                }
            }

            assertThat(attempts).isEqualTo(1)
        }
    }

    @Nested
    inner class WithRetryResultTests {

        @Test
        fun `withRetryResult returns success Result on success`() = runTest {
            val result = withRetryResult(RetryPolicy.DEFAULT) {
                "success"
            }

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo("success")
        }

        @Test
        fun `withRetryResult returns failure Result on final failure`() = runTest {
            val policy = RetryPolicy(
                maxAttempts = 2,
                initialDelay = 1.milliseconds
            )

            val result = withRetryResult(policy) {
                throw IOException("Error")
            }

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        }
    }
}
