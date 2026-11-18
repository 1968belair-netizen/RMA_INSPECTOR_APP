package com.bitzer.rmainspector.ui.workspace

import com.bitzer.rmainspector.data.ApiClient
import com.bitzer.rmainspector.data.Capabilities
import com.bitzer.rmainspector.data.Checklist
import com.bitzer.rmainspector.data.ChecklistStep
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.CodeLists
import com.bitzer.rmainspector.data.LockState
import com.bitzer.rmainspector.data.RmaDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Banners, read-only rule and the guided button (spec §4.1, §5). FAKE data. */
class WorkspaceStateTest {
    private val all = Capabilities(true, true, true, true, true)
    private val recip = RmaDetail("912345", "912345", "sig", isOpen = true, inspectable = true,
        productType = "Recip", productTypeKey = "recip", capabilities = all)
    private val scroll = recip.copy(rmaId = "912348", displayNumber = "912348", productType = "Scroll", productTypeKey = "scroll",
        capabilities = Capabilities())

    @Test fun openRecipHasNoBanners() {
        assertTrue(workspaceBanners(recip).isEmpty())
        assertFalse(recip.isReadOnly)
    }

    @Test fun scrollIsReadOnlyHandledInTheRepairDatabase() {
        val b = workspaceBanners(scroll).single() as WorkspaceBanner.ReadOnly
        assertEquals(ReadOnlyReason.SCROLL, b.reason)
        assertTrue(b.text.startsWith("Handled in the repair database"))
        assertTrue(scroll.isReadOnly)
    }

    @Test fun closedWinsOverScroll() {
        val b = workspaceBanners(scroll.copy(isOpen = false)).single() as WorkspaceBanner.ReadOnly
        assertEquals(ReadOnlyReason.CLOSED, b.reason)
    }

    @Test fun productTypeNotEnabledIsReadOnly() {
        val b = workspaceBanners(recip.copy(productType = "Transport", productTypeKey = "transport", capabilities = Capabilities()))
            .single() as WorkspaceBanner.ReadOnly
        assertEquals(ReadOnlyReason.NOT_ENABLED, b.reason)
    }

    @Test fun lockBannerNamesWhoHoldsIt() {
        val b = workspaceBanners(recip.copy(lock = LockState(locked = true, heldBy = "JDOE2", host = "EXAMPLE-PC")))
            .single() as WorkspaceBanner.Locked
        assertTrue(b.text.contains("JDOE2 (EXAMPLE-PC)"))
        assertFalse(b.possiblyStale)
    }

    @Test fun bannersInOrderReadOnlyLockChanged() {
        val banners = workspaceBanners(scroll.copy(lock = LockState(locked = true, possiblyStale = true)), changedElsewhere = true)
        assertEquals(3, banners.size)
        assertTrue(banners[0] is WorkspaceBanner.ReadOnly)
        assertTrue((banners[1] as WorkspaceBanner.Locked).possiblyStale)
        assertEquals(WorkspaceBanner.ChangedElsewhere, banners[2])
    }

    @Test fun guidedUnavailableWithoutChecklist() {
        val g = guidedButtonFor(recip, emptyMap())
        assertEquals(GuidedButton.Unavailable("Recip"), g)
        assertEquals("No guided list for Recip yet", g.label())
    }

    @Test fun guidedStartsWhenTheServerSendsAList() {
        val list = Checklist("2026-12-01", listOf(ChecklistStep(CodeKey(null, "63"), required = true)))
        assertEquals(GuidedButton.Start(1), guidedButtonFor(recip, mapOf("recip" to list)))
        // Read-only RMAs never offer guided mode.
        assertTrue(guidedButtonFor(scroll, mapOf("scroll" to list)) is GuidedButton.Unavailable)
    }

    @Test fun checklistsParseFromCodeLists() {
        val json = """
        { "checklists": { "recip": { "version": "2026-12-01", "steps": [
            { "part_code": { "group": null, "code": "63" }, "required": true,
              "photos": [ { "slot": "overall", "label": "Overall", "required_when": "always" },
                          { "slot": "detail", "label": "Detail", "required_when": "non_normal" } ] },
            { "part_code": { "code": "07a" } } ] } } }
        """.trimIndent()
        val lists = ApiClient.defaultJson.decodeFromString(CodeLists.serializer(), json)
        val steps = lists.checklists.getValue("recip").steps
        assertEquals(CodeKey(null, "63"), steps[0].partCode)
        assertEquals("non_normal", steps[0].photos[1].requiredWhen)
        assertEquals("07a", steps[1].partCode.code)
        assertTrue(ApiClient.defaultJson.decodeFromString(CodeLists.serializer(), """{"checklists": {}}""").checklists.isEmpty())
    }
}
