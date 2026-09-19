package dev.aoframe.immich

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * File-backed JSON loading for ImmichSecrets/ImmichSecretsStore, including
 * the optional-field-vs-blank-string and NaN-vs-null-double handling.
 * Instrumented (androidTest) - see ImmichClientTest's header comment for
 * why org.json needs a real Android runtime here.
 */
class ImmichSecretsStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val secretsFile = File(context.filesDir, "immich-secrets.json")

    @Before
    fun setUp() {
        secretsFile.delete()
    }

    @After
    fun tearDown() {
        secretsFile.delete()
    }

    @Test
    fun loadWithNoFileReturnsNull() {
        assertNull(ImmichSecretsStore.load(context))
    }

    @Test
    fun loadWithMalformedJsonReturnsNull() {
        secretsFile.writeText("not json")

        assertNull(ImmichSecretsStore.load(context))
    }

    @Test
    fun loadParsesEveryFieldWhenAllArePresent() {
        secretsFile.writeText(
            """
            {
                "baseUrl": "https://immich.example",
                "apiKey": "<test-api-key>",
                "photoSource": "immich",
                "localServerBaseUrl": "http://local.example",
                "videoGateBaseUrl": "http://gate.example",
                "piBaseUrl": "http://pi.example",
                "weatherLatitude": 50.45,
                "weatherLongitude": 30.52,
                "weatherSeaLatitude": 46.48,
                "weatherSeaLongitude": 30.72,
                "locale": "uk"
            }
            """.trimIndent()
        )

        val secrets = ImmichSecretsStore.load(context)

        assertEquals(
            ImmichSecrets(
                baseUrl = "https://immich.example",
                apiKey = "<test-api-key>",
                photoSource = "immich",
                localServerBaseUrl = "http://local.example",
                videoGateBaseUrl = "http://gate.example",
                piBaseUrl = "http://pi.example",
                weatherLatitude = 50.45,
                weatherLongitude = 30.52,
                weatherSeaLatitude = 46.48,
                weatherSeaLongitude = 30.72,
                locale = "uk"
            ),
            secrets
        )
    }

    @Test
    fun loadTreatsBlankOptionalStringsAsNullRatherThanEmpty() {
        secretsFile.writeText(
            """{"baseUrl": "https://immich.example", "apiKey": "key", "photoSource": "", "locale": ""}"""
        )

        val secrets = ImmichSecretsStore.load(context)

        assertNull(secrets?.photoSource)
        assertNull(secrets?.locale)
    }

    @Test
    fun loadTreatsMissingCoordinatesAsNullRatherThanNaN() {
        secretsFile.writeText("""{"baseUrl": "https://immich.example", "apiKey": "key"}""")

        val secrets = ImmichSecretsStore.load(context)

        assertNull(secrets?.weatherLatitude)
        assertNull(secrets?.weatherLongitude)
        assertNull(secrets?.weatherSeaLatitude)
        assertNull(secrets?.weatherSeaLongitude)
    }
}
