package app.hanime.shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * A Mihon extension repository, read.
 *
 * Mihon publishes three shapes of the same list: `index.pb` (a gzipped protobuf
 * with the repository's meta and one entry per extension), the older
 * `index.min.json` (an array of extensions), and a newer JSON store
 * (`{ meta, extensionList }`). All three carry the same facts: an extension is
 * an Android package, an APK address, a version, and the sources it provides.
 *
 * The protobuf is decoded here by hand rather than with a generated schema: the
 * message is four small fields deep and the app already ships no protobuf
 * runtime.
 */
final class MangaRepoIndex {

    private MangaRepoIndex() {
    }

    /** Repo meta: who publishes this, and the key its APKs are signed with. */
    static final class Meta {
        String name = "";
        String shortName = "";
        String fingerprint = "";
    }

    /** One extension package and the sources inside it. */
    static final class Extension {
        String name = "";
        String pkg = "";
        String apk = "";
        String icon = "";
        String lang = "";
        String version = "";
        int code;
        int contentWarning;
        final List<Source> sources = new ArrayList<>();
    }

    /** One catalogue inside an extension. [id] is Mihon's own 64-bit source id. */
    static final class Source {
        long id;
        String name = "";
        String lang = "";
        String baseUrl = "";
    }

    static final class Index {
        Meta meta = new Meta();
        final List<Extension> extensions = new ArrayList<>();
    }

    /** True when the bytes are a gzipped protobuf rather than JSON. */
    static boolean isProtobuf(byte[] body) {
        return body != null && body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
    }

    /**
     * Parse a Mihon repository, or return null when this is not one.
     *
     * A repository this app cannot read a source list out of is the caller's
     * error to raise; null here means "not Mihon, try the SEKAI reader".
     */
    static Index parse(String url, byte[] body) throws Exception {
        if (body == null || body.length == 0) return null;
        if (isProtobuf(body)) {
            return fromProtobuf(url, gunzip(body));
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                JSONObject root = text.startsWith("{") ? new JSONObject(text) : null;
                if (root != null) {
                    if (!root.has("meta") && !root.has("extensionList")) return null;
                    return fromJson(url, root.optJSONArray("extensionList"), root.optJSONObject("meta"));
                }
                JSONArray array = new JSONArray(text);
                return fromJson(url, array, null);
            } catch (Exception notJson) {
                return null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- json

    private static Index fromJson(String url, JSONArray list, JSONObject meta) {
        Index index = new Index();
        if (meta != null) {
            index.meta.name = meta.optString("name", "");
            index.meta.shortName = meta.optString("shortName", "");
            index.meta.fingerprint = meta.optString("signingKeyFingerprint", "");
        }
        if (list == null) return index;
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.optJSONObject(i);
            if (row == null) continue;
            Extension extension = new Extension();
            extension.name = row.optString("name", "");
            extension.pkg = row.optString("pkg", "");
            extension.apk = resolve(url, row.optString("apk", ""));
            extension.icon = resolve(url, row.optString("icon", ""));
            extension.lang = row.optString("lang", "");
            extension.version = row.optString("version", "");
            extension.code = row.optInt("code", 0);
            extension.contentWarning = row.optInt("nsfw", 0) != 0 ? 3 : 1;
            JSONArray sources = row.optJSONArray("sources");
            if (sources != null) {
                for (int j = 0; j < sources.length(); j++) {
                    JSONObject item = sources.optJSONObject(j);
                    if (item == null) continue;
                    Source source = new Source();
                    source.id = item.optLong("id", 0L);
                    source.name = item.optString("name", extension.name);
                    source.lang = item.optString("lang", extension.lang);
                    source.baseUrl = item.optString("baseUrl", "");
                    if (source.id != 0L) extension.sources.add(source);
                }
            }
            if (!extension.pkg.isEmpty() && !extension.sources.isEmpty()) index.extensions.add(extension);
        }
        return index;
    }

    // ----------------------------------------------------------- protobuf

    private static Index fromProtobuf(String url, byte[] bytes) {
        Index index = new Index();
        List<Field> top = fields(bytes);
        List<byte[]> entries = new ArrayList<>();
        // The index is four facts, then one wrapper: 1 name, 2 shortName,
        // 3 signingKeyFingerprint, and 101 - a single message whose field 1 is
        // repeated once per extension. It reads as a list held in one field
        // rather than a repeated 101, which is why this opens the wrapper
        // before it opens the entries.
        for (Field field : top) {
            if (field.number == 1 && field.bytes != null) {
                index.meta.name = text(field.bytes);
            } else if (field.number == 2 && field.bytes != null) {
                index.meta.shortName = text(field.bytes);
            } else if (field.number == 3 && field.bytes != null) {
                index.meta.fingerprint = text(field.bytes);
            } else if (field.number == 101 && field.bytes != null) {
                for (Field entry : fields(field.bytes)) {
                    if (entry.number == 1 && entry.bytes != null) entries.add(entry.bytes);
                }
            }
        }

        for (byte[] entry : entries) {
            Extension extension = new Extension();
            List<Source> sources = new ArrayList<>();
            for (Field field : fields(entry)) {
                switch (field.number) {
                    case 1:
                        extension.name = text(field.bytes);
                        break;
                    case 2:
                        extension.pkg = text(field.bytes);
                        break;
                    case 3:
                        if (field.bytes != null) readUrls(url, field.bytes, extension);
                        break;
                    case 5:
                        extension.code = (int) field.number_;
                        break;
                    case 6:
                        extension.version = text(field.bytes);
                        break;
                    case 7:
                        extension.contentWarning = (int) field.number_;
                        break;
                    case 8:
                        if (field.bytes != null) {
                            Source source = source(field.bytes);
                            if (source != null) sources.add(source);
                        }
                        break;
                    default:
                        break;
                }
            }
            if (extension.lang.isEmpty() && !sources.isEmpty()) extension.lang = sources.get(0).lang;
            if (extension.pkg.isEmpty() || sources.isEmpty()) continue;
            extension.sources.addAll(sources);
            index.extensions.add(extension);
        }
        return index;
    }

    /** The extension's address block: apk, icon, and the per-version jar. */
    private static void readUrls(String url, byte[] block, Extension extension) {
        for (Field field : fields(block)) {
            if (field.bytes == null) continue;
            // keiyoushi publishes absolute addresses; a repository that mirrors
            // the same shape may publish them relative to its index.
            String value = resolve(url, text(field.bytes));
            if (field.number == 1) extension.apk = value;
            else if (field.number == 2) extension.icon = value;
        }
        if (extension.icon.isEmpty()) extension.icon = "";
    }

    private static Source source(byte[] bytes) {
        Source source = new Source();
        for (Field field : fields(bytes)) {
            if (field.number == 1) source.id = field.number_;
            else if (field.number == 2) source.name = text(field.bytes);
            else if (field.number == 3) source.lang = text(field.bytes);
            else if (field.number == 4) source.baseUrl = text(field.bytes);
        }
        return source.id == 0L || source.name.isEmpty() ? null : source;
    }

    private static final class Field {
        int number;
        int wire;
        long number_;
        byte[] bytes;
    }

    private static List<Field> fields(byte[] buffer) {
        List<Field> out = new ArrayList<>();
        int index = 0;
        while (index < buffer.length) {
            long key;
            int[] cursor = {index};
            try {
                key = varint(buffer, cursor);
            } catch (Exception broken) {
                break;
            }
            index = cursor[0];
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            Field field = new Field();
            field.number = number;
            field.wire = wire;
            try {
                if (wire == 0) {
                    cursor[0] = index;
                    field.number_ = varint(buffer, cursor);
                    index = cursor[0];
                } else if (wire == 2) {
                    cursor[0] = index;
                    int length = (int) varint(buffer, cursor);
                    int start = cursor[0];
                    if (length < 0 || start + length > buffer.length) break;
                    field.bytes = new byte[length];
                    System.arraycopy(buffer, start, field.bytes, 0, length);
                    index = start + length;
                } else if (wire == 5) {
                    index += 4;
                } else if (wire == 1) {
                    index += 8;
                } else {
                    break;
                }
            } catch (Exception broken) {
                break;
            }
            out.add(field);
        }
        return out;
    }

    private static long varint(byte[] buffer, int[] cursor) {
        long value = 0;
        int shift = 0;
        while (cursor[0] < buffer.length) {
            int b = buffer[cursor[0]++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 63) return value;
        }
        return value;
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] gunzip(byte[] body) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length * 3);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static String resolve(String base, String ref) {
        if (ref == null || ref.isEmpty()) return "";
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        try {
            return URI.create(base).resolve(ref).toString();
        } catch (Exception broken) {
            return ref;
        }
    }
}
