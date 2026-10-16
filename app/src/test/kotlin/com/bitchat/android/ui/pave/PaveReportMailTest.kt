package com.bitchat.android.ui.pave

import com.bitchat.android.ui.WeshLinks
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaveReportMailTest {
    private val fr = PaveStringResources.strings("values-fr")
    private val rule = PaveStringResources.remoteNameRule("values-fr")
    private val text: (Int, Array<out Any>) -> String = { id, args -> fr.getValue(PaveStringResources.name(id)).format(*args) }

    private fun mail(target: PaveReportTarget, reason: PaveReportReason = PaveReportReason.Harassment) =
        PaveReportMail.compose(target, reason, rule, text)

    private fun decoded(uri: String, field: String): String =
        URLDecoder.decode(uri.substringAfter("$field=").substringBefore("&").replace("+", "%2B"), "UTF-8")

    @Test
    fun `the e-mail goes to the single report address with the fixed subject`() {
        val mail = mail(PaveReportTarget("Léo", "3C81 E0F2 9A47 15BD", PaveReportSource.Contact))
        assertTrue(mail.uri.startsWith("mailto:${WeshLinks.REPORT_EMAIL}?subject="))
        assertEquals("Signalement Wesh", decoded(mail.uri, "subject"))
        assertEquals(WeshLinks.REPORT_EMAIL, mail.address)
        assertEquals("Signalement Wesh", mail.subject)
    }

    @Test
    fun `the body names the person through the remote name rule, with fingerprint and reason`() {
        val raw = "Le​o" // an invisible character between letters: the rule shows it as unnamed
        val body = mail(PaveReportTarget(raw, "3C81 E0F2 9A47 15BD", PaveReportSource.Request), PaveReportReason.Spam).body
        assertTrue(body, body.contains(rule.show(raw)))
        assertFalse(body, body.contains(raw))
        assertTrue(body, body.contains("3C81 E0F2 9A47 15BD"))
        assertTrue(body, body.contains(fr.getValue("wesh_report_reason_spam")))
        assertTrue(body, body.contains(fr.getValue("wesh_report_where_request")))
    }

    @Test
    fun `a missing fingerprint says so instead of leaving a blank`() {
        val body = mail(PaveReportTarget("Léo", null, PaveReportSource.Contact)).body
        assertTrue(body, body.contains(fr.getValue("wesh_pave_fingerprint_unavailable")))
    }

    @Test
    fun `only a public room message carries its text`() {
        val public = mail(PaveReportTarget("Léo", null, PaveReportSource.PublicMessage("Rendez-vous à 18 h & après ?"))).body
        assertTrue(public, public.contains("Rendez-vous à 18 h & après ?"))
        assertTrue(public, public.contains(fr.getValue("wesh_report_mail_public_message")))
        for (source in listOf(PaveReportSource.Request, PaveReportSource.Contact)) {
            val body = mail(PaveReportTarget("Léo", null, source)).body
            assertFalse(body, body.contains(fr.getValue("wesh_report_mail_public_message")))
        }
    }

    @Test
    fun `a request is reported by nickname and fingerprint only, never with private content`() {
        val request = com.bitchat.android.services.WeshMessageRequests.Request(
            peerID = "synthetic-peer", nickname = "Léo", fingerprint = "a1b2c3d4e5f60718" + "9".repeat(48), lastSeen = 0,
        )
        val target = paveReportTarget(request)
        assertEquals(PaveReportTarget("Léo", "A1B2 C3D4 E5F6 0718", PaveReportSource.Request), target)
        val body = mail(target).body
        assertFalse(body, body.contains("synthetic-peer"))
        assertFalse(body, body.contains(fr.getValue("wesh_report_mail_public_message")))
    }

    @Test
    fun `subject and body are percent-encoded for a mailto link`() {
        val mail = mail(PaveReportTarget("A&B=C?", "3C81 E0F2 9A47 15BD", PaveReportSource.PublicMessage("100 % + #tag\nligne 2")))
        val query = mail.uri.substringAfter("?")
        assertFalse(query, query.contains(" ") || query.contains("\n") || query.contains("#") || query.contains("+"))
        assertEquals(2, query.split("&").size) // the nickname's '&' and '=' cannot open a new field
        assertTrue(query.contains("%0D%0A"))
        assertEquals(mail.body.replace("\n", "\r\n"), decoded(mail.uri, "body"))
    }

    @Test
    fun `the local block happens first and nothing is sent by the app`() {
        var blocks = 0
        val target = PaveReportTarget("Léo", null, PaveReportSource.Contact)
        val (blocked, offer) = paveBlockThenOfferReport(target) { blocks++; true }
        assertEquals(1, blocks)
        assertTrue(blocked)
        assertEquals(target, offer)
        // A failed block still offers the e-mail; it never retries nor sends.
        val (failed, again) = paveBlockThenOfferReport(target) { blocks++; false }
        assertFalse(failed)
        assertEquals(target, again)
        assertEquals(2, blocks)
        // The app only hands an editable draft to the mail app: no network call of its own.
        val source = java.io.File("src/main/java/com/bitchat/android/ui/pave/PaveReport.kt").readText()
        assertTrue(source.contains("Intent.ACTION_SENDTO"))
        assertFalse(source.contains("HttpURLConnection") || source.contains("okhttp3"))
    }
}
