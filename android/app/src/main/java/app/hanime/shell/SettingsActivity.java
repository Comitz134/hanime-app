package app.hanime.shell;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The update source, and what the last check did with it.
 *
 * The screen used to be one address field, which meant the only way to answer
 * "why did my update check fail?" was to read logcat over a cable. Both rounds
 * of that complaint cost a debugging session each. Status is cheap: three lines
 * the updater already knows, refreshed after a check the user can run from here
 * without leaving the app.
 *
 * Kept as a real activity rather than a preference screen so it can also run as
 * a first-run step, where "cancel" is not a meaningful choice.
 */
public class SettingsActivity extends Activity {

    public static final String EXTRA_FIRST_RUN = "first_run";
    public static final String EXTRA_CURRENT = "current";
    /** Sent back when a check found something, so the app can offer to install it. */
    public static final String EXTRA_FOUND_UPDATE = "found_update";

    private EditText field;
    private TextView status;
    private Button checkButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        boolean firstRun = getIntent().getBooleanExtra(EXTRA_FIRST_RUN, false);
        // The field shows the source the updater will actually use — never the
        // app's own origin. It used to be prefilled with that, so pressing Save
        // without touching anything pointed the updater at the bundled client.
        String current = getIntent().getStringExtra(EXTRA_CURRENT);
        if (current == null) current = ServerConfig.get(this);
        if (current == null) current = "";

        TextView title = findViewById(R.id.settings_title);
        TextView help = findViewById(R.id.settings_help);
        field = findViewById(R.id.settings_field);
        status = findViewById(R.id.settings_status);
        checkButton = findViewById(R.id.settings_check);
        Button save = findViewById(R.id.settings_save);
        Button cancel = findViewById(R.id.settings_cancel);
        Button useDefault = findViewById(R.id.settings_default);
        Button copy = findViewById(R.id.settings_copy);

        title.setText(firstRun ? R.string.settings_title : R.string.menu_server);
        help.setText(R.string.server_help);

        field.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        field.setText(current);
        field.setSelection(current.length());

        // On first run there is nothing to go back to, so hide the cancel.
        cancel.setVisibility(firstRun ? View.GONE : View.VISIBLE);

        // The way back from a dead address is always available: this is the
        // button that un-breaks a device whose saved source cannot be reached.
        useDefault.setVisibility(View.VISIBLE);
        useDefault.setOnClickListener(v -> resetToBuiltIn());

        checkButton.setOnClickListener(v -> checkNow());
        copy.setOnClickListener(v -> copyDiagnostics());
        save.setOnClickListener(v -> save());
        cancel.setOnClickListener(v -> finish());

