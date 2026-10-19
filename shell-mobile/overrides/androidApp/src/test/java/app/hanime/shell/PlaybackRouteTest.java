package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-app playback plumbing, held to a JVM.
 *
 * Every shelf's sources now end in the same two pieces — a door or an embed
 * resolved to a real media URL, and {@link HlsRelay} serving it from loopback —
 * and each of those has a decision in it that a device would otherwise be the
 * only way to check:
 *
 *   * which door is tried first, and what URL a door is handed (cinehd's own
 *     six, with the door that last worked at the front);
 *   * whether an embed is flixcloud's (it needs the payload resolver) or
 *     somebody else's player page (it needs {@link Doors});
 *   * how a flixcloud segment is unwrapped, which is the difference between
 *     video and a wall of static;
 *   * and that a route which matches nothing answers 404 without dialling
 *     anything.
 *
 * What is deliberately *not* here: anything that needs the relay's link
 * building, since that goes through `android.util.Base64` and a unit test's
 * android.jar is a stub. The relay's own wiring is checked on a device
 * (`adb logcat -s ShellRelay`, and a `curl` of the link it hands out).
 */
public class PlaybackRouteTest {

    /** The six cinehd doors, in cinehd's order, as the picker lists them. */
    private static final String[] DOOR_IDS = {
            "max", "vidpro", "v2", "premium", "4k", "vidfast",
    };

    @Test
    public void theInAppWalkStartsWithTheDoorCinehdPutsFirst() {
        List<Fmovies.Door> order = Fmovies.inAppOrder(null);
        assertEquals(DOOR_IDS.length, order.size());
        // Not simply cinehd's first entry: the in-app walk prefers Vidpro
        // (vixsrc), the door the desktop's own player reaches for first.
        assertEquals("vidpro", order.get(0).id);
    }

    @Test
    public void aRememberedDoorGoesToTheFrontAndTheOthersKeepTheirOrder() {
        List<Fmovies.Door> order = Fmovies.inAppOrder("vidfast");
        assertEquals("vidfast", order.get(0).id);

        List<String> rest = new ArrayList<>();
        for (int i = 1; i < order.size(); i++) rest.add(order.get(i).id);
        assertEquals(List.of("max", "vidpro", "v2", "premium", "4k"), rest);
    }

    @Test
    public void aDoorMayBeNamedByItsLabelTooAndAnUnknownOneIsNotAnError() {
        assertEquals("vidpro", Fmovies.inAppOrder("Vidpro").get(0).id);
        assertEquals("vidpro", Fmovies.inAppOrder("no-such-door").get(0).id);
    }

    @Test
    public void everyDoorIsListedExactlyOnce() {
        List<String> seen = new ArrayList<>();
        for (Fmovies.Door door : Fmovies.inAppOrder(null)) seen.add(door.id);
        // The preferred door moved to the front, and nothing else did.
        assertEquals(DOOR_IDS.length, seen.size());
        for (String id : DOOR_IDS) {
            assertEquals(id + " is listed once, not twice", 1, count(seen, id));
        }
    }

    private static int count(List<String> values, String wanted) {
        int seen = 0;
        for (String value : values) {
            if (value.equals(wanted)) seen++;
        }
        return seen;
    }

    @Test
    public void aDoorUrlIsCinehdsTemplateWithTheTmdbIdFilledIn() {
        for (Fmovies.Door door : Fmovies.inAppOrder(null)) {
            String movie = door.url("movie", 1396, 1, 1);
            String tv = door.url("tv", 1396, 2, 7);
            assertFalse(door.id + " left a placeholder: " + movie, movie.contains("{"));
            assertFalse(door.id + " left a placeholder: " + tv, tv.contains("{"));
            assertTrue(door.id + " lost the id: " + movie, movie.contains("1396"));
            assertTrue(door.id + " is not a player page: " + movie, movie.startsWith("https://"));
            assertTrue(door.id + " lost the season: " + tv, tv.contains("2"));
            assertTrue(door.id + " lost the episode: " + tv, tv.contains("7"));
        }
    }

    @Test
    public void aDoorUrlCarriesTheRefererTheCdnWillDemand() {
        assertEquals("https://vixsrc.to/",
                Doors.originOf("https://vixsrc.to/movie/1396"));
        assertEquals("https://vidfast.vc/",
                Doors.originOf("https://vidfast.vc/tv/1396/2/7?autoplay=true"));
        assertNull(Doors.originOf("not a url"));
    }

