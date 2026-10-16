// The settings search bar, as the reader meets it: a field on the Settings page
// itself — directly under the page's own title on a wide window, at the top of
// the root list on a narrow one — whose answers include this fork's own rows.
//
// Upstream reveals that field by pulling the list down past a threshold, and its
// search catalogue is a hand-written list of the rows on each page. Both halves
// are pinned here: the field is on the page without any gesture, and the words a
// reader would type find the setting this fork adds ("Hide 18+ content", whose
// own words the catalogue never knew) and land on the page it lives on.

package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import kotlin.test.Test

class SettingsSearchBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theWideWindowShowsTheFieldUnderThePagesTitle() {
        setPage(width = 1280.dp)

        // Title and field on screen together: the bar is on the page itself, not
        // behind a scroll or a pull.
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText(SEARCH_PLACEHOLDER).assertIsDisplayed()
    }

    @Test
    fun theNarrowWindowShowsTheFieldToo() {
        setPage(width = 420.dp)

        compose.onNodeWithText(SEARCH_PLACEHOLDER).assertIsDisplayed()
    }

    @Test
    fun typingTheWordsAReaderUsesFindsThisForksOwnSetting() {
        setPage(width = 1280.dp)

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("18")
        compose.waitForIdle()

        compose.onNodeWithText("Hide 18+ content").assertIsDisplayed()
    }

    @Test
    fun typingTheOtherWordAReaderUsesFindsItToo() {
        setPage(width = 1280.dp)

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("nsfw")
        compose.waitForIdle()

        compose.onNodeWithText("Hide 18+ content").assertIsDisplayed()
    }

    private fun setPage(width: Dp) {
        compose.setContent {
            NuvioTheme {
                Box(modifier = Modifier.size(width, 900.dp)) {
                    SettingsScreen()
                }
            }
        }
    }

    private companion object {
        const val SEARCH_PLACEHOLDER = "Search settings..."
    }
}
