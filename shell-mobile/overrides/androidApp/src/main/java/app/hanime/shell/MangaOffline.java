package app.hanime.shell;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manga chapters kept on this phone, so the reader works with no connection.
 *
 * The reader's pages are normally proxied through this server one image at a
 * time (`/manga/page`), because mangafire's CDN refuses a request without their
 * referer — and a proxy needs a network. Downloading is the other way round: the
 * bytes are fetched here once and written into the app's own files directory.
 * So a downloaded chapter's page list names the files themselves, by `file://`
 * URL, and the reader does not know or care that the network is gone. (This
 * server does also serve those files at `/manga/offline`, for anything outside
 * the app; the reader does not use it, because with the radio off the image
 * loader will not open an `http` URL - even a loopback one.)
 *
 * Layout, under `filesDir/manga-offline/`:
 *
 * <pre>
 *   &lt;hid&gt;/title.json                     the title's name and poster
 *   &lt;hid&gt;/&lt;chapterId&gt;/chapter.json       number, name, page count, byte size
 *   &lt;hid&gt;/&lt;chapterId&gt;/0001.jpg           the pages, in reading order
 * </pre>
 *
 * The metadata lives beside the pages rather than in preferences because the
 * pages are the expensive part: a chapter whose images are there but whose row
 * is missing would be invisible and undeletable, so the row is written last and
 * read back before the chapter counts as downloaded. A chapter that fails
 * halfway is deleted, not left as a half-readable strip.
 *
 * `chapter.json` is written after every page and the title row before the
 * first, so a phone that dies mid-download leaves at most one incomplete
 * chapter directory — and `read` refuses a chapter whose files do not all
 * exist, which is what makes that directory invisible instead of broken.
 */
final class MangaOffline {

    private static final String TAG = "ShellMangaOffline";

    /** The states a chapter can be in, as the reader's buttons read them. */
    static final String DOWNLOADED = "downloaded";
    static final String QUEUED = "queued";
    static final String RUNNING = "downloading";
    static final String FAILED = "failed";

    /**
     * The most pages one chapter may have before it is refused.
     *
     * A ceiling rather than an expectation: a chapter is tens of pages, and a
     * nonsense answer from upstream must not fill the phone in one tap.
     */
    private static final int PAGES_MAX = 500;

    private static Context application;
    private static File root;

    /** Everything on disk, read once and kept in step by the writes below. */
    private static Map<String, Chapter> chapters;
    private static Map<String, Title> titles;

    /** Chapters being fetched, by chapter id, for the reader's progress rows. */
    private static final Map<String, Job> JOBS = new LinkedHashMap<>();

    private static final LinkedBlockingQueue<Job> QUEUE = new LinkedBlockingQueue<>();
    private static Thread worker;

    private MangaOffline() {
    }

