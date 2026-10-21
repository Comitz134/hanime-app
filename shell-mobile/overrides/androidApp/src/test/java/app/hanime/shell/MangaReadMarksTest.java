package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What a read mark is allowed to be.
 *
 * The mark is a chapter *number* in a preference set, and the whole reason the
 * count is exact is that the set contains one entry per chapter actually
 * opened. Three ways that could stop being true, all of them silent: a number
 * stored with the whitespace the route handed it (so "12" and " 12 " are two
 * chapters), a blank stored as a mark (every nameless extra collapsing into one
 * fake chapter), and an unbounded set (a preference file that grows until the
 * app is uninstalled).
 *
 * `withMarks` is the whole rule, in one function, so it can be held here rather
 * than inferred by opening a title on a phone.
 */
public class MangaReadMarksTest {

    @Test
    public void aNumberIsStoredTrimmed() {
        Set<String> marks = Manga.withMarks(new HashSet<>(), Arrays.asList(" 12 ", "13"), 100);
        assertEquals(new HashSet<>(Arrays.asList("12", "13")), marks);
    }

    @Test
    public void theSameChapterOfferedTwiceIsOneMark() {
        Set<String> before = new HashSet<>(Collections.singletonList("12"));
        Set<String> after = Manga.withMarks(before, Arrays.asList("12", " 12 ", "12"), 100);
        assertEquals(1, after.size());
        assertEquals(before, after);
    }

    @Test
    public void aBlankIsNotAChapter() {
        // Every unnamed extra upsteam would otherwise be the same mark, and the
        // fraction would claim a chapter that no row can point at.
        Set<String> marks = Manga.withMarks(new HashSet<>(), Arrays.asList("", "   ", null, "7"), 100);
        assertEquals(Collections.singleton("7"), marks);
    }

    @Test
    public void theCeilingStopsTheSetRatherThanTheWrite() {
        Set<String> marks = Manga.withMarks(new HashSet<>(), Arrays.asList("1", "2", "3", "4", "5"), 3);
        assertEquals(3, marks.size());
        assertTrue(marks.contains("1"));
        assertFalse("a mark past the ceiling is not stored", marks.contains("4"));
    }

    @Test
    public void anExistingRecordIsKeptWhenMoreIsOffered() {
        Set<String> before = new HashSet<>(Arrays.asList("1", "2"));
        Set<String> after = Manga.withMarks(before, Collections.singletonList("40"), 100);
        assertEquals(3, after.size());
        assertTrue(after.containsAll(Arrays.asList("1", "2", "40")));
    }

    @Test
    public void theCallersSetIsNotModified() {
        // A write decides what to store and then stores it; a helper that edited
        // its argument would have written before the decision was made.
        Set<String> before = new HashSet<>(Collections.singletonList("1"));
        Manga.withMarks(before, Collections.singletonList("2"), 100);
        assertEquals(Collections.singleton("1"), before);
    }

    @Test
    public void nothingOfferedIsNoChange() {
        Set<String> before = new HashSet<>(Collections.singletonList("1"));
        assertEquals(before, Manga.withMarks(before, null, 100));
        assertEquals(before, Manga.withMarks(before, Collections.<String>emptyList(), 100));
    }
}
