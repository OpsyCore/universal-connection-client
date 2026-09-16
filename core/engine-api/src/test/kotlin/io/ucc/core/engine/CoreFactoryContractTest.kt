package io.ucc.core.engine

import io.ucc.core.engine.manager.DefaultConnectionManager
import io.ucc.core.engine.manager.FakeCore
import io.ucc.core.engine.manager.FakeNetwork
import io.ucc.core.engine.manager.FakeProfiles
import io.ucc.core.engine.manager.FakeTunnelHost
import io.ucc.core.engine.manager.FakeClock
import io.ucc.core.engine.manager.awaitValue
import io.ucc.core.engine.manager.fastPolicy
import io.ucc.core.engine.manager.profile
import io.ucc.core.model.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves the Core boundary: a ConnectionManager can be assembled from a
 * CoreFactory + CorePlatform without knowing any engine type, and a second
 * engine can be swapped in by replacing the factory only.
 */
class CoreFactoryContractTest {
    private class FakePlatform : CorePlatform {
        override val workingDirectory = File(System.getProperty("java.io.tmpdir"), "ucc-test")
        override val cacheDirectory = workingDirectory
        override val debug = true
        override val underlyingNetwork = MutableStateFlow<UnderlyingNetwork?>(null)
    }

    private class FakeFactory(override val id: String, private val core: CoreAdapter) : CoreFactory {
        var createdWith: CorePlatform? = null
        override val descriptor = core.descriptor
        override val capabilities = core.capabilities
        override val notices = listOf(ThirdPartyNotice("fake-engine", "1.0", "MIT", "https://example.invalid"))
        override fun create(platform: CorePlatform): CoreAdapter { createdWith = platform; return core }
    }

    @Test
    fun `manager is assembled through the factory and connects`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fakeCore = FakeCore()
            val factory: CoreFactory = FakeFactory("fake", fakeCore)
            val platform = FakePlatform()
            val manager = DefaultConnectionManager(
                scope, factory.create(platform), FakeTunnelHost(),
                FakeProfiles(mapOf("p1" to profile("p1"))), FakeNetwork(), FakeClock(), fastPolicy,
            )
            assertEquals(platform, (factory as FakeFactory).createdWith)
            manager.connect("p1")
            val s = manager.state.awaitValue { it is ConnectionState.Connected || it is ConnectionState.Error }
            assertIs<ConnectionState.Connected>(s)
            assertEquals(1, fakeCore.startCount)
        } finally { scope.cancel() }
    }

    @Test
    fun `factories are interchangeable and carry their own notices`() {
        val a = FakeFactory("a", FakeCore())
        val b = FakeFactory("b", FakeCore())
        val chosen: CoreFactory = listOf(a, b).first { it.id == "b" }
        assertEquals("b", chosen.id)
        assertTrue(chosen.notices.single().license == "MIT")
        assertTrue(chosen.capabilities.protocols.contains(Protocol.VLESS) || chosen.capabilities.protocols.isEmpty())
    }
}
