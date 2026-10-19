package app.hanime.shell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Starts the catalogue server while the app is still coming up.
 *
 * Something has to begin listening on 127.0.0.1:8787 before the app's first
 * request for an addon manifest, and this is the earliest hook Android offers
 * without taking over the Application class. A provider's `onCreate` runs
 * before `Application.onCreate` and before any activity, and the framework
 * instantiates every declared provider on start-up — so being a provider *is*
 * the whole mechanism. It stores nothing and answers no query: the content
 * contract is deliberately empty, because this is a start-up hook that happens
 * to be spelled as a provider rather than a database of any kind.
 *
 * The alternative — our own `Application` subclass — would have meant either
 * subclassing upstream's `NuvioApplication` (final, and its Coil set-up is not
 * ours to fork) or copying that set-up here so it could drift. Upstream's
 * application class is left exactly as it is; only the manifest gained one
 * provider line.
 */
public final class SekaiBoot extends ContentProvider {

    @Override
    public boolean onCreate() {
        // The context comes first: the server reads the signer's assets out of
        // the APK and builds the WebView that runs them, and neither is possible
        // from the socket layer's own idea of a context.
        SekaiServer.setContext(getContext());
        SekaiServer.start();
        // The shell whose package this app inherits kept its account in a
        // WebView; carry it across on the first activity, once. Registered
        // here because a provider runs before any activity, and the import
        // needs the storage initialisation an activity performs.
        LegacyShellImport.watch(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
