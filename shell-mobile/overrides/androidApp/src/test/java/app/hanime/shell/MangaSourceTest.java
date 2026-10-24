package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * The source layer, held to a JVM.
 *
 * None of this needs a phone: a repository is a JSON document or a gzipped
 * protobuf, a source is a script or an extension package, and the sandbox is a
 * Java library. The things a phone would hide are the ones worth pinning here -
 * that a Mihon index is read down to the extension and its sources, that a
 * source is only installed once it is switched on, and that a script's answer
 * is shaped the way the reader draws it before it ever reaches the screen.
 */
public class MangaSourceTest {

    private static final String DEMO_SCRIPT =
            "function search(q, page) {\n"
                    + "  return { items: [\n"
                    + "    { id: 'one', title: 'First', poster: 'https://x/1.jpg' },\n"
                    + "    { id: 'two', title: 'Second', poster: 'https://x/2.jpg' }\n"
                    + "  ], hasNext: false };\n"
                    + "}\n"
                    + "function popular(page) { return search('', page); }\n"
                    + "function title(id) { return { id: id, title: 'First', poster: '' }; }\n"
                    + "function chapters(id) {\n"
                    + "  return { chapters: [\n"
                    + "    { id: 'c1', number: '1', name: 'One', at: '2026-01-01', created: 1767225600 }\n"
                    + "  ] };\n"
                    + "}\n"
                    + "function pages(chapterId) {\n"
                    + "  return { pages: ['https://x/p1.jpg', 'https://x/p2.jpg'] };\n"
                    + "}\n";

    @After
    public void tearDown() {
        MangaSources.resetForTest();
    }

    private static MangaSources.Source source(String id, String script) {
        MangaSources.Source source = new MangaSources.Source();
        source.id = id;
        source.name = id;
        source.script = script;
        source.enabled = true;
        source.repo = "test://repo";
        return source;
    }

    // --------------------------------------------------------- repository

    @Test
    public void aSekaiRepositoryIsAnIndexWithScripts() throws Exception {
        String index = "{\n"
                + "  \"format\": \"sekai-manga-sources\",\n"
                + "  \"name\": \"Demo\",\n"
                + "  \"sources\": [\n"
                + "    { \"id\": \"demo\", \"name\": \"Demo source\", \"lang\": \"en\",\n"
                + "      \"version\": \"1.2.3\", \"script\": \"demo.js\" }\n"
                + "  ]\n"
                + "}";
        MangaSources.fetch = url -> {
            assertEquals("https://example.test/repo/demo.js", url);
            return DEMO_SCRIPT.getBytes(StandardCharsets.UTF_8);
        };

        MangaSources.Repo repo = MangaSources.parseIndex(
                "https://example.test/repo/index.json",
                index.getBytes(StandardCharsets.UTF_8),
                false);

        assertEquals("Demo", repo.name);
        assertEquals(1, repo.sources.size());
        MangaSources.Source source = repo.sources.get(0);
        assertEquals("demo", source.id);
        assertEquals("1.2.3", source.version);
        assertFalse(source.isNative());
        assertTrue(source.script.contains("function search"));
    }

    // ------------------------------------------------------- mihon index

    /**
     * A Mihon protobuf index, byte for byte.
     *
     * The layout is the one keiyoushi publishes: field 1 name, 2 shortName,
     * 3 signingKeyFingerprint, and 101 a single wrapper whose field 1 repeats
     * once per extension. An extension names its package and version, keeps its
     * addresses in field 3 (apk then icon), and repeats field 8 once per source,
     * where a source is a 64-bit id, a name, a language and a base URL.
     */
    private static byte[] mihonIndex() throws Exception {
        byte[] source = cat(
                varintField(1, 6289731484943315811L),
                text(2, "Akuma"),
                text(3, "en"),
                text(4, "https://akuma.moe"));
        byte[] extension = cat(
                text(1, "Akuma"),
                text(2, "eu.kanade.tachiyomi.extension.all.akuma"),
                text(3, cat(text(1, "tachiyomi-all.akuma-v1.6.10.apk"), text(2, "https://cdn.test/akuma.png"))),
                varintField(5, 106010L),
                text(6, "1.6.10"),
                varintField(7, 3L),
                text(8, source));
        byte[] body = cat(
                text(1, "Keiyoushi"),
                text(2, "KEI"),
                text(3, "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"),
                text(101, cat(text(1, extension))));
        return gzip(body);
    }

