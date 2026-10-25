package app.hanime.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;

import com.nuvio.app.features.manga.ext.MihonExtensions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The manga sources installed on this phone, and the repositories they came from.
 *
 * One source is one catalogue. Mangafire is built in and native — its API needs
 * a signing WebView, so its code lives here rather than in a script. Everything
 * else is a JavaScript file a repository hands over, run by [MangaJs]. That
 * split is deliberate: a repository can extend the app, but it cannot reach
 * into it.
 *
 * A repository is an `index.json`:
 *
 * <pre>
 * {
 *   "format": "sekai-manga-sources",
 *   "name": "My sources",
 *   "sources": [
 *     { "id": "example", "name": "Example", "lang": "en",
 *       "script": "example.js" }
 *   ]
 * }
 * </pre>
 *
 * and a script answers five functions (`popular`, `search`, `title`,
 * `chapters`, `pages`) with JSON objects. The README's *Manga sources*
 * section is the contract; this class is the collection around it.
 *
 * A Mihon repository is the other kind: `index.pb` (a gzipped protobuf) or
 * `index.min.json`, both read by [MangaRepoIndex]. Its entries are not scripts
 * but Android extension APKs, so what a repository holds and what is installed
 * are two different lists - every extension it offers is named here, and only
 * the sources the reader switches on are stored as sources. An extension row is
 * a package with a switch; the APK is downloaded and loaded by
 * [MihonExtensions] the first time one of its sources is asked a question.
 */
final class MangaSources {

    private static final String TAG = "ShellMangaSource";

    /** The preference file the manga area already uses; positions and marks live here too. */
    private static final String PREFS = "sekai-manga";
    private static final String KEY = "sources.v1";

    /** The built-in repository's address. Not fetched: its files are in the APK. */
    static final String BUILTIN = "builtin://sekai";

    private static final int MAX_REPOS = 20;
    private static final int MAX_SOURCES = 200;
    private static final int MAX_SCRIPT = 256 * 1024;
    private static final int MAX_ICON = 1024;

    private static Context application;
    private static boolean loaded;
    private static final List<Repo> repos = new ArrayList<>();

    private MangaSources() {
    }

    // ------------------------------------------------------------- the model

    /** One catalogue the app can read. */
    static final class Source {
        String id = "";
        String name = "";
        String lang = "en";
        String version = "";
        String description = "";
        String icon = "";
        String repo = "";
        String script = "";
        String nativeId = "";
        /** A Mihon extension package, when this source comes from one. */
        String pkg = "";
        /** The extension's display name, for the row that groups its sources. */
        String extension = "";
        /** The extension APK's address, so it can be installed on first use. */
        String apk = "";
        /** The source's own 64-bit id inside that extension. */
        long sourceId;
        String baseUrl = "";
        boolean enabled = true;

        boolean isNative() {
            return !nativeId.isEmpty();
        }

        boolean isExtension() {
            return !pkg.isEmpty();
        }

        JSONObject json() throws JSONException {
            JSONObject out = new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("lang", lang)
                    .put("version", version)
                    .put("description", description)
                    .put("icon", icon)
                    .put("repo", repo)
                    .put("enabled", enabled)
                    .put("native", isNative());
            if (isExtension()) {
                out.put("extension", true)
                        .put("pkg", pkg)
                        .put("extensionName", extension)
                        .put("apk", apk)
                        .put("baseUrl", baseUrl);
            }
            return out;
        }
    }

    /** One installed extension package and the sources it offers. */
    static final class Extension {
        String name = "";
        String pkg = "";
        String version = "";
        String apk = "";
        String icon = "";
        int contentWarning;
        final List<Source> sources = new ArrayList<>();
    }

    /** One repository, and the sources it installed. */
    static final class Repo {
        String url = "";
        String name = "";
        String version = "";
        /** "sekai", "native" or "mihon" - which reader knows this index. */
        String format = "sekai";
        /** The key a Mihon repository signs its APKs with, when it declares one. */
        String fingerprint = "";
        boolean builtin;
        long updated;
        String error = "";
        /** Every source the repository offers - stored count, not the installed list. */
        int totalSources;
        /**
         * How many extension packages the index listed.
         *
         * Held as a number rather than read off [extensions]: the index itself
         * is the session's (a Mihon repository can list 1,400 packages, which do
         * not belong in a preferences file), so after a restart the list is
         * empty while the repository still has this many.
         */
        int totalExtensions;
        final List<Source> sources = new ArrayList<>();
        /** The whole Mihon index, held for the session and fetched again when stale. */
        final List<Extension> extensions = new ArrayList<>();

        int totalOf() {
            return "mihon".equals(format) ? totalSources : sources.size();
        }
    }

    /** A title or chapter id, and the source that owns it. */
    static final class Route {
        final Source source;
        final String raw;

        Route(Source source, String raw) {
            this.source = source;
            this.raw = raw;
        }
    }

    /** A repository the reader can do something about, not a Java failure. */
    static final class BadRepoException extends Exception {
        BadRepoException(String message) {
            super(message);
        }
    }

    /** The built-in source, present even before the store has been read. */
    static Source nativeSource() {
        Source source = new Source();
        source.id = "mangafire";
        source.name = "Mangafire";
        source.lang = "en";
        source.nativeId = "mangafire";
        source.repo = BUILTIN;
        source.enabled = true;
        return source;
    }

