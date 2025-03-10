package xyz.malkki.neostumbler.ui.composables.shared

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlin.time.Duration
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import xyz.malkki.neostumbler.PREFERENCES
import xyz.malkki.neostumbler.domain.LatLng
import xyz.malkki.neostumbler.domain.Position
import xyz.malkki.neostumbler.location.LocationSource

class AreaPickerTest {
    private val testContext: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun testAreaPicker() {
        stopKoin()

        val fakeLocation =
            Position(latitude = 40.689100, longitude = -74.044300, source = "gps", timestamp = 0)

        startKoin {
            modules(
                module {
                    androidContext(testContext)

                    single<Deferred<Call.Factory>> {
                        @OptIn(DelicateCoroutinesApi::class) GlobalScope.async { OkHttpClient() }
                    }

                    single<DataStore<Preferences>>(PREFERENCES) {
                        @OptIn(DelicateCoroutinesApi::class)
                        PreferenceDataStoreFactory.create(
                            scope = GlobalScope,
                            produceFile = { testContext.preferencesDataStoreFile("prefs") },
                        )
                    }

                    single<LocationSource> {
                        object : LocationSource {
                            override fun getLocations(
                                interval: Duration,
                                usePassiveProvider: Boolean,
                            ): Flow<Position> {
                                return flowOf(fakeLocation)
                            }
                        }
                    }
                }
            )
        }

        var selectedCircle: Pair<LatLng, Double>? = null

        composeTestRule.setContent {
            KoinContext {
                AreaPickerDialog(
                    title = "Area picker",
                    positiveButtonText = "select",
                    onAreaSelected = { circle -> selectedCircle = circle },
                )
            }
        }

        composeTestRule.onNodeWithText("select").performClick()

        assertNotNull(selectedCircle)
        assertEquals(40.689100, selectedCircle!!.first.latitude, 0.0001)
        assertEquals(-74.044300, selectedCircle!!.first.longitude, 0.0001)
        assertTrue(selectedCircle!!.second > 0)
    }
}