    @Test
    public void aMihonProtobufIndexReadsDownToItsExtensionsAndSources() throws Exception {
        MangaSources.Repo repo = MangaSources.parseIndex(
                "https://example.test/repo/index.pb", mihonIndex(), false);

        assertEquals("mihon", repo.format);
        assertEquals("Keiyoushi", repo.name);
        assertTrue(repo.fingerprint.startsWith("9add655a"));
        assertEquals(1, repo.extensions.size());
        // Nothing is installed until the reader picks it.
        assertEquals(0, repo.sources.size());
        assertEquals(1, repo.totalOf());

        MangaSources.Extension extension = repo.extensions.get(0);
        assertEquals("Akuma", extension.name);
        assertEquals("eu.kanade.tachiyomi.extension.all.akuma", extension.pkg);
        assertEquals("1.6.10", extension.version);
        assertEquals(3, extension.contentWarning);
        assertEquals("https://example.test/repo/tachiyomi-all.akuma-v1.6.10.apk", extension.apk);
        assertEquals(1, extension.sources.size());

        MangaSources.Source akuma = extension.sources.get(0);
        assertEquals("e6289731484943315811", akuma.id);
        assertEquals("Akuma", akuma.name);
        assertEquals("en", akuma.lang);
        assertEquals("https://akuma.moe", akuma.baseUrl);
        assertFalse(akuma.enabled);
        assertTrue(akuma.isExtension());
    }

    @Test
    public void aMihonJsonIndexIsAnExtensionList() throws Exception {
        String index = "[{\"name\":\"Manga Demon\","
                + "\"pkg\":\"eu.kanade.tachiyomi.extension.en.mangademon\","
                + "\"apk\":\"tachiyomi-en.mangademon-v1.4.12.apk\",\"version\":\"1.4.12\","
                + "\"code\":14,\"lang\":\"en\",\"nsfw\":0,"
                + "\"sources\":[{\"id\":\"42\",\"name\":\"Manga Demon\","
                + "\"lang\":\"en\",\"baseUrl\":\"https://demon.test\"}]}]";

        MangaSources.Repo repo = MangaSources.parseIndex(
                "https://example.test/repo/index.min.json",
                index.getBytes(StandardCharsets.UTF_8),
                false);

        assertEquals("mihon", repo.format);
        assertEquals(1, repo.extensions.size());
        MangaSources.Extension extension = repo.extensions.get(0);
        assertEquals("eu.kanade.tachiyomi.extension.en.mangademon", extension.pkg);
        assertEquals("https://example.test/repo/tachiyomi-en.mangademon-v1.4.12.apk", extension.apk);
        assertEquals("e42", extension.sources.get(0).id);
    }