        renderStatus();
    }

    // ------------------------------------------------------------- status

    /**
     * What is installed, where updates are looked for, and what the last look
     * returned. Read from the same preferences the updater writes, so this can
     * never disagree with what actually happened.
     */
    private void renderStatus() {
        String source = ServerConfig.get(this);
        if (source == null || source.trim().isEmpty()) {
            source = getString(R.string.settings_source_none);
        }
        String at = UpdateLog.lastAtLabel(this);
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.settings_installed,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)).append('\n');
        text.append(getString(R.string.settings_source_now, source)).append('\n');
        text.append(at == null
                ? getString(R.string.settings_never_checked)
                : getString(R.string.settings_last_check, at, describeLast()));
        status.setText(text.toString());
    }

    /** The recorded outcome, in words rather than in enum names. */
    private String describeLast() {
        String text = UpdateLog.lastText(this);
        switch (UpdateLog.lastKind(this)) {
            case "UP_TO_DATE":
                return getString(R.string.last_up_to_date, text);
            case "AVAILABLE":
                return getString(R.string.last_available, text);
            case "DOWNLOADED":
                return getString(R.string.last_downloaded, text);
            case "NOT_CONFIGURED":
                return getString(R.string.last_not_configured);
            case "FAILED":
                return getString(R.string.last_failed, text);
            default:
                return getString(R.string.last_unknown);
        }
    }

    private void checkNow() {
        checkButton.setEnabled(false);
        checkButton.setText(R.string.settings_checking);
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();

        Updater.check(this, outcome -> {
            checkButton.setEnabled(true);
            checkButton.setText(R.string.settings_check_now);
            // The updater has already written this outcome down, so the status
            // line is now the record rather than a separate guess.
            renderStatus();
            Toast.makeText(this, describeOutcome(outcome), Toast.LENGTH_LONG).show();

            if (outcome.kind == Updater.Outcome.Kind.AVAILABLE) {
                // Downloading and installing belong to the app, where the
                // consent dialog lives. Hand it back rather than duplicating
                // that flow here.
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_FOUND_UPDATE, true));
            }
        });
    }

    private String describeOutcome(Updater.Outcome outcome) {
        switch (outcome.kind) {
            case UP_TO_DATE:
                return getString(R.string.update_none, outcome.message);
            case AVAILABLE:
                return getString(R.string.settings_found, outcome.info.versionName);
            case NOT_CONFIGURED:
                return getString(R.string.update_no_endpoint);
            case FAILED:
            default: {
                String source = outcome.fellBackFrom != null
                        ? outcome.fellBackFrom
                        : Updater.sourceLabel(this);
                String why = getString(R.string.update_failed, outcome.message)
                        + "\n\n" + getString(R.string.update_source_used, source);
                if (outcome.alsoFailed != null) {
                    why += "\n\n" + getString(R.string.update_builtin_failed, outcome.alsoFailed);
                }
                return why;
            }
        }
    }

    /**
     * Everything a bug report would otherwise need a cable for: versions, the
     * effective source, the saved override, the last result, the WebView — and
     * the one number that is otherwise invisible, how much the library's covers
     * are holding on disk.
     */
    private void copyDiagnostics() {
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.diag_header)).append('\n');
        text.append(getString(R.string.settings_installed,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)).append('\n');
        text.append(getString(R.string.diag_package, getPackageName())).append('\n');
        text.append(getString(R.string.diag_source, ServerConfig.get(this))).append('\n');
        text.append(getString(R.string.diag_builtin, ServerConfig.builtIn())).append('\n');
        String saved = ServerConfig.stored(this);
        text.append(getString(R.string.diag_override,
                saved.isEmpty() ? getString(R.string.diag_none) : saved)).append('\n');
        String at = UpdateLog.lastAtLabel(this);
        text.append(getString(R.string.diag_last_check,
                at == null ? getString(R.string.diag_never) : at,
                UpdateLog.lastKind(this),
                UpdateLog.lastText(this))).append('\n');
        text.append(getString(R.string.diag_last_source, UpdateLog.lastSource(this))).append('\n');
        text.append(getString(R.string.diag_webview, WebSettings.getDefaultUserAgent(this))).append('\n');
        text.append(getString(R.string.diag_covers, coverBytes() / 1024)).append('\n');

        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("hanime diagnostics", text.toString()));
            Toast.makeText(this, R.string.settings_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private long coverBytes() {
        try {
            return new CoverCache(this).bytes();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------- source

    /** Drops the override so the app goes back to the source it shipped with. */
    private void resetToBuiltIn() {
        String builtIn = ServerConfig.builtIn();
        ServerConfig.clear(this);
        if (builtIn.isEmpty()) {
            // A build made with -PupdateUrl= has nothing to fall back to.
            field.setText("");
            Toast.makeText(this, R.string.settings_no_builtin, Toast.LENGTH_LONG).show();
            renderStatus();
            return;
        }
        field.setText(builtIn);
        field.setSelection(field.getText().length());
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show();
        renderStatus();
    }

    private void save() {
        String raw = field.getText().toString();
        // An empty field is allowed: saving it clears the override rather than
        // storing an address that is not one.
        if (!raw.trim().isEmpty() && !ServerConfig.isValid(raw)) {
            Toast.makeText(this, R.string.bad_url, Toast.LENGTH_LONG).show();
            return;
        }
        ServerConfig.set(this, raw);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK, new Intent());
        finish();
    }
}
