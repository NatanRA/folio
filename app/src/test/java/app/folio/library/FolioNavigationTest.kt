package app.folio.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolioNavigationTest {
    @Test fun opensEveryMainScreen() {
        listOf("home", "library", "downloads", "server").forEachIndexed { index, name ->
            assertEquals(FolioNavigation(index), FolioNavigation.from("folio://navigate?screen=$name"))
        }
    }
    @Test fun acceptsEncodedLibrarySearch() {
        assertEquals(FolioNavigation(1, "Alice & C++"), FolioNavigation.from("folio://navigate?screen=library&q=Alice+%26+C%2B%2B"))
    }
    @Test fun refusesUnknownOrNonNavigationOperations() {
        listOf("folio://navigate?screen=delete", "folio://navigate?screen=home&screen=server",
            "folio://navigate?screen=server&q=Alice", "folio://navigate?screen=library&q=",
            "folio://navigate?screen=library&q=%00", "folio://navigate?screen=home&password=secret",
            "folio://navigate/other?screen=home", "folio://user@navigate?screen=home",
            "folio://navigate?screen=home#other", "folio-pair://booklore?address=x", "https://navigate?screen=home"
        ).forEach { assertNull(it, FolioNavigation.from(it)) }
    }
}
