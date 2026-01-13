package com.vanespark.googledrivesync.resilience

import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Handles rate limiting (HTTP 429) for API requests.
 *
 * Features:
 * - Parses Retry-After headers (seconds or HTTP date format)
 * - Tracks global rate limit state
 * - Provides configurable backoff strategies
 * - Supports quota-based limiting
 *
 * Usage:
 * ```kotlin
 * // Check before making request
 * if (rateLimitHandler.isRateLimited()) {
 *     val waitTime = rateLimitHandler.getWaitTime()
 *     // Show user message or wait
 * }
 *
 * // Handle 429 response
 * rateLimitHandler.handleRateLimitResponse(
 *     retryAfterHeader = response.header("Retry-After")
 * )
 *
 * // Execute with rate limit awareness
 * rateLimitHandler.executeWithRateLimiting {
 *     driveApi.listFiles()
 * }
 * ```
 */
@Singleton
class RateLimitHandler @Inject constructor() {

    companion object {
        private const val TAG = "${Constants.TAG}/RateLimit"

        // Delay bounds
        private val MIN_RATE_LIMIT_DELAY = 1.seconds
        private val MAX_RATE_LIMIT_DELAY = 300.seconds // 5 minutes
    }

    // Rate limit state
    private val _isRateLimited = MutableStateFlow(false)
    val isRateLimited: StateFlow<Boolean> = _isRateLimited.asStateFlow()

    private val _rateLimitUntil = MutableStateFlow<Long?>(null)
    val rateLimitUntil: StateFlow<Long?> = _rateLimitUntil.asStateFlow()

    // Request tracking for preemptive rate limiting
    private var requestTimestamps = mutableListOf<Long>()
    private val requestLock = Any()

    /**
     * Current rate limit configuration
     */
    var config: RateLimitConfig = RateLimitConfig()
        private set

    /**
     * Configure rate limiting behavior
     */
    fun configure(config: RateLimitConfig) {
        this.config = config
    }

    /**
     * Check if currently rate limited
     */
    fun isCurrentlyLimited(): Boolean {
        val until = _rateLimitUntil.value ?: return false
        val isLimited = System.currentTimeMillis() < until
        if (!isLimited) {
            clearRateLimit()
        }
        return isLimited
    }

