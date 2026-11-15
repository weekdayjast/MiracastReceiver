package top.jast.miracastreceiver.settings

import top.jast.miracastreceiver.util.ReceiverName
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

class NameSetupTest {
    @Test fun namesRespectUtf8DnsBudget() {
        assertEquals("客厅电视", ReceiverName.normalize("  客厅电视  "))
        assertEquals("📺 Bedroom", ReceiverName.normalize("📺 Bedroom"))
        assertNotNull(ReceiverName.normalize("客".repeat(16)))
        assertNull(ReceiverName.normalize("客".repeat(17)))
        assertNotNull(ReceiverName.normalize("a".repeat(48)))
        assertNull(ReceiverName.normalize("a".repeat(49)))
        assertNull(ReceiverName.normalize(" \t "))
        assertNull(ReceiverName.normalize("Living\r\nRoom"))
        assertNull(ReceiverName.normalize("Living\u0000Room"))
        assertNull(ReceiverName.normalize("broken\uD800"))
    }

    @Test fun pageEscapesNamesAndMessages() {
        val html = NameSetupPage.render("TV", "hint", "\"><script>alert(1)</script>",
            listOf("A&B"), "Save", "<saved>")
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&quot;&gt;&lt;script&gt;"))
        assertTrue(html.contains("A&amp;B"))
        assertTrue(html.contains("&lt;saved&gt;"))
        assertTrue(html.contains("<button type=\"button\" class=\"preset\" data-name=\"A&amp;B\" aria-pressed=\"false\">A&amp;B</button>"))
        assertFalse(html.contains("<datalist"))
    }

    private fun request(url: String, method: String = "GET", body: String = "", path: String? = null,
                        extra: String = ""): String {
        val uri = URI(url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5000
            val data = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${uri.authority}\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: ${data.size}\r\n$extra\r\n").toByteArray())
                write(data); flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun tokenProtectedFormSavesUnicodeAndRejectsInvalidRequests() {
        val name = AtomicReference("Original TV")
        NameSetupServer("127.0.0.1", { message, _ -> "${name.get()} $message" }, { name.set(it); true }, "Saved", "Invalid").use { server ->
            assertTrue(request(server.url).contains("Original TV"))
            assertTrue(request(server.url, "POST", "name=Wrong", "/wrong-token").startsWith("HTTP/1.1 404"))
            assertEquals("Original TV", name.get())
            assertTrue(request(server.url, "GET", path = URI(server.url).path + "?name=Wrong").startsWith("HTTP/1.1 404"))
            assertEquals("Original TV", name.get())
            val body = "name=" + URLEncoder.encode(" 客厅电视 ", "UTF-8")
            val response = request(server.url, "POST", body)
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("Saved"))
            assertTrue(response.contains("Cache-Control: no-store"))
            assertEquals("客厅电视", name.get())
            for (invalid in listOf("", " ", "客".repeat(17), "A\nB")) {
                assertTrue(request(server.url, "POST", "name=" + URLEncoder.encode(invalid, "UTF-8"))
                    .startsWith("HTTP/1.1 400"))
                assertEquals("客厅电视", name.get())
            }
            assertTrue(request(server.url, "POST", "name=One&name=Two").startsWith("HTTP/1.1 400"))
            assertTrue(request(server.url, "PUT", "name=Wrong").startsWith("HTTP/1.1 405"))
            assertTrue(request(server.url, "POST", "name=Wrong", extra = "Transfer-Encoding: chunked\r\n")
                .startsWith("HTTP/1.1 400"))
            assertEquals("客厅电视", name.get())
        }
    }

    @Test fun serverStopsWhenDialogCloses() {
        val server = NameSetupServer("127.0.0.1", { _, _ -> "page" }, { true }, "Saved", "Invalid")
        val uri = URI(server.url)
        server.close()
        assertTrue(runCatching { Socket(uri.host, uri.port).use { } }.isFailure)
    }

    @Test fun failedPersistenceIsReported() {
        NameSetupServer("127.0.0.1", { message, _ -> message }, { false }, "Saved", "Failed").use { server ->
            assertTrue(request(server.url, "POST", "name=Bedroom").startsWith("HTTP/1.1 500"))
        }
    }

    @Test fun savedResponseIncludesConfirmationAndThePersistedName() {
        val name = AtomicReference("Original")
        NameSetupServer("127.0.0.1", { message, saved ->
            NameSetupPage.render("TV", "Hint", name.get(), listOf("Bedroom"), "Save", message, saved)
        }, { name.set(it); true }, "Saved", "Failed").use { server ->
            assertFalse(request(server.url).contains("role=\"alert\""))
            val response = request(server.url, "POST", "name=Living+Room")
            assertTrue(response.contains("class=\"notice success\" role=\"alert\""))
            assertTrue(response.contains("✓ Saved"))
            assertTrue(response.contains("<div class=\"saved-name\">Living Room</div>"))
            // Submission targets the confirmation, even when the save button was below the fold.
            assertTrue(response.contains("action=\"#result\""))
            val script = response.substringAfter("<script>").substringBefore("</script>")
            val hash = java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8)))
            assertTrue(response.contains("script-src 'sha256-$hash'"))
        }
    }

    @Test fun failedOrThrowingSaveNeverShowsSuccess() {
        for (throws in listOf(false, true)) {
            NameSetupServer("127.0.0.1", { message, saved ->
                NameSetupPage.render("TV", "Hint", "Original", emptyList(), "Save", message, saved)
            }, { if (throws) error("Storage unavailable") else false }, "Saved", "Failed").use { server ->
                val response = request(server.url, "POST", "name=Bedroom")
                assertTrue(response.startsWith("HTTP/1.1 500"))
                assertTrue(response.contains("class=\"notice\" role=\"alert\""))
                assertTrue(response.contains("! Failed"))
                assertFalse(response.contains("✓ Saved"))
            }
        }
    }

    @Test fun expiredLinkCannotSave() {
        val name = AtomicReference("Original")
        NameSetupServer("127.0.0.1", { _, _ -> "page" }, { name.set(it); true }, "Saved", "Invalid",
            lifetimeMs = 20).use { server ->
            Thread.sleep(100)
            // Expiry may close the listener or reject an already accepted request.
            val response = runCatching { request(server.url, "POST", "name=Wrong") }.getOrNull()
            assertTrue(response == null || !response.startsWith("HTTP/1.1 200"))
            assertEquals("Original", name.get())
        }
    }
}
