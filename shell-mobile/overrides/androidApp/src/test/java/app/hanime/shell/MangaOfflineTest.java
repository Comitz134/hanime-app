package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The chapter store on this phone, held to a JVM.
 *
 * Two failures are worth a test rather than a build and a phone. The first is
 * the layout: a chapter's row and its pages are written separately, and a
 * chapter whose pages are missing must not read back as downloaded — a strip
 * that stops halfway through is worse than a chapter nobody saved. The second
 * is the id: the ids arrive in a query string and become path segments, so a
 * name that could climb out of the app's own folder has to be refused at the
 * door, not cleaned up afterwards.
 */
public class MangaOfflineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static void write(File file, String body) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A title with one whole chapter of two pages. */
    private File storeWithOneChapter() throws Exception {
        File root = folder.newFolder("manga-offline");
        File title = new File(root, "ro8ro");
        assertTrue(title.mkdirs());
        write(new File(title, "title.json"),
                "{\"id\":\"ro8ro\",\"title\":\"Sip of Poison\","
                        + "\"poster\":\"https://x/p.jpg\",\"at\":1760000000000}");
        File chapter = new File(title, "9051");
        assertTrue(chapter.mkdirs());
        write(new File(chapter, "0001.jpg"), "one");
        write(new File(chapter, "0002.jpg"), "two");
        write(new File(chapter, "chapter.json"),
                "{\"id\":\"9051\",\"hid\":\"ro8ro\",\"number\":\"12\",\"name\":\"The Cup\","
                        + "\"at\":\"Oct 1\",\"pages\":2,\"bytes\":6,"
                        + "\"files\":[\"0001.jpg\",\"0002.jpg\"],\"saved\":1760000000000}");
        return root;
    }

    @Test
    public void aWholeChapterReadsBackWithItsTitle() throws Exception {
        MangaOffline.Loaded loaded = MangaOffline.read(storeWithOneChapter());

        assertEquals(1, loaded.titles.size());
        assertEquals("Sip of Poison", loaded.titles.get("ro8ro").name);
        MangaOffline.Chapter saved = loaded.chapters.get("9051");
        assertNotNull(saved);
        assertEquals("ro8ro", saved.hid);
        assertEquals("12", saved.number);
        assertEquals("The Cup", saved.name);
        assertEquals(2, saved.files.size());
        assertEquals(6L, saved.bytes);
    }

    /**
     * A page deleted under the row makes the chapter not-downloaded, not a
     * chapter that renders as far as the gap.
     */
    @Test
    public void aChapterMissingAPageIsNotAChapter() throws Exception {
        File root = storeWithOneChapter();
        File page = new File(new File(new File(root, "ro8ro"), "9051"), "0002.jpg");
        assertTrue(page.delete());

        MangaOffline.Loaded loaded = MangaOffline.read(root);
        assertNull(loaded.chapters.get("9051"));
        assertEquals(0, loaded.titles.size());
    }

    @Test
    public void aChapterWithNoRowIsNotRead() throws Exception {
        File root = storeWithOneChapter();
        File row = new File(new File(new File(root, "ro8ro"), "9051"), "chapter.json");
        assertTrue(row.delete());

        assertEquals(0, MangaOffline.read(root).chapters.size());
    }

    @Test
    public void aStoreThatIsNotThereIsEmptyRatherThanAnError() {
        assertTrue(MangaOffline.read(null).chapters.isEmpty());
        assertTrue(MangaOffline.read(new File("/no/such/place")).chapters.isEmpty());
    }

    /**
     * The id becomes a directory under the app's own files. `..` is not an id.
     */
    @Test
    public void namesThatCouldClimbOutOfTheStoreAreRefused() {
        assertEquals("ro8ro", MangaOffline.safe("ro8ro"));
        assertEquals("9051", MangaOffline.safe(" 9051 "));
        assertEquals("a.b-c_1", MangaOffline.safe("a.b-c_1"));
        assertEquals("", MangaOffline.safe(".."));
        assertEquals("", MangaOffline.safe("."));
        assertEquals("", MangaOffline.safe("../etc"));
        assertEquals("", MangaOffline.safe("a/b"));
        assertEquals("", MangaOffline.safe("a b"));
        assertEquals("", MangaOffline.safe(""));
        assertEquals("", MangaOffline.safe(null));
    }

    /**
     * The reader's next chapter is the row above it, so the saved list is sorted
     * descending by number - and 10 has to come before 9, which a string sort
     * gets wrong.
     */
    @Test
    public void chapterNumbersCompareAsNumbers() {
        assertTrue(MangaOffline.compareNumbers("10", "9") > 0);
        assertTrue(MangaOffline.compareNumbers("9", "10") < 0);
        assertEquals(0, MangaOffline.compareNumbers("12", "12.0"));
        assertTrue(MangaOffline.compareNumbers("2", "1.5") > 0);
        // An extra with no number sorts below the numbered chapters, by name.
        assertTrue(MangaOffline.compareNumbers("12", "Omake") > 0);
        assertTrue(MangaOffline.compareNumbers("Omake", "Artbook") > 0);
    }

    @Test
    public void aPagesTypeDecidesItsFileAndItsAnswer() {
        assertEquals("jpg", MangaOffline.extension("image/jpeg"));
        assertEquals("jpg", MangaOffline.extension(""));
        assertEquals("webp", MangaOffline.extension("image/webp"));
        assertEquals("avif", MangaOffline.extension("IMAGE/AVIF"));
        assertEquals("png", MangaOffline.extension("image/png; charset=binary"));
        // An error page is not a page, whatever its name.
        assertEquals("", MangaOffline.extension("text/html"));
        assertEquals("", MangaOffline.extension("application/json"));

        assertEquals("image/png", MangaOffline.contentType("0003.png"));
        assertEquals("image/webp", MangaOffline.contentType("0003.webp"));
        assertEquals("image/jpeg", MangaOffline.contentType("0003.jpg"));
        assertEquals("image/jpeg", MangaOffline.contentType("0003.img"));
    }

    @Test
    public void aPageUrlNamesTheChapterAndThePageOnLoopback() {
        assertEquals(
                "http://127.0.0.1:" + SekaiServer.PORT + "/manga/offline?chapter=9051&page=3",
                MangaOffline.pageUrl("9051", 3));
    }

    /**
     * A downloaded page is a file, not a request: the reader hands these to the
     * image loader with the radio off, and an `http` URL is one the loader will
     * not open without a network - loopback included.
     */
    @Test
    public void aDownloadedPageIsAFileUrlBesideItsRow() throws Exception {
        MangaOffline.Chapter saved = MangaOffline.read(storeWithOneChapter())
                .chapters.get("9051");
        assertNotNull(saved);
        assertEquals(
                "file://" + new File(saved.dir, "0002.jpg").getAbsolutePath(),
                MangaOffline.fileUrl(saved, 2));
        // On the phone the store is under /data/..., so the URL reads file:///data/...
        // A JVM run on Windows spells the same URL file://F:/... - what the loader
        // needs is the scheme followed straight by an absolute path, nothing else.
        assertTrue(new File(saved.dir, "0002.jpg").isAbsolute());
        assertEquals("", MangaOffline.fileUrl(saved, 3));
        assertEquals("", MangaOffline.fileUrl(null, 1));
    }
}
