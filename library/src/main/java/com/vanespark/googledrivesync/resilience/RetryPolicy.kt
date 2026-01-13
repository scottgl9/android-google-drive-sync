package com.vanespark.googledrivesync.resilience

import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Extension to check if exception is rate limiting
private fun Throwable.isRateLimitException(): Boolean {
    return this is RateLimitException ||
        message?.contains("429") == true ||
        message?.lowercase()?.contains("rate limit") == true
}

/**
 * Configuration for retry behavior
 */
data class RetryPolicy(
    /**
     * Maximum number of retry attempts
     */
    val maxAttempts: Int = Constants.DEFAULT_MAX_RETRY_ATTEMPTS,

    /**
     * Initial delay before first retry
     */
    val initialDelay: Duration = Constants.DEFAULT_INITIAL_DELAY_MS.milliseconds,

    /**
     * Maximum delay between retries
     */
    val maxDelay: Duration = Constants.DEFAULT_MAX_DELAY_MS.milliseconds,

    /**
     * Multiplier for exponential backoff
     */
    val multiplier: Double = Constants.DEFAULT_BACKOFF_MULTIPLIER,

    /**
     * Exception types that should trigger retry
     */
    val retryableExceptions: Set<Class<out Throwable>> = DEFAULT_RETRYABLE_EXCEPTIONS,

    /**
     * Custom predicate for determining if an exception is retryable
     */
    val retryPredicate: ((Throwable) -> Boolean)? = null
) {
    companion object {
        /**
         * Default exceptions that trigger retry
         */
        val DEFAULT_RETRYABLE_EXCEPTIONS: Set<Class<out Throwable>> = setOf(
            IOException::class.java
        )

        /**
         * Default retry policy
         */
        val DEFAULT = RetryPolicy()

        /**
         * Aggressive retry policy for important operations
         */
        val AGGRESSIVE = RetryPolicy(
            maxAttempts = 5,
            initialDelay = 500.milliseconds,
            maxDelay = 60.seconds,
            multiplier = 2.0
        )

        /**
         * Minimal retry policy for quick operations
         */
        val MINIMAL = RetryPolicy(
            maxAttempts = 2,
            initialDelay = 100.milliseconds,
            maxDelay = 1.seconds,
            multiplier = 2.0
        )

        /**
         * No retry policy
         */
        val NONE = RetryPolicy(maxAttempts = 1)
    }

    /**
     * Check if an exception should trigger a retry
     */
    fun shouldRetry(exception: Throwable): Boolean {
        // Check custom predicate first
        retryPredicate?.let { return it(exception) }

        // Check if exception type is retryable
        if (retryableExceptions.any { it.isInstance(exception) }) {
            return true
        }

        // Check for common retryable error patterns in message
        val message = exception.message?.lowercase() ?: return false
        return RETRYABLE_PATTERNS.any { message.contains(it) }
    }

    /**
     * Calculate delay for a given attempt number
     */
    fun calculateDelay(attemptNumber: Int): Duration {
        val delayMs = initialDelay.inWholeMilliseconds *
            Math.pow(multiplier, (attemptNumber - 1).toDouble()).toLong()
        return delayMs.coerceAtMost(maxDelay.inWholeMilliseconds).milliseconds
    }
}

private val RETRYABLE_PATTERNS = listOf(
    "timeout",
    "connection",
    "network",
    "502",
    "503",
    "504",
    "429",
    "rate limit",
    "temporarily unavailable",
    "service unavailable"
)

/**
 * Execute an operation with retry logic
 *
 * @param policy The retry policy to use
 * @param operationName Name for logging
 * @param block The operation to execute
 * @return The result of the operation
 */
suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy.DEFAULT,
    operationName: String = "Operation",
    block: suspend () -> T
): T {
    var lastException: Throwable? = null
    var currentDelay = policy.initialDelay

    repeat(policy.maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            lastException = e

            val isLastAttempt = attempt == policy.maxAttempts - 1
            val shouldRetry = policy.shouldRetry(e) && !isLastAttempt

            if (shouldRetry) {
                // Check for rate limiting - use appropriate delay
                val delay = when {
                    e is RateLimitException -> {
                        e.getRetryDelay()
                    }
                    e.isRateLimitException() -> {
                        Constants.RATE_LIMIT_DELAY_MS.milliseconds
                    }
                    else -> {
                        policy.calculateDelay(attempt + 1)
                    }
                }

                Log.w(
                    Constants.TAG,
                    "$operationName failed (attempt ${attempt + 1}/${policy.maxAttempts}), " +
                        "retrying in ${delay.inWholeMilliseconds}ms: ${e.message}"
                )

                delay(delay.inWholeMilliseconds)
                currentDelay = (currentDelay.inWholeMilliseconds * policy.multiplier)
                    .toLong()
                    .coerceAtMost(policy.maxDelay.inWholeMilliseconds)
                    .milliseconds
            } else if (!policy.shouldRetry(e)) {
                Log.e(Constants.TAG, "$operationName failed with non-retryable error", e)
                throw e
            }
        }
    }

    Log.e(Constants.TAG, "$operationName failed after ${policy.maxAttempts} attempts")
    throw lastException ?: Exception("$operationName failed after ${policy.maxAttempts} attempts")
}

/**
 * Execute an operation with retry, returning Result
 */
suspend fun <T> withRetryResult(
    policy: RetryPolicy = RetryPolicy.DEFAULT,
    operationName: String = "Operation",
    block: suspend () -> T
): Result<T> {
    return try {
        Result.success(withRetry(policy, operationName, block))
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
