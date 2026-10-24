package app.hanime.shell;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The JavaScript a source repository ships, run in a box with one door.
 *
 * A source is a small script that answers five questions — what is popular,
 * what matches a search, what is one title, what are its chapters, and where
 * are one chapter's pages — and this is the thing that asks them. Nothing else
 * is reachable from inside it: Rhino's safe standard objects carry no Java
 * bridge, a class shutter refuses every name, and the only host function is
 * {@code httpGet}, which speaks http and nothing else. That matters because a
 * repository is somebody else's file on somebody else's server, and the phone
 * that installs it is also the phone with the account on it.
 *
 * The engine is Rhino, interpreted, because the alternative is a native
 * library the JVM tests cannot load: every one of these decisions is testable
 * on a laptop, which is where the parser and the box below are held.
 *
 * A script is compiled and run on every call rather than cached. The scripts
 * are small, the calls are a catalogue's worth of seconds apart, and a cached
 * scope would keep whatever the last call left in it — the one thing a shared
 * box must not do.
 */
final class MangaJs {

    private static final String TAG = "ShellMangaSource";

    /** A script larger than this is not a source; it is a payload. */
    private static final int MAX_CODE = 512 * 1024;
    /** A result larger than this cannot be a page of cards. */
    private static final int MAX_RESULT = 8 * 1024 * 1024;
    /** Rhino checks its clock this often, in bytecode instructions. */
    private static final int INSTRUCTION_CHUNK = 10_000;
    /** One call's whole budget: long enough for a slow catalogue, short enough to notice. */
    private static final long LIMIT_MS = 20_000L;

    private MangaJs() {
    }

    /** The seam a JVM test replaces; the app talks http. */
    interface Fetch {
        String get(String url, String headersJson) throws Exception;
    }

    static Fetch fetch = new HttpFetch();

    /**
     * Ask one script one question.
     *
     * [op] is the function's name in the script and [args] are handed to it in
     * order. The answer is the object it returns, serialized; a script that
     * returns a JSON string is accepted too, because that is the shape a person
     * writing one reaches for first.
     */
    static JSONObject call(String code, String op, Object... args) throws Exception {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalStateException("This source has no script.");
        }
        if (code.length() > MAX_CODE) {
            throw new IllegalStateException("This source's script is too large.");
        }

        Deadline deadline = new Deadline();
        Context cx = FACTORY.enterContext();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            DEADLINE.set(deadline);
            Scriptable scope = cx.initSafeStandardObjects();
            hostFunctions(cx, scope);

            cx.evaluateString(scope, code, "source.js", 1, null);

            Object value = ScriptableObject.getProperty(scope, op);
            if (!(value instanceof Function)) {
                throw new IllegalStateException("This source does not answer \"" + op + "\".");
            }
            Object[] passed = new Object[args == null ? 0 : args.length];
            for (int i = 0; i < passed.length; i++) {
                passed[i] = Context.javaToJS(args[i], scope);
            }
            Object answer = ((Function) value).call(cx, scope, scope, passed);
            if (answer == null || answer == Undefined.instance) {
                throw new IllegalStateException("This source answered \"" + op + "\" with nothing.");
            }

