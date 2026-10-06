package app.hanime.shell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The one setting the app has: which server to talk to.
 *
 * Kept as a real activity rather than a preference screen so it can also run as
 * a first-run step, where "cancel" is not a meaningful choice.
 */
public class SettingsActivity extends Activity {

    public static final String EXTRA_FIRST_RUN = "first_run";
    public static final String EXTRA_CURRENT = "current";

    private EditText field;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        boolean firstRun = getIntent().getBooleanExtra(EXTRA_FIRST_RUN, false);
        String current = getIntent().getStringExtra(EXTRA_CURRENT);
        if (current == null) current = ServerConfig.get(this);

        TextView title = findViewById(R.id.settings_title);
        TextView help = findViewById(R.id.settings_help);
        field = findViewById(R.id.settings_field);
        Button save = findViewById(R.id.settings_save);
        Button cancel = findViewById(R.id.settings_cancel);
        Button useDefault = findViewById(R.id.settings_default);

        title.setText(firstRun ? R.string.settings_title : R.string.menu_server);
        help.setText(R.string.server_help);

        field.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        field.setText(current);
        field.setSelection(current.length());

        // On first run there is nothing to go back to, so hide the escape hatch
        // and make the compiled-in default one tap away.
        cancel.setVisibility(firstRun ? View.GONE : View.VISIBLE);
        useDefault.setVisibility(firstRun ? View.VISIBLE : View.GONE);
        useDefault.setOnClickListener(v -> {
            field.setText(BuildConfig.DEFAULT_SERVER_URL);
            field.setSelection(field.getText().length());
        });

        save.setOnClickListener(v -> save());
        cancel.setOnClickListener(v -> finish());
    }

    private void save() {
        String raw = field.getText().toString();
        if (!ServerConfig.isValid(raw)) {
            Toast.makeText(this, R.string.bad_url, Toast.LENGTH_LONG).show();
            return;
        }
        ServerConfig.set(this, raw);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK, new Intent());
        finish();
    }
}
