package app.hanime.shell;

import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The handshake token envelope — a direct port of server/src/token.mjs.
 *
 * The site names its own scheme "htv-insecure-handshake-v1": the key is
 * SHA-256 of a string constant baked into its bundle, so there is no server
 * secret involved and anyone holding the site's JS can mint these. That is
 * exactly why it ports cleanly to Java — there is nothing to steal from us
 * either, the goal is only to keep speaking the protocol.
 *
 *   key   = SHA-256("htv-insecure-handshake-v1")
 *   aad   = "htv-insecure-v1"
 *   wire  = base64url(JSON { v:1, alg:"AES-256-GCM", iv, tag, data })
 */
final class Token {

    private static final String KEY_SEED = "htv-insecure-handshake-v1";
    private static final String AAD = "htv-insecure-v1";
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final SecretKeySpec KEY = buildKey();

    private Token() {
    }

    private static SecretKeySpec buildKey() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(KEY_SEED.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Seal a payload object into the wire envelope. */
    static String seal(JSONObject payload) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
        byte[] ct = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));

        // The tag is the last TAG_BITS/8 bytes of the GCM output.
        byte[] data = new byte[ct.length - 16];
        byte[] tag = new byte[16];
        System.arraycopy(ct, 0, data, 0, data.length);
        System.arraycopy(ct, data.length, tag, 0, 16);

        JSONObject env = new JSONObject();
        env.put("v", 1);
        env.put("alg", "AES-256-GCM");
        env.put("iv", b64url(iv));
        env.put("tag", b64url(tag));
        env.put("data", b64url(data));
        return b64url(env.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Open a wire envelope. Throws on tampering — GCM authentication is enforced. */
    static JSONObject open(String envelope) throws Exception {
        JSONObject env = new JSONObject(new String(unb64url(envelope), StandardCharsets.UTF_8));
        if (env.optInt("v", -1) != 1) {
            throw new IllegalStateException("unsupported envelope version: " + env.opt("v"));
        }
        byte[] data = unb64url(env.getString("data"));
        byte[] tag = unb64url(env.getString("tag"));

        byte[] ct = new byte[data.length + tag.length];
        System.arraycopy(data, 0, ct, 0, data.length);
        System.arraycopy(tag, 0, ct, data.length, tag.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, KEY,
                new GCMParameterSpec(TAG_BITS, unb64url(env.getString("iv"))));
        cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(ct);
        return new JSONObject(new String(plain, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ base64url

    private static String b64url(byte[] raw) {
        return Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static byte[] unb64url(String s) {
        return Base64.decode(s, Base64.URL_SAFE);
    }
}
