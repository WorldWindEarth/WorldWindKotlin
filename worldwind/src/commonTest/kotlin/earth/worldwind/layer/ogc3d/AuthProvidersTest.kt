package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.ogc3d.auth.AuthedRequest
import earth.worldwind.layer.ogc3d.auth.BearerTokenAuthProvider
import earth.worldwind.layer.ogc3d.auth.CesiumIonAuthProvider
import earth.worldwind.layer.ogc3d.auth.CompositeAuthProvider
import earth.worldwind.layer.ogc3d.auth.CustomHeadersAuthProvider
import earth.worldwind.layer.ogc3d.auth.GoogleTilesAuthProvider
import earth.worldwind.layer.ogc3d.auth.NoAuthProvider
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthProvidersTest {

    // --- NoAuthProvider ----------------------------------------------------------

    @Test fun noAuthPassesThrough() {
        val req = NoAuthProvider.rewriteRequest("https://example.com/tileset.json")
        assertEquals("https://example.com/tileset.json", req.url)
        assertTrue(req.headers.isEmpty())
        assertNull(NoAuthProvider.redirectFor("https://example.com/", "{}"))
    }

    // --- BearerTokenAuthProvider -------------------------------------------------

    @Test fun bearerAttachesAuthorizationHeader() {
        val provider = BearerTokenAuthProvider("abc123")
        val req = provider.rewriteRequest("https://example.com/x")
        assertEquals("Bearer abc123", req.headers["Authorization"])
    }

    @Test fun bearerNeverRedirects() {
        val provider = BearerTokenAuthProvider("abc123")
        assertNull(provider.redirectFor("https://example.com/", "{}"))
    }

    // --- CustomHeadersAuthProvider -----------------------------------------------

    @Test fun customHeadersMergedIntoRequest() {
        val provider = CustomHeadersAuthProvider(mapOf("X-Api-Key" to "k", "X-Trace" to "t"))
        val req = provider.rewriteRequest("https://example.com/x")
        assertEquals("k", req.headers["X-Api-Key"])
        assertEquals("t", req.headers["X-Trace"])
    }

    // --- GoogleTilesAuthProvider -------------------------------------------------

    @Test fun googleAppendsKeyToOutgoingUrl() {
        val provider = GoogleTilesAuthProvider("MYKEY")
        val req = provider.rewriteRequest("https://tile.googleapis.com/v1/3dtiles/root.json")
        assertTrue("key=MYKEY" in req.url, "URL was '${req.url}'")
    }

    @Test fun googleCapturesSessionFromResponseUrl() {
        val provider = GoogleTilesAuthProvider("MYKEY")
        provider.observeTilesetResponse(
            requestedUrl = "https://tile.googleapis.com/v1/3dtiles/root.json?key=MYKEY",
            responseUrl = "https://tile.googleapis.com/v1/3dtiles/root.json?session=ABC&key=MYKEY",
            body = "{}",
        )
        val req2 = provider.rewriteRequest("https://tile.googleapis.com/v1/3dtiles/datasets/x")
        assertTrue("session=ABC" in req2.url, "session token should propagate; URL was '${req2.url}'")
    }

    @Test fun googleCapturesSessionFromBodyWhenUrlIsBare() {
        val provider = GoogleTilesAuthProvider("MYKEY")
        val body = """
            {
              "children": [
                {"contents": [{"uri": "datasets/CgA/files/abc.glb?session=ZZZ"}]}
              ]
            }
        """.trimIndent()
        provider.observeTilesetResponse(
            requestedUrl = "https://tile.googleapis.com/v1/3dtiles/root.json?key=MYKEY",
            responseUrl = "https://tile.googleapis.com/v1/3dtiles/root.json?key=MYKEY",
            body = body,
        )
        val rewritten = provider.rewriteChildUri(
            parentUri = "https://tile.googleapis.com/v1/3dtiles/root.json",
            childUri = "https://tile.googleapis.com/v1/3dtiles/datasets/other/files/a.glb",
        )
        assertTrue("session=ZZZ" in rewritten, "Body-scraped session should ride child URIs; got '$rewritten'")
        assertTrue("key=MYKEY" in rewritten)
    }

    @Test fun googlePreservesExistingSessionOnChildUri() {
        val provider = GoogleTilesAuthProvider("MYKEY")
        provider.observeTilesetResponse(
            requestedUrl = "https://tile.googleapis.com/x",
            responseUrl = "https://tile.googleapis.com/x?session=NEW",
            body = "{}",
        )
        val rewritten = provider.rewriteChildUri(
            parentUri = "https://tile.googleapis.com/x",
            childUri = "https://tile.googleapis.com/y?session=OLD",
        )
        assertTrue("session=OLD" in rewritten)
        assertFalse("session=NEW" in rewritten)
    }

    @Test fun googleNeverRedirects() {
        val provider = GoogleTilesAuthProvider("MYKEY")
        assertNull(provider.redirectFor("https://x", "{}"))
    }

    // --- CesiumIonAuthProvider ---------------------------------------------------

    @Test fun ionFirstFetchUsesUserToken() {
        val provider = CesiumIonAuthProvider("USERTOK")
        val req = provider.rewriteRequest("https://api.cesium.com/v1/assets/123/endpoint")
        assertEquals("Bearer USERTOK", req.headers["Authorization"])
    }

    @Test fun ionRedirectsToReturnedUrlAndSwapsToken() {
        val provider = CesiumIonAuthProvider("USERTOK")
        val responseBody = """
            {
              "type": "3DTILES",
              "url": "https://assets.cesium.com/123/tileset.json",
              "accessToken": "ASSETTOK"
            }
        """.trimIndent()
        val redirect = provider.redirectFor(
            responseUrl = "https://api.cesium.com/v1/assets/123/endpoint",
            body = responseBody,
        )
        assertEquals("https://assets.cesium.com/123/tileset.json", redirect)

        // Subsequent request now uses the asset access token, not the user token.
        val req = provider.rewriteRequest("https://assets.cesium.com/123/tileset.json")
        assertEquals("Bearer ASSETTOK", req.headers["Authorization"])
    }

    @Test fun ionRedirectIsOneShot() {
        val provider = CesiumIonAuthProvider("USERTOK")
        val responseBody = """{"type":"3DTILES","url":"https://x.com/t.json","accessToken":"AT"}"""
        assertNotNull(provider.redirectFor("https://api.cesium.com/v1/assets/1/endpoint", responseBody))
        // Second call returns null even when the body still looks like an endpoint envelope.
        assertNull(provider.redirectFor("https://x.com/t.json", responseBody))
    }

    @Test fun ionIgnoresRegularTilesetJson() {
        val provider = CesiumIonAuthProvider("USERTOK")
        val tilesetJson = """
            {"asset":{"version":"1.0"},"geometricError":100.0,"root":{}}
        """.trimIndent()
        assertNull(provider.redirectFor("https://example.com/tileset.json", tilesetJson))
    }

    // --- CompositeAuthProvider ---------------------------------------------------

    @Test fun compositeChainsRewrites() {
        val provider = CompositeAuthProvider(
            BearerTokenAuthProvider("TOK"),
            CustomHeadersAuthProvider(mapOf("X-Trace" to "abc")),
        )
        val req = provider.rewriteRequest("https://example.com/x")
        assertEquals("Bearer TOK", req.headers["Authorization"])
        assertEquals("abc", req.headers["X-Trace"])
    }

    @Test fun compositeDelegatesRedirectToFirstClaimer() {
        val ion = CesiumIonAuthProvider("USER")
        val provider = CompositeAuthProvider(
            CustomHeadersAuthProvider(mapOf("X-Trace" to "t")),
            ion,
        )
        val body = """{"type":"3DTILES","url":"https://x.com/t.json","accessToken":"AT"}"""
        val redirect = provider.redirectFor("https://api.cesium.com/v1/assets/1/endpoint", body)
        assertEquals("https://x.com/t.json", redirect)
    }

    @Test fun compositeRedirectNullWhenNoProviderClaims() {
        val provider = CompositeAuthProvider(
            BearerTokenAuthProvider("TOK"),
            CustomHeadersAuthProvider(mapOf("X-Trace" to "t")),
        )
        assertNull(provider.redirectFor("https://example.com/", "{}"))
    }

    @Test fun compositeChildUriRewritesApplyInOrder() {
        // A pseudo-provider that wraps the URI in tag brackets, twice composed.
        class TagProvider(private val tag: String) : TilesetAuthProvider {
            override val discriminator: String = "TagProvider:$tag"
            override fun rewriteRequest(url: String, headers: MutableMap<String, String>): AuthedRequest =
                AuthedRequest(url, headers)
            override fun rewriteChildUri(parentUri: String, childUri: String): String =
                "<$tag>$childUri</$tag>"
        }
        val provider = CompositeAuthProvider(TagProvider("a"), TagProvider("b"))
        val rewritten = provider.rewriteChildUri("p", "child")
        assertEquals("<b><a>child</a></b>", rewritten)
    }
}
