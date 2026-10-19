package app.hanime.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The parsing half of the old shell's account import, held to a JVM.
 *
 * The WebView read needs a device, but everything after it does not: what
 * `evaluateJavascript` hands back is a JSON literal, and the shell's session
 * record is a string inside it. That boundary is where a silent mistake would
 * cost a sign-in — a record read as `null`, or tokens taken from a shape the
 * old client never wrote — so it is pinned here.
 *
 * The fixture is the exact shape `sessionFrom` in
 * `server/client/src/account.js` stores: `access`, `refresh`, `expiresAt`,
 * `email`, `userId`.
 */
public class LegacyShellImportTest {

    private static String quoted(String json) {
        // What evaluateJavascript returns for a script that returned a string.
        return org.json.JSONObject.quote(json);
    }

    @Test
    public void aSessionWithBothTokensYieldsThem() {
        String record = "{\"access\":\"access-token\",\"refresh\":\"refresh-token\","
                + "\"expiresAt\":1700000000000,\"email\":\"reader@example.com\","
                + "\"userId\":\"0b7c\"}";
        assertArrayEquals(
                new String[]{"access-token", "refresh-token"},
                LegacyShellImport.parse(quoted(record)));
    }

    @Test
    public void aProfileWithNoRecordIsNoSession() {
        assertNull(LegacyShellImport.parse("null"));
    }

    @Test
    public void aRecordWithoutARefreshTokenCannotBeRenewed() {
        assertNull(LegacyShellImport.parse(quoted("{\"access\":\"access-token\"}")));
    }

    @Test
    public void aRecordWithoutAnAccessTokenIsNoSessionEither() {
        assertNull(LegacyShellImport.parse(quoted("{\"refresh\":\"refresh-token\"}")));
    }

    @Test
    public void aCorruptRecordIsIgnoredRatherThanThrown() {
        assertNull(LegacyShellImport.parse(quoted("{not json")));
    }

    @Test
    public void aPlainJsonValueIsNotTheEvaluatorsAnswer() {
        // evaluateJavascript always quotes a string result; a bare object here
        // means the script returned something else, and is not a session.
        assertNull(LegacyShellImport.parse("{\"access\":\"a\",\"refresh\":\"r\"}"));
    }
}
