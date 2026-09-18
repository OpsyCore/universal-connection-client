package io.ucc.core.config

import io.ucc.core.config.parser.LinkParser
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.model.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportPlannerTest {
    private var n = 0
    private val importer = ConfigImporter(LinkParser({ "id-${++n}" }, { 1L }))
    private val caps = CoreCapabilities(
        protocols = setOf(Protocol.VLESS, Protocol.TROJAN, Protocol.SHADOWSOCKS),
        transports = setOf("tcp", "ws"),
        reality = true, utlsFingerprints = true, perAppRouting = true, ruleSets = true, fakeIp = true, hotReload = true,
    )
    private val planner = ImportPlanner(CapabilityCheck(caps))

    private fun report(text: String) = importer.import(text)

    @Test fun `new profiles are preselected`() {
        val plan = planner.plan(report("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B"), existing = emptyList())
        assertEquals(2, plan.newCount)
        assertTrue(plan.items.all { it.selectedByDefault && it.status is ImportItem.Status.New })
        assertTrue(plan.hasAnythingToSave)
    }

    @Test fun `duplicate against store is detected by fingerprint not name`() {
        val existing = report("trojan://pw@1.2.3.4:443#Old name").profiles
        val plan = planner.plan(report("trojan://pw@1.2.3.4:443#Completely different name"), existing)
        val item = plan.items.single()
        val dup = assertIs<ImportItem.Status.Duplicate>(item.status)
        assertEquals(existing[0].id, dup.existingId)
        assertEquals("Old name", dup.existingName)
        assertFalse(item.selectedByDefault)
        assertFalse(plan.hasAnythingToSave)
    }

    @Test fun `same name different server is not a duplicate`() {
        val existing = report("trojan://pw@1.2.3.4:443#Same").profiles
        val plan = planner.plan(report("trojan://pw@9.9.9.9:443#Same"), existing)
        assertIs<ImportItem.Status.New>(plan.items.single().status)
    }

    @Test fun `unsupported protocol for the selected core is flagged but savable and not preselected`() {
        val plan = planner.plan(report("hy2://pw@h.example.com:443#H"), emptyList())
        val item = plan.items.single()
        val u = assertIs<ImportItem.Status.Unsupported>(item.status)
        assertIs<UnsupportedReason.Protocol>(u.reason)
        assertEquals("hysteria2", u.reason.detail)
        assertTrue(item.status.isSavable)
        assertFalse(item.selectedByDefault)
        assertEquals(1, plan.unsupportedCount)
    }

    @Test fun `unsupported transport is flagged using capabilities`() {
        val plan = planner.plan(report("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?type=grpc&security=tls#g"), emptyList())
        val u = assertIs<ImportItem.Status.Unsupported>(plan.items.single().status)
        assertIs<UnsupportedReason.Transport>(u.reason)
        assertEquals("grpc", u.reason.detail)
    }

    @Test fun `xhttp Transport Unsupported is flagged by its own name`() {
        val plan = planner.plan(report("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?type=xhttp&security=tls#x"), emptyList())
        val u = assertIs<ImportItem.Status.Unsupported>(plan.items.single().status)
        assertEquals("xhttp", u.reason.detail)
    }

    @Test fun `reality without core support is a feature gap`() {
        val noReality = ImportPlanner(CapabilityCheck(caps.copy(reality = false)))
        val plan = noReality.plan(report("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=reality&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sni=a.com#r"), emptyList())
        val u = assertIs<ImportItem.Status.Unsupported>(plan.items.single().status)
        assertIs<UnsupportedReason.Feature>(u.reason)
    }

    @Test fun `partially valid batch keeps good items and lists failures`() {
        val plan = planner.plan(report("trojan://pw@1.2.3.4:443#ok\nnotalink://x\ntrojan://pw@1.2.3.4:99999#badport"), emptyList())
        assertEquals(1, plan.items.size)
        assertEquals(2, plan.failures.size)
        assertTrue(plan.failures.none { it.snippet.contains("pw@") })
    }

    @Test fun `empty and unknown input produce empty plan`() {
        assertTrue(planner.plan(report(""), emptyList()).isEmpty)
        val unknown = planner.plan(report("hello"), emptyList())
        assertTrue(unknown.items.isEmpty()); assertEquals(1, unknown.failures.size)
    }

    @Test fun `structurally invalid profile is marked Invalid`() {
        val p = report("trojan://pw@1.2.3.4:443#x").profiles.single().copy(address = "bad host with spaces")
        val plan = planner.plan(ImportReport(listOf(p), emptyList(), InputFormat.SHARE_LINKS), emptyList())
        val inv = assertIs<ImportItem.Status.Invalid>(plan.items.single().status)
        assertIs<ConfigError.InvalidField>(inv.errors.single())
        assertFalse(plan.items.single().status.isSavable)
    }
}