    static synchronized void ensure(Context context) {
        if (application == null) {
            application = context.getApplicationContext();
            root = new File(application.getFilesDir(), "manga-offline");
            load();
        }
        if (worker == null) {
            // One worker, in order: a queue of chapters fetched three at a time
            // is a queue that finishes a series no sooner and stresses the CDN
            // that serves every page of it.
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            Job job = QUEUE.take();
                            if (!job.cancelled) runJob(job);
                        } catch (InterruptedException stopped) {
                            return;
                        } catch (Exception unexpected) {
                            Log.w(TAG, "download worker", unexpected);
                        }
                    }
                }
            }, "manga-download");
            worker.setDaemon(true);
            worker.start();
        }
    }

    // ---------------------------------------------------------------- queue

    /**
     * Ask for one chapter to be kept. Answered with the state it is now in:
     * `downloaded` for one already here, `queued` for one just added, and
     * `downloading` for one already on its way. A failed chapter is queued
     * again, which is what makes tapping a failed row a retry.
     */
    static synchronized String queue(
            String hid,
            String chapter,
            String number,
            String name,
            String at,
            String title,
            String poster) {
        load();
        String cleanHid = safe(hid);
        String cleanChapter = safe(chapter);
        if (cleanHid.isEmpty() || cleanChapter.isEmpty()) return FAILED;
        if (chapters.containsKey(cleanChapter)) return DOWNLOADED;

        Job existing = JOBS.get(cleanChapter);
        if (existing != null) {
            if (QUEUED.equals(existing.state) || RUNNING.equals(existing.state)) {
                return existing.state;
            }
            existing.cancelled = true;
            JOBS.remove(cleanChapter);
        }

        Job job = new Job(cleanHid, cleanChapter, text(number), text(name), text(at),
                text(title), text(poster));
        JOBS.put(cleanChapter, job);
        QUEUE.add(job);
        return QUEUED;
    }

    /** Forget one chapter, its pages and its row. Queued work for it is cancelled. */
    static synchronized boolean remove(String hid, String chapter) {
        load();
        String key = text(chapter);
        if (!key.isEmpty()) {
            Job waiting = JOBS.remove(key);
            if (waiting != null) waiting.cancelled = true;
        }
        Chapter saved = chapters == null ? null : chapters.remove(key);
        if (saved == null) return false;
        delete(saved.dir);
        forget(saved);
        return true;
    }

    /** Forget every chapter of one title, and the title row with them. */
    static synchronized int removeTitle(String hid) {
        load();
        String key = text(hid);
        int removed = 0;
        if (chapters != null) {
            for (Chapter saved : new ArrayList<>(chapters.values())) {
                if (!saved.hid.equals(key)) continue;
                chapters.remove(saved.id);
                delete(saved.dir);
                removed++;
            }
        }
        for (Job job : new ArrayList<>(JOBS.values())) {
            if (!job.hid.equals(key)) continue;
            job.cancelled = true;
            JOBS.remove(job.chapter);
        }
        if (titles != null) titles.remove(key);
        delete(new File(root, key));
        return removed;
    }

    // ---------------------------------------------------------------- index

    /**
     * Everything the downloads screen draws: the chapters saved here, the
     * chapters on their way, and one row per title that has any.
     *
     * A finished chapter is a row in [chapters]; one in flight is a [Job].
     * Both shapes are the same so the screen does not have to tell them apart —
     * the state is what it draws differently.
     */
    static synchronized JSONObject index() {
        load();
        JSONObject out = new JSONObject();
        JSONArray saved = new JSONArray();
        long total = 0;
        try {
            List<Chapter> rows = new ArrayList<>(chapters.values());
            rows.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
            for (Chapter chapter : rows) {
                saved.put(row(chapter));
                total += chapter.bytes;
            }
            for (Job job : JOBS.values()) {
                // The worker keeps the chapter row until it has written the
                // last page; a poll landing in that moment must not draw the
                // same chapter twice.
                if (chapters.containsKey(job.chapter)) continue;
                saved.put(job.row());
            }

            JSONArray list = new JSONArray();
            List<Title> shelves = new ArrayList<>(titles.values());
            shelves.sort((a, b) -> Long.compare(b.at, a.at));
            for (Title title : shelves) {
                list.put(title.row());
            }
            out.put("chapters", saved);
            out.put("titles", list);
            out.put("bytes", total);
            out.put("active", active());
        } catch (Exception e) {
            Log.w(TAG, "index", e);
            return new JSONObject();
        }
        return out;
    }

    /** Whether this chapter is here, whole. */
    static synchronized boolean has(String chapter) {
        load();
        return chapters.containsKey(text(chapter));
    }

    /**
     * A downloaded chapter's pages, as `file://` URLs the image loader reads
     * straight off the phone.
     *
     * Not the `/manga/offline` route, although it serves the same bytes and is
     * kept for a caller outside the app: the loader gives up on an `http`
     * request before it reaches loopback when Android says there is no network,
     * which is exactly the case this feature exists for. A `file://` URL is not
     * a network request at all, so there is nothing to give up on.
     *
     * Null when the chapter is not here.
     */
    static synchronized JSONObject pages(String chapter) {
        load();
        Chapter saved = chapters.get(text(chapter));
        if (saved == null) return null;
        JSONArray out = new JSONArray();
        try {
            for (int page = 1; page <= saved.files.size(); page++) {
                out.put(new JSONObject().put("u", fileUrl(saved, page)));
            }
            return new JSONObject().put("pages", out);
        } catch (Exception e) {
            return null;
        }
    }

    /** One saved page as a `file://` URL the image loader can open. */
    static String fileUrl(Chapter saved, int page) {
        if (saved == null || page < 1 || page > saved.files.size()) return "";
        File file = new File(saved.dir, saved.files.get(page - 1));
        // `file://` + an absolute path is three slashes, and every part of this
        // path (the package, the ids, the page names) is URL-safe already.
        return "file://" + file.getAbsolutePath();
    }

    /** The chapters of one title that are here, newest first, as a chapter list. */
    static synchronized JSONArray chapters(String hid) {
        load();
        JSONArray out = new JSONArray();
        List<Chapter> list = new ArrayList<>();
        for (Chapter saved : chapters.values()) {
            if (saved.hid.equals(text(hid))) list.add(saved);
        }
        list.sort((a, b) -> compareNumbers(b.number, a.number));
        try {
            for (Chapter saved : list) {
                out.put(new JSONObject()
                        .put("id", saved.id)
                        .put("number", saved.number)
                        .put("name", saved.name)
                        .put("at", saved.at)
                        .put("created", 0L));
            }
        } catch (Exception e) {
            return new JSONArray();
        }
        return out;
    }

    /** Whether this title has any chapter here. */
    static synchronized boolean hasTitle(String hid) {
        load();
        return titles.containsKey(text(hid));
    }

    /** One page's file, or null when the chapter is not here or the page is out of range. */
    static synchronized File pageFile(String chapter, int page) {
        load();
        Chapter saved = chapters.get(text(chapter));
        if (saved == null) return null;
        if (page < 1 || page > saved.files.size()) return null;
        File file = new File(saved.dir, saved.files.get(page - 1));
        return file.isFile() ? file : null;
    }

    static String pageUrl(String chapter, int page) {
        return "http://127.0.0.1:" + SekaiServer.PORT + "/manga/offline?chapter="
                + encode(chapter) + "&page=" + page;
    }

    /** A page file's own type, from the extension the downloader gave it. */
    static String contentType(String name) {
        String ext = name == null ? "" : name.substring(name.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        switch (ext) {
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "avif": return "image/avif";
            case "svg": return "image/svg+xml";
            default: return "image/jpeg";
        }
    }

    /** A file's bytes, or null when it cannot be read. */
    static byte[] bytes(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "read " + file, e);
            return null;
        }
    }

    // ------------------------------------------------------------- internals

    private static void runJob(Job job) {
        job.state = RUNNING;
        File dir = new File(new File(root, job.hid), job.chapter);
        try {
            JSONArray urls = Manga.pageUrls(job.chapter);
            if (urls.length() == 0) {
                throw new IllegalStateException("This chapter has no pages to save.");
            }
            if (urls.length() > PAGES_MAX) {
                throw new IllegalStateException("This chapter has more pages than a phone should keep.");
            }
            delete(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                throw new IllegalStateException("The phone would not make room for the pages.");
            }
            job.total = urls.length();

            JSONArray files = new JSONArray();
            long bytes = 0;
            for (int i = 0; i < urls.length(); i++) {
                if (job.cancelled) {
                    delete(dir);
                    return;
                }
                String url = urls.optString(i, "");
                if (url.isEmpty()) throw new IllegalStateException("A page had no address.");
                // Not through the memory cache: a saved chapter is read from
                // disk afterwards, and pinning every page of a 300-chapter
                // series in a 96 MB cache would evict the pages in use.
                Manga.Page page = Manga.fetch(url, false);
                if (page == null) {
                    throw new IllegalStateException("Page " + (i + 1) + " did not come back.");
                }
                String ext = extension(page.contentType);
                if (ext.isEmpty()) {
                    throw new IllegalStateException("Page " + (i + 1) + " was not an image.");
                }
                File file = new File(dir, pad(i + 1) + "." + ext);
                write(file, page.body);
                files.put(file.getName());
                bytes += page.body.length;
                job.done = i + 1;
                job.bytes = bytes;
            }

            JSONObject meta = new JSONObject()
                    .put("id", job.chapter)
                    .put("hid", job.hid)
                    .put("number", job.number)
                    .put("name", job.name)
                    .put("at", job.at)
                    .put("pages", files.length())
                    .put("bytes", bytes)
                    .put("files", files)
                    .put("saved", System.currentTimeMillis());
            write(new File(dir, "chapter.json"),
                    meta.toString().getBytes(StandardCharsets.UTF_8));

            synchronized (MangaOffline.class) {
                Chapter saved = readChapter(dir);
                if (saved == null) {
                    throw new IllegalStateException("The chapter did not read back.");
                }
                remember(saved, job.title, job.poster);
                job.state = DOWNLOADED;
                JOBS.remove(job.chapter);
            }
        } catch (Exception e) {
            delete(dir);
            job.state = FAILED;
            job.error = e.getMessage() == null ? "The download failed." : e.getMessage();
            Log.w(TAG, "download " + job.chapter + " failed", e);
        }
    }

    /** Put a finished chapter in the index, and keep its title row current. */
    private static void remember(Chapter saved, String title, String poster) throws Exception {
        chapters.put(saved.id, saved);
        Title row = titles.get(saved.hid);
        if (row == null) {
            row = new Title(saved.hid, text(title), text(poster), System.currentTimeMillis());
            titles.put(saved.hid, row);
        } else if (row.name.isEmpty() && !text(title).isEmpty()) {
            row.name = text(title);
        }
        if (row.poster.isEmpty() && !text(poster).isEmpty()) row.poster = text(poster);
        row.chapters.put(saved.id, saved);
        writeTitle(row);
    }

    /** Drop a chapter from its title's row, and the row when it has no chapters left. */
    private static void forget(Chapter saved) {
        Title row = titles.get(saved.hid);
        if (row == null) return;
        row.chapters.remove(saved.id);
        try {
            if (row.chapters.isEmpty()) {
                titles.remove(row.hid);
                delete(new File(root, row.hid));
            } else {
                writeTitle(row);
            }
        } catch (Exception e) {
            Log.w(TAG, "forget " + saved.id, e);
        }
    }

    private static void writeTitle(Title row) throws Exception {
        File dir = new File(root, row.hid);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        write(new File(dir, "title.json"), row.row().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static synchronized void load() {
        if (chapters != null) return;
        Loaded loaded = read(root);
        chapters = loaded.chapters;
        titles = loaded.titles;
    }

    private static int active() {
        int count = 0;
        for (Job job : JOBS.values()) {
            if (QUEUED.equals(job.state) || RUNNING.equals(job.state)) count++;
        }
        return count;
    }

    private static JSONObject row(Chapter saved) throws Exception {
        Title title = titles.get(saved.hid);
        return new JSONObject()
                .put("hid", saved.hid)
                .put("chapter", saved.id)
                .put("number", saved.number)
                .put("name", saved.name)
                .put("at", saved.at)
                .put("title", title == null ? "" : title.name)
                .put("poster", title == null ? "" : title.poster)
                .put("state", DOWNLOADED)
                .put("pages", saved.files.size())
                .put("done", saved.files.size())
                .put("bytes", saved.bytes);
    }

    // ------------------------------------------------------------- on disk

    /** The files on disk, as the index reads them. Pure, so a JVM test can hold it. */
    static Loaded read(File base) {
        Loaded out = new Loaded();
        if (base == null || !base.isDirectory()) return out;
        File[] titleDirs = base.listFiles();
        if (titleDirs == null) return out;
        for (File dir : titleDirs) {
            if (!dir.isDirectory()) continue;
            JSONObject meta = json(new File(dir, "title.json"));
            if (meta == null) continue;
            String hid = meta.optString("id", dir.getName());
            if (hid.isEmpty()) continue;
            Title title = new Title(hid, meta.optString("title", ""),
                    meta.optString("poster", ""), meta.optLong("at", 0));
            File[] chapterDirs = dir.listFiles();
            if (chapterDirs != null) {
                for (File chapterDir : chapterDirs) {
                    if (!chapterDir.isDirectory()) continue;
                    Chapter chapter = readChapter(chapterDir);
                    if (chapter == null) continue;
                    title.chapters.put(chapter.id, chapter);
                    out.chapters.put(chapter.id, chapter);
                }
            }
            if (!title.chapters.isEmpty()) out.titles.put(hid, title);
        }
        return out;
    }

    /**
     * One chapter's directory as a chapter, or null when it is not whole.
     *
     * Every file the row names has to be there: a half-written chapter is a
     * strip that stops halfway through, and the honest answer to "is this
     * downloaded" is no.
     */
    static Chapter readChapter(File dir) {
        JSONObject meta = json(new File(dir, "chapter.json"));
        if (meta == null) return null;
        String id = meta.optString("id", "");
        String hid = meta.optString("hid",
                dir.getParentFile() == null ? "" : dir.getParentFile().getName());
        JSONArray files = meta.optJSONArray("files");
        if (id.isEmpty() || hid.isEmpty() || files == null || files.length() == 0) return null;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            String name = files.optString(i, "");
            if (name.isEmpty()) return null;
            if (!new File(dir, name).isFile()) return null;
            names.add(name);
        }
        return new Chapter(hid, id, meta.optString("number", ""), meta.optString("name", ""),
                meta.optString("at", ""), dir, names, meta.optLong("bytes", 0),
                meta.optLong("saved", 0));
    }

    private static JSONObject json(File file) {
        if (!file.isFile()) return null;
        byte[] body = bytes(file);
        if (body == null || body.length == 0) return null;
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "unreadable " + file, e);
            return null;
        }
    }

    private static void write(File file, byte[] body) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        // Written beside the real file and renamed, so a phone that dies during
        // a write leaves either the old file or the new one, never a torn one.
        File temp = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(body);
            out.flush();
            out.getFD().sync();
        }
        if (!temp.renameTo(file)) {
            delete(file);
            if (!temp.renameTo(file)) {
                delete(temp);
                throw new IllegalStateException("The phone would not write a page.");
            }
        }
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        if (!file.delete()) Log.w(TAG, "could not delete " + file);
    }

    // -------------------------------------------------------------- helpers

    /**
     * A name safe to put under the files directory.
     *
     * The ids come from a query string, and a chapter id is a path segment, so
     * anything that could climb out of the app's own folder (`..`, a slash) is
     * refused rather than cleaned: an id that is not exactly one safe segment is
     * an id this store will not write.
     *
     * A colon is allowed because a source's ids are namespaced with one
     * (`mangadex:<uuid>`) and Android's own filesystem has no problem with it;
     * it is a separator, not a path character.
     */
    static String safe(String value) {
        String text = text(value);
        if (text.isEmpty() || text.length() > 80) return "";
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.'
                    || ch == ':';
            if (!ok) return "";
        }
        if (text.equals(".") || text.equals("..")) return "";
        return text;
    }

    /** The extension a page's type wants, or empty for something that is not an image. */
    static String extension(String type) {
        String text = type == null ? "" : type.toLowerCase(Locale.ROOT).trim();
        if (text.isEmpty()) return "jpg";
        if (!text.startsWith("image/")) return "";
        if (text.contains("jpeg") || text.contains("jpg")) return "jpg";
        if (text.contains("png")) return "png";
        if (text.contains("webp")) return "webp";
        if (text.contains("gif")) return "gif";
        if (text.contains("avif")) return "avif";
        if (text.contains("svg")) return "svg";
        return "img";
    }

    /**
     * Chapter numbers, compared as numbers: "10" is after "9".
     *
     * A string compare puts chapter 10 before chapter 9, which reorders a
     * downloaded series against the catalogue's own order and makes the
     * reader's "next chapter" a chapter already read. Numbers that are not
     * numbers (an extra, a special) sort after the numbered ones, by name.
     */
    static int compareNumbers(String a, String b) {
        Double left = numeric(a);
        Double right = numeric(b);
        if (left != null && right != null) return Double.compare(left, right);
        if (left != null) return 1;
        if (right != null) return -1;
        return text(a).compareTo(text(b));
    }

    private static Double numeric(String value) {
        String text = text(value);
        if (text.isEmpty()) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String pad(int page) {
        return String.format(Locale.ROOT, "%04d", page);
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    // -------------------------------------------------------------- records

    /** Everything read from disk, before it is kept. */
    static final class Loaded {
        final Map<String, Chapter> chapters = new LinkedHashMap<>();
        final Map<String, Title> titles = new LinkedHashMap<>();
    }

    static final class Title {
        final String hid;
        String name;
        String poster;
        final long at;
        final Map<String, Chapter> chapters = new LinkedHashMap<>();

        Title(String hid, String name, String poster, long at) {
            this.hid = hid;
            this.name = name;
            this.poster = poster;
            this.at = at;
        }

        long bytes() {
            long total = 0;
            for (Chapter chapter : chapters.values()) total += chapter.bytes;
            return total;
        }

        JSONObject row() throws Exception {
            return new JSONObject()
                    .put("id", hid)
                    .put("title", name)
                    .put("poster", poster)
                    .put("chapters", chapters.size())
                    .put("bytes", bytes())
                    .put("at", at);
        }
    }

    static final class Chapter {
        final String hid;
        final String id;
        final String number;
        final String name;
        final String at;
        final File dir;
        final List<String> files;
        final long bytes;
        final long savedAt;

        Chapter(String hid, String id, String number, String name, String at,
                File dir, List<String> files, long bytes, long savedAt) {
            this.hid = hid;
            this.id = id;
            this.number = number;
            this.name = name;
            this.at = at;
            this.dir = dir;
            this.files = files;
            this.bytes = bytes;
            this.savedAt = savedAt;
        }
    }

    /**
     * One chapter on its way. The fields the reader polls are volatile because
     * the worker writes them and the HTTP threads read them; the queue hands
     * this object across, so nothing else is shared.
     */
    static final class Job {
        final String hid;
        final String chapter;
        final String number;
        final String name;
        final String at;
        final String title;
        final String poster;

        volatile String state = QUEUED;
        volatile int done;
        volatile int total;
        volatile long bytes;
        volatile String error = "";
        volatile boolean cancelled;

        Job(String hid, String chapter, String number, String name, String at,
                String title, String poster) {
            this.hid = hid;
            this.chapter = chapter;
            this.number = number;
            this.name = name;
            this.at = at;
            this.title = title;
            this.poster = poster;
        }

        JSONObject row() {
            try {
                return new JSONObject()
                        .put("hid", hid)
                        .put("chapter", chapter)
                        .put("number", number)
                        .put("name", name)
                        .put("at", at)
                        .put("title", title)
                        .put("poster", poster)
                        .put("state", state)
                        .put("pages", total)
                        .put("done", done)
                        .put("bytes", bytes)
                        .put("message", error);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }
}
