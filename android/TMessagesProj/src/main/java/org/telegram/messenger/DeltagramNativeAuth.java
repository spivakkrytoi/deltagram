package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Per-request authentication for the DeltaMent card/domain HTTP API.
 *
 * The old API trusted a static token compiled into the APK plus the
 * X-DeltaGram-User header, so anyone could act as any user. This helper signs
 * every request with the account's current MTProto permanent auth key. The
 * server verifies the HMAC through the telesrv admin API and derives the user
 * from the authorization row; no user id is accepted from the client.
 */
public final class DeltagramNativeAuth {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeltagramNativeAuth() {
    }

    static String authorization(int account, String method, String path, byte[] body) {
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = randomHex(16);
        byte[] bodyHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            bodyHash = digest.digest(body == null ? new byte[0] : body);
        } catch (Exception e) {
            return null;
        }
        String canonical = method + "\n" + path + "\n" + timestamp + "\n" + hex(bodyHash) + "\n" + nonce;
        byte[] signed;
        try {
            signed = ConnectionsManager.getInstance(account).signDeltaGram(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        if (signed == null || signed.length != 40) {
            return null;
        }
        long authKeyId = 0;
        for (int i = 0; i < 8; i++) {
            authKeyId |= (signed[i] & 0xffL) << (8 * i);
        }
        String signature = hex(signed, 8, 32);
        return "DeltaGram " + String.format("%016x", authKeyId) + ":" + timestamp + ":" + nonce + ":" + signature;
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return hex(data);
    }

    private static String hex(byte[] data) {
        return hex(data, 0, data.length);
    }

    private static String hex(byte[] data, int offset, int length) {
        char[] out = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int v = data[offset + i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    public static boolean isAvailable(int account) {
        if (account < 0) {
            return false;
        }
        try {
            return ConnectionsManager.getInstance(account).getCurrentAuthKeyId() != 0;
        } catch (Throwable e) {
            return false;
        }
    }
}
