package dev.aoframe.localsource

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Fast, deterministic JSON/HTTP-parsing tests for LocalPhotoClient, using
 * MockWebServer instead of a real AOFrame reference server - same
 * reasoning as ImmichClientTest (see that file's header comment).
 */
class LocalPhotoClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LocalPhotoClient
    private lateinit var destination: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LocalPhotoClient(server.url("/").toString().removeSuffix("/"))
        destination = File.createTempFile("local-photo-client-test", ".bin")
    }

    @After
    fun tearDown() {
        server.shutdown()
        destination.delete()
    }

    @Test
    fun fetchPhotosParsesOptionalFaceCoordinatesWhenPresent() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id": "a", "mimeType": "image/jpeg", "faceX": 25.5, "faceY": 40.0}]"""
            )
        )

        val photos = client.fetchPhotos()

        assertEquals(1, photos.size)
        assertEquals("a", photos[0].id)
        assertEquals(25.5f, photos[0].faceX)
        assertEquals(40.0f, photos[0].faceY)
    }

    @Test
    fun fetchPhotosLeavesFaceCoordinatesNullWhenAbsent() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id": "a", "mimeType": "video/mp4"}]"""))

        val photos = client.fetchPhotos()

        assertNull(photos[0].faceX)
        assertNull(photos[0].faceY)
    }

    @Test
    fun fetchPhotosDefaultsMimeTypeToJpegWhenMissing() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id": "a"}]"""))

        assertEquals("image/jpeg", client.fetchPhotos()[0].mimeType)
    }

    @Test(expected = java.io.IOException::class)
    fun fetchPhotosThrowsOnAnUnsuccessfulResponse() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500))

        client.fetchPhotos()
    }

    @Test
    fun downloadToFileWritesTheResponseBodyToDisk() = runBlocking {
        server.enqueue(MockResponse().setBody("raw-photo-bytes"))

        client.downloadToFile("a", destination)

        assertEquals("raw-photo-bytes", destination.readText())
    }

    @Test(expected = java.io.IOException::class)
    fun downloadToFileThrowsOnAnUnsuccessfulResponse() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404))

        client.downloadToFile("missing", destination)
    }
}
