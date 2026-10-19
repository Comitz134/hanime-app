package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The subtitle route's parsing, held to a JVM.
 *
 * Every track this app offers for anime comes off the provider's own embed
 * page, and every one for a film comes from an addon keyed by an id the slug
 * does not carry — so the parts worth pinning are:
 *
 *   * the page's `subtitles:[…]` list, which is a JavaScript object literal, not
 *     JSON (bare keys, single-quoted neighbours), and has to read as "none"
 *     when it is absent rather than as a failure;
 *   * the language: the page names a track the way a person would
 *     ('English (Dialogues [SoM])'), while a client matches a preference
 *     against a code, and the regional spellings have to keep their region;
 *   * the slug's TMDB id, which is what the subtitle source is keyed by.
 *
 * What is *not* here: `Subtitles.tracks` itself, since it needs a Context, a
 * request to LunarX and one to OpenSubtitles — that half is checked on the
 * device (`adb logcat -s ShellSubs`, and a `curl` of the route).
 */
public class SubtitleRouteTest {

    /** An embed page's payload, cut to the part the parser reads. */
    private static final String PAGE = "window.__cfg = {\n"
            + "  obfuscation_seed:\"a1b2c3d4\",\n"
            + "  w_payload:\"AAAA\",\n"
            + "  subtitles:[\n"
            + "    {url:\"https://cdn.example/one_eng_3.ass\",language:\"English (Dialogues [SoM])\",format:\"ass\",default:true},\n"
            + "    {url:\"https://cdn.example/one_por_1.srt\",language:\"Portuguese(Brazil)\",default:false},\n"
            + "    {url:\"not-a-url\",language:\"English\"}\n"
            + "  ],\n"
            + "  key_tag:\"zz\",\n"
            + "};\n";

    @Test
    public void anEmbedPageNamesItsOwnSubtitleFiles() {
        List<FlixCloud.Subtitle> tracks = FlixCloud.parseSubtitles(PAGE);
        assertEquals(2, tracks.size());

        FlixCloud.Subtitle first = tracks.get(0);
        assertEquals("https://cdn.example/one_eng_3.ass", first.url);
        assertEquals("en", first.lang);
        assertEquals("Dialogues [SoM]", first.label);
        assertEquals("ass", first.format);
        assertTrue(first.preferred);

        // A regional language is not the plain one, and a missing format is
        // read off the file name.
        FlixCloud.Subtitle second = tracks.get(1);
        assertEquals("pt-BR", second.lang);
        assertEquals("srt", second.format);
        assertEquals(false, second.preferred);
    }

    @Test
    public void aPageWithoutSubtitlesAnswersNoneRatherThanFailing() {
        assertEquals(0, FlixCloud.parseSubtitles("window.__cfg = { seed:\"x\" };\n").size());
        assertEquals(0, FlixCloud.parseSubtitles("").size());
        assertEquals(0, FlixCloud.parseSubtitles(null).size());
        // The key is there but the array never opens: still none.
        assertEquals(0, FlixCloud.parseSubtitles("subtitles: oops").size());
    }

    @Test
    public void thePagesWordsBecomeTheCodesAPickerMatchesAgainst() {
        assertEquals("en", FlixCloud.languageCodeOf("English (Dialogues [SoM])"));
        assertEquals("es-419", FlixCloud.languageCodeOf("Spanish(Latin_America)"));
        assertEquals("es", FlixCloud.languageCodeOf("Spanish"));
        assertEquals("zh-TW", FlixCloud.languageCodeOf("Chinese(Traditional)"));
        assertEquals("zh-CN", FlixCloud.languageCodeOf("Chinese"));
        assertEquals("pt", FlixCloud.languageCodeOf("Portuguese"));
        assertEquals("ko", FlixCloud.languageCodeOf("Korean"));
        // An unrecognised name travels as it arrived — it is not invented.
        assertEquals("klingon", FlixCloud.languageCodeOf("Klingon"));
        assertEquals("unknown", FlixCloud.languageCodeOf(null));
    }

    @Test
    public void theParenthesisIsWhatTellsTwoTracksInOneLanguageApart() {
        assertEquals("Dialogues [SoM]", FlixCloud.languageDetailOf("English (Dialogues [SoM])"));
        assertEquals("Brazil", FlixCloud.languageDetailOf("Portuguese(Brazil)"));
        assertNull(FlixCloud.languageDetailOf("English"));
        assertNull(FlixCloud.languageDetailOf(null));
    }

    @Test
    public void openSubtitlesSpeaksIso639TwoAndAClientMatchesOne() {
        assertEquals("en", OpenSubs.languageCode("eng"));
        assertEquals("de", OpenSubs.languageCode("ger"));
        assertEquals("de", OpenSubs.languageCode("deu"));
        assertEquals("fr", OpenSubs.languageCode("fre"));
        assertEquals("pt", OpenSubs.languageCode("por"));
        assertEquals("zh", OpenSubs.languageCode("zho"));
        // Unmapped passes through, empty is named.
        assertEquals("xyz", OpenSubs.languageCode("xyz"));
        assertEquals("unknown", OpenSubs.languageCode(""));
        assertEquals("unknown", OpenSubs.languageCode(null));
    }

    @Test
    public void aSlugCarriesTheTmdbIdTheSubtitleSourceNeeds() {
        Integer id = Fmovies.tmdbIdOf("spider-man-brand-new-day-969681");
        assertEquals(Integer.valueOf(969681), id);
        assertEquals(Integer.valueOf(1396), Fmovies.tmdbIdOf("breaking-bad-1396"));
        assertNull(Fmovies.tmdbIdOf("no-id-here"));
        assertNull(Fmovies.tmdbIdOf(null));
    }
}