    @Test
    public void aFlixcloudEmbedIsRoutedToThePayloadResolverAndNothingElseIs() {
        assertTrue(FlixCloud.isEmbed("https://flixcloud.cc/e/AbC-123_x"));
        assertTrue(FlixCloud.isEmbed("https://www.flixcloud.cc/e/AbC-123_x"));
        // Their API and their root are not embeds; neither is another door.
        assertFalse(FlixCloud.isEmbed("https://flixcloud.cc/api/m3u8/token"));
        assertFalse(FlixCloud.isEmbed("https://flixcloud.cc/e/"));
        assertFalse(FlixCloud.isEmbed("https://vixsrc.to/movie/1396"));
        assertFalse(FlixCloud.isEmbed(null));
    }

    @Test
    public void aFlixcloudSegmentArrivesUnderAnImageHeaderAndIsUnwrapped() {
        byte[] key = {
                (byte) 157, 42, (byte) 241, 71, (byte) 179, (byte) 142, 92, 112,
                (byte) 166, 25, (byte) 228, 59, (byte) 216, 98, 15, (byte) 197,
        };
        byte[] plain = new byte[194];
        plain[0] = 0x47;                       // an MPEG-TS sync byte
        for (int i = 1; i < plain.length; i++) plain[i] = (byte) (i & 0x7F);

        byte[] cased = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) cased[i] = (byte) (plain[i] ^ key[i & 15]);

        byte[] body = new byte[8 + cased.length];
        byte[] png = {(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10};
        System.arraycopy(png, 0, body, 0, 8);
        System.arraycopy(cased, 0, body, 8, cased.length);

        assertEquals(bytesToHex(plain), bytesToHex(HlsRelay.unsealSegment(body)));
    }

    @Test
    public void aSegmentThatIsNotWrappedIsNotClaimedToBe() {
        byte[] plain = new byte[194];
        plain[0] = 0x47;
        // No image header, so there is nothing this can unwrap.
        assertNull(HlsRelay.unsealSegment(plain));

        // A header, but the body is not the provider's keystream.
        byte[] zeros = new byte[8 + 194];
        zeros[0] = (byte) 137;
        zeros[1] = 'P';
        assertNull(HlsRelay.unsealSegment(zeros));

        // Too short to be a segment at all.
        byte[] small = {(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10, 0x47};
        assertNull(HlsRelay.unsealSegment(small));
    }

    @Test
    public void theVixsrcDoorIsTheOneDoorThatNeedsNoBrowser() {
        assertTrue(Vixsrc.isDoor("https://vixsrc.to/movie/1396"));
        assertTrue(Vixsrc.isDoor("https://vixsrc.to/tv/1396/2/7"));
        // Not a door: another host, the site's own home page, an id-less path.
        assertFalse(Vixsrc.isDoor("https://ythd.org/embed/1396"));
        assertFalse(Vixsrc.isDoor("https://vixsrc.to/"));
        assertFalse(Vixsrc.isDoor("https://vixsrc.to/movie"));
        assertFalse(Vixsrc.isDoor(null));
        // A series page with no season and episode is still a door: the site
        // shows its first episode there, so that is what it is asked for.
        assertTrue(Vixsrc.isDoor("https://vixsrc.to/tv/1396"));
    }

    @Test
    public void aVixsrcEmbedNamesItsMasterPlaylistInTwoSpellings() {
        // window.masterPlaylist — the shape a movie's embed page has.
        String master = "<script>window.masterPlaylist = {\n"
                + "   'expires': '1797552630',\n"
                + "   url: 'https://vixsrc.to/playlist/793125?b=1',\n"
                + "}</script>";
        assertEquals("https://vixsrc.to/playlist/793125?b=1", Vixsrc.playlistIn(master));

        // window.streams — JSON-escaped, and the fallback when the master is
        // not spelled out; the id alone is enough to rebuild the URL.
        String streams = "window.streams = [{\"name\":\"Server1\",\"active\":false,"
                + "\"url\":\"https:\\\\/\\\\/vixsrc.to\\\\/playlist\\\\/278687?b=1\\\\u0026ub=1\"}];";
        assertEquals("https://vixsrc.to/playlist/278687?b=1&ub=1", Vixsrc.playlistIn(streams));

        assertNull(Vixsrc.playlistIn("<html>nothing here</html>"));
        assertNull(Vixsrc.playlistIn(null));
    }

    @Test
    public void aRouteThatMatchesNothingAnswers404WithoutDialling() throws Exception {
        Fmovies.Result result = Fmovies.handle("/api/fmovies/nope", new HashMap<>());
        assertEquals(404, result.status);
        assertEquals("not_found", new JSONObject(result.body).optString("error"));

        Fmovies.Result bare = Fmovies.handle("/api/fmovies/movie", new HashMap<>());
        assertEquals(404, bare.status);

        Map<String, String> query = new HashMap<>();
        query.put("season", "2");
        Fmovies.Result wrongKind = Fmovies.handle("/api/fmovies/movie/x-1396/episodes", query);
        assertNotNull(wrongKind);
        assertEquals(404, wrongKind.status);
    }

    private static String bytesToHex(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