            String text;
            if (answer instanceof CharSequence) {
                text = answer.toString();
            } else {
                Object json = NativeJSON.stringify(cx, scope, answer, null, null);
                text = json == null || json == Undefined.instance ? "" : json.toString();
            }
            text = text == null ? "" : text.trim();
            if (text.isEmpty()) {
                throw new IllegalStateException("This source answered \"" + op + "\" with nothing.");
            }
            if (text.length() > MAX_RESULT) {
                throw new IllegalStateException("This source's answer is too large.");
            }
            try {
                return new JSONObject(text);
            } catch (JSONException notAnObject) {
                throw new IllegalStateException("This source's answer to \"" + op + "\" is not JSON.");
            }
        } catch (RhinoException failed) {
            throw new IllegalStateException(explain(failed), failed);
        } catch (IllegalStateException refused) {
            throw refused;
        } finally {
            DEADLINE.remove();
            Context.exit();
        }
    }

    /** What a person can do something with, not a Rhino stack trace. */
    private static String explain(RhinoException failed) {
        String details = failed.details();
        if (details == null || details.trim().isEmpty()) details = failed.getMessage();
        details = details == null ? "unknown error" : details.trim();
        if (details.length() > 300) details = details.substring(0, 297) + "...";
        return "The source's script failed: " + details;
    }

    // ------------------------------------------------------------- the box

    private static final ClassShutter NO_JAVA = new ClassShutter() {
        @Override
        public boolean visibleToScripts(String fullClassName) {
            return false;
        }
    };

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(INSTRUCTION_CHUNK);
            cx.setClassShutter(NO_JAVA);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Deadline deadline = DEADLINE.get();
            if (deadline == null) return;
            deadline.steps += instructionCount;
            if (deadline.expired()) {
                throw new EvaluatorException("the script ran too long");
            }
        }

        @Override
        protected boolean hasFeature(Context cx, int featureIndex) {
            // No Java package access, and no way to ask for more of it.
            if (featureIndex == Context.FEATURE_ENHANCED_JAVA_ACCESS) return false;
            return super.hasFeature(cx, featureIndex);
        }
    };

    /** One call's budget, counted in steps as well as milliseconds. */
    private static final class Deadline {
        final long start = System.nanoTime();
        long steps;

        boolean expired() {
            if (steps > 25_000_000L) return true;
            return (System.nanoTime() - start) / 1_000_000L > LIMIT_MS;
        }
    }

    private static final ThreadLocal<Deadline> DEADLINE = new ThreadLocal<>();

    // ------------------------------------------------------------ host API

    /**
     * The one door out: `httpGet(url)` and `httpGet(url, headersJson)`.
     *
     * Synchronous on purpose. Rhino cannot wait on a promise, and a source that
     * has to fetch three documents is a source that does three requests; the
     * call's budget covers all of them.
     */
    private static void hostFunctions(final Context cx, final Scriptable scope) {
        ScriptableObject.putProperty(scope, "httpGet", new BaseFunction() {
            @Override
            public Object call(Context calling, Scriptable scope, Scriptable thisObj, Object[] args) {
                if (args.length < 1) {
                    throw Context.reportRuntimeError("httpGet(url) needs a URL");
                }
                String url = Context.toString(args[0]);
                if (!isHttp(url)) {
                    throw Context.reportRuntimeError("httpGet only speaks http and https");
                }
                String headers = "";
                if (args.length > 1 && args[1] != null && args[1] != Undefined.instance) {
                    headers = Context.toString(args[1]);
                }
                try {
                    return fetch.get(url, headers);
                } catch (Exception failed) {
                    String message = failed.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = failed.getClass().getSimpleName();
                    }
                    throw Context.reportRuntimeError(message);
                }
            }

            @Override
            public String getFunctionName() {
                return "httpGet";
            }
        });

        ScriptableObject.putProperty(scope, "log", new BaseFunction() {
            @Override
            public Object call(Context calling, Scriptable scope, Scriptable thisObj, Object[] args) {
                StringBuilder line = new StringBuilder();
                for (Object arg : args) {
                    if (line.length() > 0) line.append(' ');
                    line.append(Context.toString(arg));
                }
                Log.d(TAG, line.toString());
                return Undefined.instance;
            }

            @Override
            public String getFunctionName() {
                return "log";
            }
        });
    }

    private static boolean isHttp(String url) {
        return url != null
                && (url.startsWith("https://") || url.startsWith("http://"));
    }

    // ------------------------------------------------------------- network

    /** The app's client: same resolver fallback as the catalogue's own calls. */
    static final class HttpFetch implements Fetch {

        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .dns(Manga::lookup)
                .build();

        @Override
        public String get(String url, String headersJson) throws Exception {
            if (!isHttp(url)) throw new IllegalArgumentException("not an http URL");
            Request.Builder request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", Streams.USER_AGENT)
                    .header("Accept", "application/json,text/plain,text/html,*/*");
            if (headersJson != null && !headersJson.trim().isEmpty()) {
                try {
                    JSONObject headers = new JSONObject(headersJson);
                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = headers.optString(key, "");
                        if (value.isEmpty()) continue;
                        // A script may name the headers its site expects, but
                        // not the ones that would let it lie about the app.
                        if ("host".equalsIgnoreCase(key) || "content-length".equalsIgnoreCase(key)) {
                            continue;
                        }
                        request.header(key, value);
                    }
                } catch (JSONException notHeaders) {
                    throw new IllegalArgumentException("headers must be a JSON object");
                }
            }
            try (Response response = client.newCall(request.build()).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "source request failed: " + url + " HTTP " + response.code());
                    throw new IllegalStateException("HTTP " + response.code() + " from " + host(url));
                }
                return response.body() == null ? "" : response.body().string();
            }
        }

        private static String host(String url) {
            try {
                return java.net.URI.create(url).getHost();
            } catch (Exception ignored) {
                return "the source";
            }
        }
    }
}