    /**
     * Get remaining wait time in milliseconds
     */
    fun getWaitTimeMs(): Long {
        val until = _rateLimitUntil.value ?: return 0
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    /**
     * Get remaining wait time as Duration
     */
    fun getWaitTime(): Duration = getWaitTimeMs().milliseconds

    /**
     * Handle a rate limit response (HTTP 429)
     *
     * @param retryAfterHeader Value of Retry-After header (optional)
     */
    fun handleRateLimitResponse(
        retryAfterHeader: String? = null
    ) {
        val delay = parseRetryAfter(retryAfterHeader)
        val limitUntil = System.currentTimeMillis() + delay.inWholeMilliseconds

        _isRateLimited.value = true
        _rateLimitUntil.value = limitUntil

        Log.w(TAG, "Rate limited until ${java.util.Date(limitUntil)} (${delay.inWholeSeconds}s)")
    }

    /**
     * Handle a rate limit exception
     */
    fun handleRateLimitException(exception: RateLimitException) {
        handleRateLimitResponse(exception.retryAfter)
    }

    /**
     * Clear rate limit state
     */
    fun clearRateLimit() {
        _isRateLimited.value = false
        _rateLimitUntil.value = null
    }

    /**
     * Parse Retry-After header value
     *
     * Supports both formats:
     * - Seconds: "120"
     * - HTTP date: "Wed, 21 Oct 2015 07:28:00 GMT"
     */
    fun parseRetryAfter(header: String?): Duration {
        if (header == null) {
            return config.defaultDelay
        }

        // Try parsing as seconds
        header.toLongOrNull()?.let { seconds ->
            return seconds.seconds.coerceIn(MIN_RATE_LIMIT_DELAY, MAX_RATE_LIMIT_DELAY)
        }

        // Try parsing as HTTP date
        try {
            val date = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            val delayMs = date.toInstant().toEpochMilli() - System.currentTimeMillis()
            return delayMs.coerceAtLeast(0).milliseconds.coerceIn(MIN_RATE_LIMIT_DELAY, MAX_RATE_LIMIT_DELAY)
        } catch (e: DateTimeParseException) {
            Log.w(TAG, "Could not parse Retry-After header: $header")
        }

        return config.defaultDelay
    }

    /**
     * Track a request for preemptive rate limiting
     */
    fun trackRequest() {
        synchronized(requestLock) {
            val now = System.currentTimeMillis()
            requestTimestamps.add(now)

            // Clean old timestamps (older than 1 minute)
            requestTimestamps.removeAll { it < now - 60_000 }
        }
    }

    /**
     * Check if we're approaching rate limits
     */
    fun isApproachingLimit(): Boolean {
        synchronized(requestLock) {
            val now = System.currentTimeMillis()

            // Check per-second limit
            val lastSecond = requestTimestamps.count { it > now - 1000 }
            if (lastSecond >= config.requestsPerSecond) {
                return true
            }

            // Check per-minute limit
            val lastMinute = requestTimestamps.count { it > now - 60_000 }
            if (lastMinute >= config.requestsPerMinute * 0.9) { // 90% threshold
                return true
            }

            return false
        }
    }

    /**
     * Calculate recommended delay based on current request rate
     */
    fun getPreemptiveDelay(): Duration {
        synchronized(requestLock) {
            val now = System.currentTimeMillis()
            val lastSecond = requestTimestamps.count { it > now - 1000 }

            if (lastSecond >= config.requestsPerSecond) {
                return 1.seconds
            }

            return Duration.ZERO
        }
    }

    /**
     * Execute a block with rate limit awareness
     *
     * @param block The operation to execute
     * @return Result of the operation
     * @throws RateLimitException if rate limited and should wait
     */
    suspend fun <T> executeWithRateLimiting(block: suspend () -> T): T {
        // Check if currently rate limited
        if (isCurrentlyLimited()) {
            val waitTime = getWaitTime()
            if (config.waitOnRateLimit) {
                Log.d(TAG, "Waiting ${waitTime.inWholeSeconds}s due to rate limit")
                delay(waitTime.inWholeMilliseconds)
                clearRateLimit()
            } else {
                throw RateLimitException(
                    "Rate limited, try again in ${waitTime.inWholeSeconds}s",
                    retryAfter = waitTime.inWholeSeconds.toString()
                )
            }
        }

        // Check preemptive delay
        val preemptiveDelay = getPreemptiveDelay()
        if (preemptiveDelay > Duration.ZERO) {
            Log.d(TAG, "Preemptive delay of ${preemptiveDelay.inWholeMilliseconds}ms")
            delay(preemptiveDelay.inWholeMilliseconds)
        }

        // Track this request
        trackRequest()

        // Execute the operation
        return block()
    }

    /**
     * Execute with automatic retry on rate limit
     */
    suspend fun <T> executeWithRateLimitRetry(
        maxRetries: Int = 3,
        block: suspend () -> T
    ): T {
        var lastException: RateLimitException? = null

        repeat(maxRetries) { attempt ->
            try {
                return executeWithRateLimiting(block)
            } catch (e: RateLimitException) {
                lastException = e
                val waitTime = parseRetryAfter(e.retryAfter)
                Log.w(TAG, "Rate limited on attempt ${attempt + 1}/$maxRetries, waiting ${waitTime.inWholeSeconds}s")

                if (attempt < maxRetries - 1) {
                    handleRateLimitException(e)
                    delay(waitTime.inWholeMilliseconds)
                    clearRateLimit()
                }
            }
        }

        throw lastException ?: RateLimitException("Rate limit retry exhausted")
    }
}

/**
 * Rate limit configuration
 */
data class RateLimitConfig(
    /**
     * Default delay when Retry-After header is not present
     */
    val defaultDelay: Duration = 60.seconds,

    /**
     * Whether to automatically wait when rate limited
     */
    val waitOnRateLimit: Boolean = true,

    /**
     * Maximum requests per second (for preemptive limiting)
     */
    val requestsPerSecond: Int = 10,

    /**
     * Maximum requests per minute (for preemptive limiting)
     */
    val requestsPerMinute: Int = 1000,

    /**
     * Whether to use preemptive rate limiting
     */
    val enablePreemptiveLimiting: Boolean = true
)

/**
 * Exception thrown when rate limited
 */
class RateLimitException(
    message: String,
    /**
     * Value from Retry-After header (seconds or date)
     */
    val retryAfter: String? = null,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Get the retry delay as Duration
     */
    fun getRetryDelay(): Duration {
        return retryAfter?.toLongOrNull()?.seconds ?: 60.seconds
    }
}

/**
 * Result type for rate-limited operations
 */
sealed class RateLimitResult<out T> {
    data class Success<T>(val value: T) : RateLimitResult<T>()
    data class RateLimited(val retryAfterSeconds: Long) : RateLimitResult<Nothing>()
    data class Error(val exception: Throwable) : RateLimitResult<Nothing>()
}