    /**
     * A repository installs nothing; a switch installs its sources.
     *
     * The picker opens a repository, shows what it offers, and writes what the
     * reader picks. This walks that: add, open (nothing on), switch one source
     * on, open again, switch the whole extension off again.
     */
    @Test
    public void aMihonSourceIsInstalledOnlyWhenItIsSwitchedOn() throws Exception {
        MangaSources.fetch = url -> mihonIndex();
        String url = "https://example.test/repo/index.pb";

        JSONObject added = MangaSources.addRepo(url);
        JSONArray repos = added.getJSONArray("repos");
        JSONObject row = repos.getJSONObject(repos.length() - 1);
        assertEquals("mihon", row.getString("format"));
        assertEquals(1, row.getInt("count"));
        assertEquals(0, row.getInt("installed"));
        assertEquals(1, row.getInt("extensions"));
        // The only installed source is the app's own Mangafire; nothing from
        // the new repository is stored until a switch turns it on.
        assertEquals(0, sourcesOf(added, url).length());

        JSONObject opened = MangaSources.repoDetail(url);
        assertEquals("mihon", opened.getString("format"));
        assertEquals(1, opened.getInt("total"));
        assertEquals(0, opened.getInt("enabled"));
        JSONObject extension = opened.getJSONArray("extensions").getJSONObject(0);
        assertFalse(extension.getBoolean("enabled"));
        assertFalse(extension.getJSONArray("sources").getJSONObject(0).getBoolean("enabled"));

        MangaSources.toggle(url, "e6289731484943315811", true);
        opened = MangaSources.repoDetail(url);
        assertEquals(1, opened.getInt("enabled"));
        assertTrue(opened.getJSONArray("extensions").getJSONObject(0).getBoolean("enabled"));

        JSONObject switched = MangaSources.toggleExtension(
                url, "eu.kanade.tachiyomi.extension.all.akuma", false);
        assertEquals(0, switched.getInt("enabled"));
        assertEquals(0, MangaSources.repoDetail(url).getInt("enabled"));

        // An extension switched back on installs its sources again, with the
        // address the index gave it - the APK is fetched on first use.
        switched = MangaSources.toggleExtension(url, "eu.kanade.tachiyomi.extension.all.akuma", true);
        JSONObject again = switched.getJSONArray("extensions").getJSONObject(0);
        assertTrue(again.getBoolean("enabled"));
        assertEquals(1, switched.getInt("enabled"));
    }

