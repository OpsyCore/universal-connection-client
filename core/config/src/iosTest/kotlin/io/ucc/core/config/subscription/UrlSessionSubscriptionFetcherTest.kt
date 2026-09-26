package io.ucc.core.config.subscription

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Offline contract checks (no network). Compiles on Linux CI; executes on macOS hosts only. */
class UrlSessionSubscriptionFetcherTest {
    private val fetcher = UrlSessionSubscriptionFetcher(userAgent = "ucc-test")

    @Test
    fun rejectsNonUrl() = runBlocking {
        assertFailsWith<SubscriptionFetchError.InvalidUrl> { fetcher.fetch("not a url at all") }; Unit
    }

    @Test
    fun rejectsPlainHttpByDefault() = runBlocking {
        assertFailsWith<SubscriptionFetchError.InvalidUrl> { fetcher.fetch("http://example.com/sub") }; Unit
    }

    @Test
    fun rejectsMissingHost() = runBlocking {
        assertFailsWith<SubscriptionFetchError.InvalidUrl> { fetcher.fetch("https:///sub") }; Unit
    }
}