    // --------------------------------------------------------------- reading

    /** Read the store once. A phone that has never opened this screen gets the built-in repo. */
    static synchronized void ensure(Context context) {
        if (context != null && application == null) {
            application = context.getApplicationContext();
            installExtensions(application);
        }
        if (loaded) return;
        loaded = true;

        SharedPreferences prefs = prefs();
        String raw = prefs == null ? null : prefs.getString(KEY, null);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                load(raw);
            } catch (Exception broken) {
                Log.w(TAG, "stored sources could not be read; reinstalling the built-in repo", broken);
                repos.clear();
            }
        }
        if (repos.isEmpty()) installBuiltin();
        persist();
    }

    private static void installExtensions(Context context) {
        int code = 1;
        String name = "";
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            code = info.versionCode;
            name = info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) {
            // A version is for a User-Agent string; the default is fine.
        }
        try {
            MihonExtensions.install(context, code, name);
        } catch (Throwable broken) {
            Log.w(TAG, "the extension runtime could not start", broken);
        }
    }

    private static void load(String raw) throws JSONException {
        JSONArray rows = new JSONArray(raw);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            Repo repo = new Repo();
            repo.url = row.optString("url", "");
            repo.name = row.optString("name", "");
            repo.version = row.optString("version", "");
            repo.builtin = row.optBoolean("builtin", false);
            repo.format = row.optString("format", "sekai");
            repo.fingerprint = row.optString("fingerprint", "");
            repo.totalSources = row.optInt("totalSources", 0);
            repo.totalExtensions = row.optInt("totalExtensions", 0);
            repo.updated = row.optLong("updated", 0L);
            JSONArray sources = row.optJSONArray("sources");
            if (sources == null) continue;
            for (int j = 0; j < sources.length(); j++) {
                JSONObject item = sources.optJSONObject(j);
                if (item == null) continue;
                Source source = readSource(item);
                if (source == null) continue;
                source.repo = repo.url;
                repo.sources.add(source);
            }
            if (!repo.url.isEmpty()) repos.add(repo);
        }
    }

    private static Source readSource(JSONObject item) {
        Source source = new Source();
        source.id = item.optString("id", "");
        source.name = item.optString("name", "");
        if (!validId(source.id) || source.name.isEmpty()) return null;
        source.lang = item.optString("lang", "en");
        source.version = item.optString("version", "");
        source.description = item.optString("description", "");
        source.icon = item.optString("icon", "");
        source.script = item.optString("script", "");
        source.nativeId = item.optString("native", "");
        source.pkg = item.optString("pkg", "");
        source.extension = item.optString("extensionName", "");
        source.apk = item.optString("apk", "");
        source.sourceId = item.optLong("sourceId", 0L);
        source.baseUrl = item.optString("baseUrl", "");
        source.enabled = item.optBoolean("enabled", true);
        return source;
    }

    private static void installBuiltin() {
        Repo repo = new Repo();
        repo.url = BUILTIN;
        repo.name = "SEKAI sources";
        repo.version = "1";
        repo.builtin = true;
        repo.updated = System.currentTimeMillis();
        try {
            JSONObject index = new JSONObject(readAsset("manga-repo/index.json"));
            JSONArray list = index.optJSONArray("sources");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;
                    Source source = readSource(item);
                    if (source == null) continue;
                    source.repo = BUILTIN;
                    if (!source.isNative()) {
                        String file = item.optString("script", "");
                        if (file.isEmpty()) continue;
                        source.script = readAsset("manga-repo/" + file);
                    }
                    repo.sources.add(source);
                }
            }
        } catch (Exception broken) {
            Log.e(TAG, "the built-in source repository could not be read", broken);
        }
        if (repo.sources.isEmpty()) {
            repo.sources.add(nativeSource());
        }
        repos.add(repo);
    }

    private static String readAsset(String name) throws Exception {
        if (application == null) throw new IllegalStateException("no application context");
        try (InputStream in = application.getAssets().open(name)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void persist() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        try {
            prefs.edit().putString(KEY, stored()).apply();
        } catch (JSONException impossible) {
            Log.w(TAG, "could not store the source list", impossible);
        }
    }

    /** The shelf as the preference file holds it. */
    private static String stored() throws JSONException {
        JSONArray rows = new JSONArray();
        for (Repo repo : repos) rows.put(repoJson(repo));
        return rows.toString();
    }

    private static JSONObject repoJson(Repo repo) throws JSONException {
        JSONArray sources = new JSONArray();
        for (Source source : repo.sources) {
            sources.put(new JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("lang", source.lang)
                    .put("version", source.version)
                    .put("description", source.description)
                    .put("icon", source.icon)
                    .put("native", source.nativeId)
                    .put("enabled", source.enabled)
                    .put("pkg", source.pkg)
                    .put("extensionName", source.extension)
                    .put("apk", source.apk)
                    .put("sourceId", source.sourceId)
                    .put("baseUrl", source.baseUrl)
                    .put("script", source.script));
        }
        return new JSONObject()
                .put("url", repo.url)
                .put("name", repo.name)
                .put("version", repo.version)
                .put("format", repo.format)
                .put("fingerprint", repo.fingerprint)
                .put("totalSources", repo.totalSources)
                .put("totalExtensions", repo.totalExtensions)
                .put("builtin", repo.builtin)
                .put("updated", repo.updated)
                .put("sources", sources);
    }

    private static SharedPreferences prefs() {
        Context context = application;
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // -------------------------------------------------------------- the shelf

    /** Everything the settings page draws, in one answer. */
    static synchronized JSONObject state() {
        JSONObject out = new JSONObject();
        JSONArray repoRows = new JSONArray();
        JSONArray sourceRows = new JSONArray();
        try {
            for (Repo repo : repos) {
                repoRows.put(new JSONObject()
                        .put("url", repo.url)
                        .put("name", repo.name.isEmpty() ? repo.url : repo.name)
                        .put("version", repo.version)
                        .put("format", repo.format)
                        .put("builtin", repo.builtin)
                        .put("updated", repo.updated)
                        .put("error", repo.error)
                        .put("count", repo.totalOf())
                        .put("installed", repo.sources.size())
                        .put("extensions", repo.totalExtensions));
                for (Source source : repo.sources) {
                    sourceRows.put(source.json().put("repo", repo.url));
                }
            }
            out.put("repos", repoRows).put("sources", sourceRows);
        } catch (JSONException impossible) {
            // Every field above is a primitive or an array; this cannot happen.
        }
        return out;
    }

    static synchronized List<Source> allSources() {
        List<Source> out = new ArrayList<>();
        for (Repo repo : repos) out.addAll(repo.sources);
        return out;
    }

    static synchronized List<Source> enabledSources() {
        if (!loaded) return Collections.singletonList(nativeSource());
        List<Source> out = new ArrayList<>();
        for (Repo repo : repos) {
            for (Source source : repo.sources) {
                if (source.enabled) out.add(source);
            }
        }
        return out;
    }

    /** The source a browse or the trending shelf asks. Null when the reader turned them all off. */
    static Source primary() {
        List<Source> enabled = enabledSources();
        return enabled.isEmpty() ? null : enabled.get(0);
    }

    /**
     * The source that owns an id.
     *
     * A script source's ids are prefixed (`mangadex:<uuid>`) because two
     * catalogues have no reason to agree on what an id means. Mangafire's ids
     * are left alone: they were stored in positions, shelves and read marks
     * before any of this existed, and a prefix would have orphaned all of them.
     */
    static Route route(String id) {
        ensure(application);
        String wanted = id == null ? "" : id;
        for (Source source : allSources()) {
            String prefix = source.id + ":";
            if (wanted.startsWith(prefix)) {
                return new Route(source, wanted.substring(prefix.length()));
            }
        }
        if (wanted.indexOf(':') >= 0) {
            throw new IllegalStateException(
                    "The source for this title is not installed. Install it again in Settings, or remove the title.");
        }
        return new Route(nativeSource(), wanted);
    }

    static String prefix(Source source, String id) {
        if (id == null) return "";
        return source == null || source.isNative() ? id : source.id + ":" + id;
    }

    // ------------------------------------------------------------- the writes

    static synchronized JSONObject addRepo(String rawUrl) throws Exception {
        ensure(application);
        if (repos.size() >= MAX_REPOS) {
            throw new BadRepoException("That is as many repositories as this app keeps. Remove one first.");
        }
        String url = normalizeUrl(rawUrl);
        if (url.isEmpty()) throw new BadRepoException("Enter a repository URL.");
        for (Repo repo : repos) {
            if (repo.url.equals(url)) throw new BadRepoException("That repository is already installed.");
        }

        byte[] body;
        try {
            body = fetchBytes(url);
        } catch (Exception failed) {
            throw fetchFailure(url, failed);
        }
        Repo repo = parseIndex(url, body, false);
        if (repo.sources.isEmpty() && repo.extensions.isEmpty()) {
            throw new BadRepoException("That repository lists no sources this app can install.");
        }
        if (!repo.extensions.isEmpty()) {
            // A Mihon repository names its sources but installs none until the
            // reader picks them; the ceiling is on what is installed.
            repos.add(repo);
            persist();
            Log.i(TAG, "installed Mihon repository " + url + " with " + repo.totalSources + " sources");
            return state();
        }
        for (Source candidate : repo.sources) {
            for (Repo other : repos) {
                for (Source source : other.sources) {
                    if (source.id.equals(candidate.id)) {
                        throw new BadRepoException("The source id \"" + candidate.id
                                + "\" is already provided by " + other.name + ".");
                    }
                }
            }
        }
        if (allSources().size() + repo.sources.size() > MAX_SOURCES) {
            throw new BadRepoException("That repository would install more sources than this app keeps.");
        }
        repos.add(repo);
        persist();
        Log.i(TAG, "installed repository " + url + " with " + repo.sources.size() + " sources");
        return state();
    }

    static synchronized JSONObject removeRepo(String url) throws BadRepoException {
        ensure(application);
        for (int i = 0; i < repos.size(); i++) {
            Repo repo = repos.get(i);
            if (!repo.url.equals(url)) continue;
            if (repo.builtin) throw new BadRepoException("The built-in repository cannot be removed.");
            repos.remove(i);
            persist();
            return state();
        }
        throw new BadRepoException("That repository is not installed.");
    }

    static synchronized JSONObject refreshRepo(String url) throws Exception {
        ensure(application);
        for (int i = 0; i < repos.size(); i++) {
            Repo existing = repos.get(i);
            if (!existing.url.equals(url)) continue;
            Repo fresh;
            if (existing.builtin) {
                Repo built = new Repo();
                built.url = BUILTIN;
                built.name = "SEKAI sources";
                built.version = "1";
                built.builtin = true;
                built.updated = System.currentTimeMillis();
                try {
                    JSONObject index = new JSONObject(readAsset("manga-repo/index.json"));
                    JSONArray list = index.optJSONArray("sources");
                    if (list != null) {
                        for (int j = 0; j < list.length(); j++) {
                            JSONObject item = list.optJSONObject(j);
                            if (item == null) continue;
                            Source source = readSource(item);
                            if (source == null) continue;
                            source.repo = BUILTIN;
                            if (!source.isNative()) {
                                String file = item.optString("script", "");
                                if (file.isEmpty()) continue;
                                source.script = readAsset("manga-repo/" + file);
                            }
                            built.sources.add(source);
                        }
                    }
                } catch (Exception broken) {
                    throw new BadRepoException("The built-in repository could not be read.");
                }
                fresh = built;
            } else {
                byte[] body;
                try {
                    body = fetchBytes(existing.url);
                } catch (Exception failed) {
                    throw fetchFailure(existing.url, failed);
                }
                fresh = parseIndex(existing.url, body, false);
            }
            if ("mihon".equals(fresh.format)) {
                // The index is the truth about what exists; what the reader
                // turned on is the truth about what stays installed.
                Map<String, Source> live = new HashMap<>();
                for (Extension extension : fresh.extensions) {
                    for (Source source : extension.sources) live.put(source.id, source);
                }
                for (Source installed : existing.sources) {
                    Source now = live.get(installed.id);
                    if (now == null) continue;
                    now.enabled = true;
                    fresh.sources.add(now);
                }
                if (fresh.sources.size() < existing.sources.size()) {
                    fresh.error = (existing.sources.size() - fresh.sources.size())
                            + " source(s) are no longer in this repository.";
                }
            } else {
                // What the reader turned on stays on; a source that is new to
                // this version of the repository arrives enabled.
                Map<String, Boolean> was = new HashMap<>();
                for (Source source : existing.sources) was.put(source.id, source.enabled);
                for (Source source : fresh.sources) {
                    Boolean enabled = was.get(source.id);
                    if (enabled != null) source.enabled = enabled;
                }
            }
            repos.set(i, fresh);
            persist();
            return state();
        }
        throw new BadRepoException("That repository is not installed.");
    }

    static synchronized JSONObject toggle(String id, boolean on) throws Exception {
        return toggle("", id, on);
    }

    /**
     * Turn one source on or off.
     *
     * A Mihon source is not in the stored list until it is first turned on, so
     * the search reaches into the session's index for it; turning it off takes
     * it out again, which is what keeps the stored list to the sources the
     * reader actually picked.
     *
     * A script source is different: it arrives with its repository, so its row
     * stays and only its switch changes. Dropping it would make the switch a
     * one-way door - the row is how it is turned back on - and would leave a
     * built-in repository listing nothing at all.
     */
    static synchronized JSONObject toggle(String repoUrl, String id, boolean on) throws Exception {
        ensure(application);
        for (Repo repo : repos) {
            if (repoUrl != null && !repoUrl.isEmpty() && !repo.url.equals(repoUrl)) continue;
            for (Source source : repo.sources) {
                if (!source.id.equals(id)) continue;
                source.enabled = on;
                if (!on && "mihon".equals(repo.format)) disable(repo, id);
                persist();
                return state();
            }
            if (on && "mihon".equals(repo.format)) {
                ensureExtensions(repo);
                for (Extension extension : repo.extensions) {
                    for (Source source : extension.sources) {
                        if (!source.id.equals(id)) continue;
                        enable(repo, source);
                        persist();
                        return state();
                    }
                }
            }
        }
        throw new BadRepoException("That source is not installed.");
    }

    // ----------------------------------------------------------- repo parsing

    /** The seam a JVM test replaces; the app talks http and reads the APK's assets. */
    interface Fetch {
        byte[] get(String url) throws Exception;
    }

    static Fetch fetch = new HttpFetch();

    private static byte[] fetchBytes(String url) throws Exception {
        return fetch.get(url);
    }

    static Repo parseIndex(String url, byte[] body, boolean builtin) throws Exception {
        String text = new String(body, StandardCharsets.UTF_8);
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new BadRepoException("The repository answered with nothing.");
        }
        if (!builtin) {
            MangaRepoIndex.Index mihon = MangaRepoIndex.parse(url, body);
            if (mihon != null) {
                if (mihon.extensions.isEmpty()) {
                    throw new BadRepoException("That Mihon repository lists no extensions.");
                }
                return mihonRepo(url, mihon);
            }
        }
        if (trimmed.startsWith("<")) {
            throw new BadRepoException("That URL answered with a web page, not a source repository.");
        }

        JSONObject root;
        try {
            root = new JSONObject(trimmed);
        } catch (JSONException notObject) {
            throw new BadRepoException("That URL is not a source repository: it has no JSON index.");
        }
        JSONArray list = root.optJSONArray("sources");
        if (list == null) {
            throw new BadRepoException("That repository has no \"sources\" list. "
                    + "A SEKAI repository is an index.json with a sources array.");
        }

        Repo repo = new Repo();
        repo.url = url;
        repo.name = root.optString("name", hostOf(url));
        repo.version = root.optString("version", "");
        repo.builtin = builtin;
        repo.updated = System.currentTimeMillis();

        String firstFailure = "";
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "").trim().toLowerCase();
            String name = item.optString("name", "").trim();
            if (!validId(id)) {
                if (firstFailure.isEmpty()) {
                    firstFailure = "A source has an id that is not lowercase letters, digits, - or _: \""
                            + id + "\".";
                }
                continue;
            }
            if (name.isEmpty()) {
                if (firstFailure.isEmpty()) firstFailure = "The source \"" + id + "\" has no name.";
                continue;
            }
            Source source = new Source();
            source.id = id;
            source.name = name;
            source.lang = item.optString("lang", "en");
            source.version = item.optString("version", "");
            source.description = item.optString("description", "");
            source.icon = truncate(item.optString("icon", ""), MAX_ICON);
            source.repo = url;
            source.enabled = item.optBoolean("enabled", true);

            String nativeId = item.optString("native", "").trim();
            if (!nativeId.isEmpty()) {
                if (!builtin) {
                    if (firstFailure.isEmpty()) {
                        firstFailure = "A repository has no business declaring a built-in source.";
                    }
                    continue;
                }
                source.nativeId = nativeId;
                repo.sources.add(source);
                continue;
            }

            String file = item.optString("script", "").trim();
            if (file.isEmpty()) {
                if (firstFailure.isEmpty()) firstFailure = "The source \"" + id + "\" has no script.";
                continue;
            }
            String scriptUrl;
            try {
                scriptUrl = resolve(url, file);
            } catch (Exception bad) {
                if (firstFailure.isEmpty()) firstFailure = "The source \"" + id + "\" has an invalid script path.";
                continue;
            }
            byte[] code;
            try {
                code = fetchBytes(scriptUrl);
            } catch (Exception failed) {
                if (firstFailure.isEmpty()) {
                    firstFailure = "The source \"" + id + "\" could not be downloaded: " + message(failed);
                }
                continue;
            }
            if (code.length == 0 || code.length > MAX_SCRIPT) {
                if (firstFailure.isEmpty()) {
                    firstFailure = "The source \"" + id + "\" has an empty or oversized script.";
                }
                continue;
            }
            String script = new String(code, StandardCharsets.UTF_8);
            if (script.trim().startsWith("<")) {
                if (firstFailure.isEmpty()) {
                    firstFailure = "The source \"" + id + "\" answered with a web page, not a script.";
                }
                continue;
            }
            source.script = script;
            repo.sources.add(source);
        }

        if (repo.sources.isEmpty()) {
            throw new BadRepoException(firstFailure.isEmpty()
                    ? "That repository lists no sources."
                    : firstFailure);
        }
        if (!firstFailure.isEmpty()) {
            Log.w(TAG, "repository " + url + " installed with a skipped source: " + firstFailure);
        }
        return repo;
    }

    /**
     * A Mihon repository, as this app's shelf keeps it.
     *
     * Its extensions are not installed sources: an extension is a package with
     * a switch, and only the sources the reader turns on are stored. The whole
     * index is the session's - fetched again whenever a repository is opened -
     * because 1,400 extensions do not belong in a preferences file.
     */
    private static Repo mihonRepo(String url, MangaRepoIndex.Index index) {
        Repo repo = new Repo();
        repo.url = url;
        repo.format = "mihon";
        repo.name = index.meta.name.isEmpty() ? hostOf(url) : index.meta.name;
        repo.fingerprint = index.meta.fingerprint;
        repo.updated = System.currentTimeMillis();
        adoptExtensions(repo, index);
        return repo;
    }

    private static void adoptExtensions(Repo repo, MangaRepoIndex.Index index) {
        repo.extensions.clear();
        repo.totalSources = 0;
        for (MangaRepoIndex.Extension row : index.extensions) {
            Extension extension = new Extension();
            extension.name = row.name;
            extension.pkg = row.pkg;
            extension.version = row.version;
            extension.apk = row.apk;
            extension.icon = row.icon;
            extension.contentWarning = row.contentWarning;
            for (MangaRepoIndex.Source item : row.sources) {
                extension.sources.add(extensionSource(repo, extension, item));
            }
            repo.extensions.add(extension);
            repo.totalSources += extension.sources.size();
        }
        repo.totalExtensions = repo.extensions.size();
        if (repo.fingerprint.isEmpty()) repo.fingerprint = index.meta.fingerprint;
    }

    private static Source extensionSource(Repo repo, Extension extension, MangaRepoIndex.Source item) {
        Source source = new Source();
        source.id = "e" + item.id;
        source.name = item.name;
        source.lang = item.lang;
        source.version = extension.version;
        source.description = extension.name;
        source.repo = repo.url;
        source.pkg = extension.pkg;
        source.extension = extension.name;
        source.apk = extension.apk;
        source.sourceId = item.id;
        source.baseUrl = item.baseUrl;
        source.enabled = false;
        return source;
    }

    /** The whole index in memory, fetched once per repository per session. */
    private static void ensureExtensions(Repo repo) throws Exception {
        if (!"mihon".equals(repo.format) || !repo.extensions.isEmpty()) return;
        byte[] body;
        try {
            body = fetchBytes(repo.url);
        } catch (Exception failed) {
            throw fetchFailure(repo.url, failed);
        }
        MangaRepoIndex.Index index = MangaRepoIndex.parse(repo.url, body);
        if (index == null) throw new BadRepoException("That repository no longer answers with an extension index.");
        adoptExtensions(repo, index);
        for (Source installed : repo.sources) {
            for (Extension extension : repo.extensions) {
                for (Source candidate : extension.sources) {
                    if (candidate.id.equals(installed.id)) {
                        installed.apk = candidate.apk;
                        installed.pkg = candidate.pkg;
                        installed.extension = candidate.extension;
                        installed.version = candidate.version;
                        installed.sourceId = candidate.sourceId;
                        installed.baseUrl = candidate.baseUrl;
                    }
                }
            }
        }
    }

    /**
     * One repository, with every extension and source it offers.
     *
     * The settings row opens this rather than holding 1,400 children: the page
     * draws one lazy list, filterable, and asks for this when it opens.
     */
    static synchronized JSONObject repoDetail(String url) throws Exception {
        ensure(application);
        for (Repo repo : repos) {
            if (!repo.url.equals(url)) continue;
            if (!"mihon".equals(repo.format)) {
                JSONArray sources = new JSONArray();
                for (Source source : repo.sources) sources.put(source.json());
                return new JSONObject()
                        .put("url", repo.url)
                        .put("name", repo.name)
                        .put("format", repo.format)
                        .put("error", repo.error)
                        .put("sources", sources)
                        .put("extensions", new JSONArray());
            }
            ensureExtensions(repo);
            Map<String, Boolean> on = new HashMap<>();
            for (Source source : repo.sources) on.put(source.id, source.enabled);
            JSONArray extensions = new JSONArray();
            int enabled = 0;
            for (Extension extension : repo.extensions) {
                JSONArray sources = new JSONArray();
                int sourceCount = 0;
                for (Source source : extension.sources) {
                    boolean checked = Boolean.TRUE.equals(on.get(source.id));
                    if (checked) sourceCount++;
                    sources.put(new JSONObject()
                            .put("id", source.id)
                            .put("name", source.name)
                            .put("lang", source.lang)
                            .put("enabled", checked));
                }
                enabled += sourceCount;
                extensions.put(new JSONObject()
                        .put("name", extension.name)
                        .put("pkg", extension.pkg)
                        .put("version", extension.version)
                        .put("apk", extension.apk)
                        .put("icon", extension.icon)
                        .put("contentWarning", extension.contentWarning)
                        .put("enabled", sourceCount > 0)
                        .put("sources", sources));
            }
            return new JSONObject()
                    .put("url", repo.url)
                    .put("name", repo.name)
                    .put("format", repo.format)
                    .put("error", repo.error)
                    .put("enabled", enabled)
                    .put("total", repo.totalSources)
                    .put("extensions", extensions)
                    .put("sources", new JSONArray());
        }
        throw new BadRepoException("That repository is not installed.");
    }

    /** The switch on an extension row: every source it provides, on or off. */
    static synchronized JSONObject toggleExtension(String url, String pkg, boolean on) throws Exception {
        ensure(application);
        for (Repo repo : repos) {
            if (!repo.url.equals(url)) continue;
            ensureExtensions(repo);
            for (Extension extension : repo.extensions) {
                if (!extension.pkg.equals(pkg)) continue;
                for (Source source : extension.sources) {
                    if (on) enable(repo, source);
                    else disable(repo, source.id);
                }
                persist();
                return repoDetail(url);
            }
            throw new BadRepoException("That extension is not in this repository.");
        }
        throw new BadRepoException("That repository is not installed.");
    }

    private static void enable(Repo repo, Source source) {
        for (Source installed : repo.sources) {
            if (installed.id.equals(source.id)) {
                installed.enabled = true;
                return;
            }
        }
        Source copy = new Source();
        copy.id = source.id;
        copy.name = source.name;
        copy.lang = source.lang;
        copy.version = source.version;
        copy.description = source.description;
        copy.repo = repo.url;
        copy.pkg = source.pkg;
        copy.extension = source.extension;
        copy.apk = source.apk;
        copy.sourceId = source.sourceId;
        copy.baseUrl = source.baseUrl;
        copy.enabled = true;
        repo.sources.add(copy);
    }

    private static void disable(Repo repo, String id) {
        for (int i = repo.sources.size() - 1; i >= 0; i--) {
            if (repo.sources.get(i).id.equals(id)) repo.sources.remove(i);
        }
    }

    private static BadRepoException fetchFailure(String url, Exception failed) {
        String said = message(failed);
        if (said.contains("HTTP 404")) {
            return new BadRepoException("That repository answered 404. Check the URL - "
                    + "a Mihon repository has no index.pb unless the owner published one.");
        }
        return new BadRepoException("Could not fetch the repository: " + said);
    }

    private static String normalizeUrl(String raw) {
        String trim = raw == null ? "" : raw.trim();
        if (trim.isEmpty()) return "";
        if (!trim.startsWith("http://") && !trim.startsWith("https://")) {
            trim = "https://" + trim;
        }
        trim = trim.replace("#", "");
        // github.com/owner/repo/blob/ref/path is a page; its raw twin is the file.
        if (trim.startsWith("https://github.com/") && trim.contains("/blob/")) {
            String rest = trim.substring("https://github.com/".length());
            int blob = rest.indexOf("/blob/");
            trim = "https://raw.githubusercontent.com/" + rest.substring(0, blob) + "/"
                    + rest.substring(blob + "/blob/".length());
        }
        if (trim.endsWith("/")) trim += "index.json";
        return trim;
    }

    private static String resolve(String base, String ref) throws Exception {
        return URI.create(base).resolve(ref).toString();
    }

    private static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 40) return false;
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_';
            if (!ok) return false;
        }
        char first = id.charAt(0);
        return (first >= 'a' && first <= 'z') || (first >= '0' && first <= '9');
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Exception ignored) {
            return url;
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.trim().isEmpty() ? e.getClass().getSimpleName() : text.trim();
    }

    // -------------------------------------------------------------- browsing

    /**
     * Every enabled source, asked at once for a keyword.
     *
     * A search is the one screen where mixing catalogues is the point, so the
     * answer is a single list with each card tagged by the source it came from
     * - interleaved rather than concatenated, so one large catalogue cannot
     * push the others off the first page. A source that fails is left out and
     * named in `errors`; the rest of the results still arrive. Only when every
     * source fails is the failure the reader's.
     */
    static JSONObject search(String q, int page, int limit, String type, String status, String genre)
            throws Exception {
        List<Source> live;
        if (q == null || q.trim().isEmpty()) {
            Source primary = primary();
            if (primary == null) return empty(page);
            live = Collections.singletonList(primary);
        } else {
            live = enabledSources();
            if (live.isEmpty()) return empty(page);
        }

        if (live.size() == 1) {
            Source only = live.get(0);
            JSONObject answer = browse(only, q, page, limit, type, status, genre);
            answer.put("items", decorate(only, answer.optJSONArray("items")));
            return answer;
        }

        ExecutorService pool = POOL;
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (Source source : live) {
            futures.add(pool.submit(() -> browse(source, q, page, limit, type, status, genre)));
        }

        List<JSONArray> lists = new ArrayList<>();
        JSONArray errors = new JSONArray();
        Exception firstFailure = null;
        boolean hasNext = false;
        for (int i = 0; i < futures.size(); i++) {
            Source source = live.get(i);
            try {
                JSONObject answer = futures.get(i).get(35, TimeUnit.SECONDS);
                lists.add(decorate(source, answer.optJSONArray("items")));
                hasNext = hasNext || answer.optBoolean("hasNext", false);
            } catch (Exception failed) {
                futures.get(i).cancel(true);
                if (firstFailure == null) firstFailure = failed;
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                String said = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                Log.w(TAG, "source " + source.id + " failed during search: " + said);
                try {
                    errors.put(new JSONObject().put("source", source.id).put("message", said));
                } catch (JSONException ignored) {
                    // A row that will not build is a row nobody can use.
                }
            }
        }

        if (lists.isEmpty() && firstFailure != null) {
            throw new IllegalStateException(sourceFailure(firstFailure));
        }

        JSONArray merged = interleave(lists);
        JSONObject out = new JSONObject().put("items", merged).put("page", page).put("hasNext", hasNext);
        if (errors.length() > 0) out.put("errors", errors);
        return out;
    }

    /** One source's answer for the search/browse route. */
    static JSONObject browse(Source source, String q, int page, int limit,
                             String type, String status, String genre) throws Exception {
        if (source.isNative()) {
            return Manga.searchMangafire(q, page, limit, type, status, genre);
        }
        if (source.isExtension()) {
            return extensionBrowse(source, q, page, limit);
        }
        boolean keyword = q != null && !q.trim().isEmpty();
        JSONObject raw = keyword
                ? MangaJs.call(source.script, "search", q.trim(), page)
                : MangaJs.call(source.script, "popular", page);
        JSONArray items = raw.optJSONArray("items");
        JSONArray cards = new JSONArray();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject card = card(source, items.optJSONObject(i));
                if (card != null) cards.put(card);
            }
        }
        return new JSONObject()
                .put("items", cards)
                .put("page", page)
                .put("hasNext", raw.optBoolean("hasNext", false));
    }

    /** One extension's page of results, shaped like a script's. */
    private static JSONObject extensionBrowse(Source source, String q, int page, int limit) throws Exception {
        JSONObject raw = new JSONObject(MihonExtensions.search(
                source.apk, source.pkg, source.sourceId, q == null ? "" : q.trim(), page, limit));
        JSONArray items = raw.optJSONArray("items");
        JSONArray cards = new JSONArray();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject card = card(source, items.optJSONObject(i));
                if (card != null) cards.put(card);
            }
        }
        return new JSONObject()
                .put("items", cards)
                .put("page", page)
                .put("hasNext", raw.optBoolean("hasNext", false));
    }

    static JSONObject jsTitle(Source source, String rawId) throws Exception {
        JSONObject raw = source.isExtension()
                ? new JSONObject(MihonExtensions.title(source.apk, source.pkg, source.sourceId, rawId))
                : MangaJs.call(source.script, "title", rawId);
        JSONObject card = card(source, raw);
        if (card == null) {
            throw new IllegalStateException("That source did not find this title.");
        }
        return card;
    }

    static JSONArray jsChapters(Source source, String rawId) throws Exception {
        JSONObject raw = source.isExtension()
                ? new JSONObject(MihonExtensions.chapters(source.apk, source.pkg, source.sourceId, rawId))
                : MangaJs.call(source.script, "chapters", rawId);
        JSONArray list = raw.optJSONArray("chapters");
        JSONArray out = new JSONArray();
        if (list == null) return out;
        for (int i = 0; i < list.length() && i < 2000; i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "").trim();
            if (id.isEmpty()) continue;
            out.put(new JSONObject()
                    .put("id", prefix(source, id))
                    .put("number", item.optString("number", ""))
                    .put("name", item.optString("name", ""))
                    .put("at", item.optString("at", ""))
                    .put("created", item.optLong("created", 0L)));
        }
        return out;
    }

    static JSONArray jsPageUrls(Source source, String rawChapterId) throws Exception {
        JSONObject raw = source.isExtension()
                ? new JSONObject(MihonExtensions.pages(source.apk, source.pkg, source.sourceId, rawChapterId))
                : MangaJs.call(source.script, "pages", rawChapterId);
        JSONArray list = raw.optJSONArray("pages");
        JSONArray out = new JSONArray();
        if (list == null) return out;
        for (int i = 0; i < list.length() && i < 1000; i++) {
            String url = list.optString(i, "").trim();
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue;
            out.put(url);
        }
        return out;
    }

    // --------------------------------------------------------------- shaping

    /** A script's title row, in the shape the app's cards are drawn from. */
    static JSONObject card(Source source, JSONObject raw) throws JSONException {
        if (raw == null) return null;
        String id = raw.optString("id", "").trim();
        if (id.isEmpty()) return null;
        JSONObject out = new JSONObject()
                .put("id", prefix(source, id))
                .put("title", raw.optString("title", ""))
                .put("poster", raw.optString("poster", ""))
                .put("source", source.id)
                .put("sourceName", source.name)
                .put("sourceLang", source.lang);
        copy(raw, out, "type");
        copy(raw, out, "year");
        copy(raw, out, "status");
        copy(raw, out, "description");
        copy(raw, out, "rating");
        copy(raw, out, "latestChapter");
        copy(raw, out, "malId");
        JSONArray genres = raw.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            JSONArray names = new JSONArray();
            for (int i = 0; i < genres.length(); i++) {
                String name = genres.optString(i, "").trim();
                if (!name.isEmpty()) names.put(name);
            }
            if (names.length() > 0) out.put("genres", names);
        }
        return out;
    }

    /** Tag rows with the catalogue they came from, for the mixed result grid. */
    static JSONArray decorate(Source source, JSONArray items) throws JSONException {
        JSONArray out = new JSONArray();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONObject tagged = new JSONObject(item.toString());
            tagged.put("source", source.id);
            tagged.put("sourceName", source.name);
            // The language a search's source heading says under the name.
            // Tagged here rather than by each catalogue's own row shape, which
            // has no idea which source it is being read through.
            tagged.put("sourceLang", source.lang);
            out.put(tagged);
        }
        return out;
    }

    private static void copy(JSONObject from, JSONObject to, String key) throws JSONException {
        String value = from.optString(key, "");
        if (!value.isEmpty()) to.put(key, value);
    }

    private static JSONArray interleave(List<JSONArray> lists) throws JSONException {
        JSONArray out = new JSONArray();
        if (lists.isEmpty()) return out;
        int longest = 0;
        for (JSONArray list : lists) longest = Math.max(longest, list.length());
        for (int i = 0; i < longest; i++) {
            for (JSONArray list : lists) {
                if (i < list.length()) out.put(list.get(i));
            }
        }
        return out;
    }

    private static JSONObject empty(int page) throws JSONException {
        return new JSONObject().put("items", new JSONArray()).put("page", page).put("hasNext", false);
    }

    private static String sourceFailure(Exception failed) {
        Throwable cause = failed.getCause() == null ? failed : failed.getCause();
        String said = cause.getMessage();
        if (said == null || said.trim().isEmpty()) said = cause.getClass().getSimpleName();
        return said;
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "manga-source");
        thread.setDaemon(true);
        return thread;
    });

    // --------------------------------------------------------------- network

    /** Fetching a repository index and the scripts it names. */
    static final class HttpFetch implements Fetch {
        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .dns(Manga::lookup)
                .build();

        @Override
        public byte[] get(String url) throws Exception {
            if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) {
                throw new IllegalArgumentException("not an http URL");
            }
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", Streams.USER_AGENT)
                    .header("Accept", "application/json,text/plain,*/*")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("HTTP " + response.code() + " from " + hostOf(url));
                }
                return response.body() == null ? new byte[0] : response.body().bytes();
            }
        }
    }

    // ------------------------------------------------------------------ test

    /** Install a source list without a context, so a JVM can hold the routing. */
    static synchronized void installForTest(List<Source> sources) {
        Repo repo = new Repo();
        repo.url = "test://repo";
        repo.name = "Test";
        repo.sources.addAll(sources);
        repos.clear();
        repos.add(repo);
        loaded = true;
    }

    static synchronized void resetForTest() {
        repos.clear();
        loaded = false;
        application = null;
        fetch = new HttpFetch();
        MangaJs.fetch = new MangaJs.HttpFetch();
    }

    /** The preference file's contents, without a preference file. */
    static synchronized String storedForTest() throws JSONException {
        return stored();
    }

    /** Start-up's read of the preference file, for a JVM that has none. */
    static synchronized void loadForTest(String raw) throws JSONException {
        repos.clear();
        load(raw);
        loaded = true;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 15);
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
