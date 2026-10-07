package app.hanime.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pure half of the updater: which manifest URL a check consults, and how
 * an address becomes one. This is the logic behind the stable/beta channel
 * switch and the §6.1 fallback — decided without a device, so a regression
 * here is a failing test rather than a strand in the field.
 */
public class UpdaterSourceTest {

    private static final String STABLE =
            "https://example.com/releases/latest/download/version.json";
    private static final String BETA =
            "https://example.com/releases/download/channel-beta/version.json";

    // ------------------------------------------------------------- channels

    @Test
    public void noOverrideAndStableChannelChecksStable() {
        assertEquals(STABLE, Updater.selectSource("", Updater.CHANNEL_STABLE, STABLE, BETA));
    }

    @Test
    public void betaChannelSelectsTheBetaManifest() {
        assertEquals(BETA, Updater.selectSource("", Updater.CHANNEL_BETA, STABLE, BETA));
    }

    @Test
    public void aSavedAddressWinsOverEveryChannel() {
        String saved = "http://10.0.2.2:8787/version.json";
        assertEquals(saved, Updater.selectSource(saved, Updater.CHANNEL_BETA, STABLE, BETA));
        assertEquals(saved, Updater.selectSource(saved, Updater.CHANNEL_STABLE, STABLE, BETA));
    }

    @Test
    public void whitespaceSavedAddressIsNoAddress() {
        assertEquals(STABLE, Updater.selectSource("   ", Updater.CHANNEL_STABLE, STABLE, BETA));
    }

    @Test
    public void anUnknownChannelFallsBackToStable() {
        assertEquals(STABLE, Updater.selectSource("", "nightly", STABLE, BETA));
        assertEquals(STABLE, Updater.builtInFor(null, STABLE, BETA));
        assertEquals(BETA, Updater.builtInFor(Updater.CHANNEL_BETA, STABLE, BETA));
        assertEquals(STABLE, Updater.builtInFor(Updater.CHANNEL_STABLE, STABLE, BETA));
    }

    @Test
    public void theCompiledBetaUrlIsARollingChannelAsset() {
        // The published contract with publish-github.mjs: the app's beta URL
        // must be the rolling channel-beta manifest (refreshed on every
        // publish), never empty (a channel that reports a phantom connection
        // failure) and never version-pinned (which would freeze the channel).
        String beta = BuildConfig.BETA_URL.trim();
        assertFalse("BETA_URL is empty: switching to beta would strand the check", beta.isEmpty());
        assertTrue("the updater only skips the base path for .json", beta.endsWith("/version.json"));
        assertTrue("BETA_URL is not the channel-beta asset", beta.contains("channel-beta"));
        assertFalse("beta must not alias the stable route", beta.contains("/releases/latest/"));
    }

    @Test
    public void theStableBuiltInIsStillTheBuildTimeUrl() {
        assertEquals(BuildConfig.UPDATE_URL,
                Updater.builtInFor(Updater.CHANNEL_STABLE, BuildConfig.UPDATE_URL, BuildConfig.BETA_URL));
        assertEquals(BuildConfig.BETA_URL,
                Updater.builtInFor(Updater.CHANNEL_BETA, BuildConfig.UPDATE_URL, BuildConfig.BETA_URL));
    }

    // -------------------------------------------------------- manifest URLs

    @Test
    public void aJsonUrlIsItsOwnManifest() {
        assertEquals("https://example.com/version.json",
                Updater.manifestUrl("https://example.com/version.json"));
    }

    @Test
    public void aBareAddressImpliesTheApiRoute() {
        assertEquals("http://host:8787/api/app/version",
                Updater.manifestUrl("http://host:8787"));
        assertEquals("http://host:8787/api/app/version",
                Updater.manifestUrl("http://host:8787/"));
    }

    @Test
    public void anEmptyAddressMeansNoManifestRatherThanADeadHost() {
        assertNull(Updater.manifestUrl(""));
        assertNull(Updater.manifestUrl("   "));
        assertNull(Updater.manifestUrl(null));
    }

    @Test
    public void aRelativeApkUrlResolvesAgainstTheAnsweringSource() {
        assertEquals("https://example.com/r/a.apk",
                Updater.resolve("https://example.com/r", "/a.apk"));
        assertEquals("https://example.com/r/a.apk",
                Updater.resolve("https://example.com/r", "a.apk"));
        assertEquals("https://cdn.example.com/a.apk",
                Updater.resolve("https://example.com/r", "https://cdn.example.com/a.apk"));
    }
}