    /** The sources one repository installed, out of a whole-shelf answer. */
    private static JSONArray sourcesOf(JSONObject shelf, String url) throws Exception {
        JSONArray all = shelf.optJSONArray("sources");
        JSONArray mine = new JSONArray();
        if (all == null) return mine;
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.optJSONObject(i);
            if (row != null && url.equals(row.optString("repo", ""))) mine.put(row);
        }
        return mine;
    }

    /**
     * The counts on the repository row survive the app being closed.
     *
     * The extension index is not stored - 1,400 packages do not belong in a
     * preferences file - so a repository read back at start-up has an empty
     * extension list and must still say how many extensions and sources it
     * holds, or the settings row would read "2 of 2 on" for a repository of
     * 2,422.
     */
    @Test
    public void aMihonRepositoryKeepsItsCountsAfterARestart() throws Exception {
        MangaSources.fetch = url -> mihonIndex();
        String url = "https://example.test/repo/index.pb";
        MangaSources.addRepo(url);
        MangaSources.toggle(url, "e6289731484943315811", true);

        String stored = MangaSources.storedForTest();
        MangaSources.resetForTest();
        MangaSources.loadForTest(stored);
        MangaSources.fetch = fetchUrl -> mihonIndex();

        JSONObject row = null;
        JSONArray repos = MangaSources.state().getJSONArray("repos");
        for (int i = 0; i < repos.length(); i++) {
            if (url.equals(repos.getJSONObject(i).optString("url"))) row = repos.getJSONObject(i);
        }
        assertNotNull(row);
        assertEquals("mihon", row.getString("format"));
        assertEquals(1, row.getInt("count"));
        assertEquals(1, row.getInt("extensions"));
        assertEquals(1, row.getInt("installed"));

        // Opening it again fetches the index, and what was switched on is still on.
        JSONObject opened = MangaSources.repoDetail(url);
        assertEquals(1, opened.getInt("enabled"));
        assertTrue(opened.getJSONArray("extensions").getJSONObject(0).getBoolean("enabled"));
    }

    // --------------------------------------------------- protobuf writing

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long left = value;
        while (true) {
            int bits = (int) (left & 0x7f);
            left >>>= 7;
            if (left == 0) {
                out.write(bits);
                return out.toByteArray();
            }
            out.write(bits | 0x80);
        }
    }

    /** A length-delimited field: a string, a message, or a nested index. */
    private static byte[] text(int number, byte[] bytes) {
        return cat(varint((number << 3) | 2), varint(bytes.length), bytes);
    }

    private static byte[] text(int number, String value) {
        return text(number, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] varintField(int number, long value) {
        return cat(varint(number << 3), varint(value));
    }

    private static byte[] gzip(byte[] body) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
            out.write(body);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------- sandbox

    @Test
    public void aScriptAnswersThroughTheHostAndNothingElse() throws Exception {
        MangaJs.fetch = (url, headers) -> {
            assertEquals("https://api.example.test/items", url);
            return "{\"items\":[{\"id\":\"a\",\"title\":\"From the network\"}]}";
        };
        String script = "function popular(page) {\n"
                + "  var body = JSON.parse(httpGet('https://api.example.test/items'));\n"
                + "  return { items: body.items, hasNext: false };\n"
                + "}\n";

        JSONObject answer = MangaJs.call(script, "popular", 1);
        assertEquals("a", answer.getJSONArray("items").getJSONObject(0).getString("id"));
        assertEquals("From the network",
                answer.getJSONArray("items").getJSONObject(0).getString("title"));
    }

    @Test
    public void javaIsNotReachableFromAScript() {
        String script = "function popular(page) { java.lang.System.exit(1); return {}; }\n";
        try {
            MangaJs.call(script, "popular", 1);
            fail("a script reached java.lang");
        } catch (Exception refused) {
            assertNotNull(refused.getMessage());
        }
    }

    // ------------------------------------------------------------- routing

    @Test
    public void aPrefixedIdBelongsToItsSourceAndABareOneIsMangafire() {
        MangaSources.installForTest(Collections.singletonList(source("demo", DEMO_SCRIPT)));

        MangaSources.Route scripted = MangaSources.route("demo:abc");
        assertEquals("demo", scripted.source.id);
        assertEquals("abc", scripted.raw);

        MangaSources.Route builtIn = MangaSources.route("ro8ro");
        assertTrue(builtIn.source.isNative());
        assertEquals("ro8ro", builtIn.raw);
    }

    @Test
    public void aTitleFromAnUninstalledSourceDoesNotFallThroughToMangafire() {
        MangaSources.installForTest(Collections.singletonList(source("demo", DEMO_SCRIPT)));
        try {
            MangaSources.route("gone:abc");
            fail("an uninstalled source's id was sent to Mangafire");
        } catch (Exception refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("not installed"));
        }
    }

    @Test
    public void chaptersAndPagesCarryTheirSourcePrefix() throws Exception {
        MangaSources.Source demo = source("demo", DEMO_SCRIPT);

        JSONArray chapters = MangaSources.jsChapters(demo, "title-1");
        assertEquals("demo:c1", chapters.getJSONObject(0).getString("id"));

        JSONObject pages = new JSONObject()
                .put("pages", new JSONArray().put("https://x/1.jpg").put("https://x/2.jpg"));
        JSONArray urls = MangaSources.jsPageUrls(demo, "demo:c1");
        assertEquals(2, urls.length());
    }

    // ------------------------------------------------------ mixed searching

    @Test
    public void aSearchAsksEveryEnabledSourceAndInterleavesThem() throws Exception {
        String a = "function search(q, page) { return { items: "
                + "[{id:'a1',title:'A one'},{id:'a2',title:'A two'}], hasNext: false }; }\n"
                + "function popular(page) { return search('', page); }\n";
        String b = "function search(q, page) { return { items: "
                + "[{id:'b1',title:'B one'},{id:'b2',title:'B two'}], hasNext: false }; }\n"
                + "function popular(page) { return search('', page); }\n";

        MangaSources.installForTest(Arrays.asList(
                source("alpha", a),
                source("beta", b)));

        JSONObject answer = MangaSources.search("one", 1, 24, "", "", "");
        JSONArray items = answer.getJSONArray("items");
        assertEquals(4, items.length());
        assertEquals("A one", items.getJSONObject(0).getString("title"));
        assertEquals("alpha:a1", items.getJSONObject(0).getString("id"));
        assertEquals("alpha", items.getJSONObject(0).getString("source"));
        assertEquals("B one", items.getJSONObject(1).getString("title"));
        assertEquals("beta:b1", items.getJSONObject(1).getString("id"));
        assertEquals("A two", items.getJSONObject(2).getString("title"));
    }

    @Test
    public void aDisabledSourceIsNotAsked() throws Exception {
        MangaSources.Source alpha = source("alpha", DEMO_SCRIPT);
        MangaSources.Source beta = source("beta", DEMO_SCRIPT);
        beta.enabled = false;
        MangaSources.installForTest(Arrays.asList(alpha, beta));

        JSONObject answer = MangaSources.search("one", 1, 24, "", "", "");
        assertEquals(2, answer.getJSONArray("items").length());
    }

    // ------------------------------------------------------- bundled source

    /**
     * The bundled MangaDex script, against canned answers.
     *
     * This is the test that keeps the shipped repository honest: if the script
     * stops compiling, or a field is renamed on one side of the contract, the
     * app would still build and the source would still list - and the failure
     * would only show as an empty grid on the phone.
     */
    @Test
    public void theBundledMangaDexScriptShapesCardsChaptersAndPages() throws Exception {
        File file = new File("src/main/assets/manga-repo/mangadex.js");
        assertTrue("the bundled script is missing", file.isFile());
        String script = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        MangaSources.Source dex = source("mangadex", script);

        String manga = "{\"result\":\"ok\",\"response\":\"collection\",\"data\":[{"
                + "\"id\":\"m-1\",\"type\":\"manga\",\"attributes\":{"
                + "\"title\":{\"ko-ro\":\"Solo\"},\"altTitles\":[{\"en\":\"Solo Leveling\"}],"
                + "\"description\":{\"en\":\"A <b>hunter</b> levels up.\"},"
                + "\"status\":\"ongoing\",\"year\":2018,"
                + "\"tags\":[{\"attributes\":{\"name\":{\"en\":\"Action\"}}}]},"
                + "\"relationships\":[{\"type\":\"cover_art\","
                + "\"attributes\":{\"fileName\":\"cover.png\"}}]}],"
                + "\"offset\":0,\"total\":1}";
        String feed = "{\"result\":\"ok\",\"data\":["
                + "{\"id\":\"c-1\",\"attributes\":{\"chapter\":\"15\",\"title\":\"Boss\","
                + "\"publishAt\":\"2023-09-05T17:14:12+00:00\",\"pages\":18,\"externalUrl\":null}},"
                + "{\"id\":\"c-2\",\"attributes\":{\"chapter\":\"14\",\"title\":\"External\","
                + "\"publishAt\":\"2023-09-01T17:14:12+00:00\",\"pages\":0,"
                + "\"externalUrl\":\"https://example.test/read\"}}]}";
        String atHome = "{\"result\":\"ok\",\"baseUrl\":\"https://cdn.example.test\","
                + "\"chapter\":{\"hash\":\"hash\",\"dataSaver\":[\"1.jpg\",\"2.jpg\"]}}";

        MangaJs.fetch = (url, headers) -> {
            if (url.contains("/at-home/")) return atHome;
            if (url.contains("/feed")) return feed;
            return manga;
        };

        JSONObject popular = MangaJs.call(script, "popular", 1);
        JSONArray items = popular.getJSONArray("items");
        assertEquals(1, items.length());
        JSONObject card = items.getJSONObject(0);
        assertEquals("m-1", card.getString("id"));
        assertEquals("Solo Leveling", card.getString("title"));
        assertEquals("https://uploads.mangadex.org/covers/m-1/cover.png.512.jpg",
                card.getString("poster"));
        assertEquals("Releasing", card.getString("status"));
        assertEquals(2018, card.getInt("year"));
        assertEquals("A hunter levels up.", card.getString("description"));
        assertEquals("Action", card.getJSONArray("genres").getString(0));
        assertFalse(popular.getBoolean("hasNext"));

        JSONArray chapters = MangaSources.jsChapters(dex, "m-1");
        assertEquals(1, chapters.length());
        JSONObject chapter = chapters.getJSONObject(0);
        assertEquals("mangadex:c-1", chapter.getString("id"));
        assertEquals("15", chapter.getString("number"));
        assertEquals("2023-09-05", chapter.getString("at"));
        assertEquals(1693934052L, chapter.getLong("created"));

        JSONArray pages = MangaSources.jsPageUrls(dex, "mangadex:c-1");
        assertEquals(2, pages.length());
        assertEquals("https://cdn.example.test/data-saver/hash/1.jpg", pages.getString(0));
    }
}
