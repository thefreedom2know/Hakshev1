package com.example.hakshev;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Database passphrase management. A random 256-bit passphrase is generated once and stored
 * only in encrypted form: it is wrapped with an AES key that lives in the Android Keystore
 * (hardware-backed where available) and never leaves it.
 */
final class DbKey {
    private DbKey() {}

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "hakshev_db_wrap_key";
    private static final String PREF = "hakshev_dbkey";
    private static final String PREF_BLOB = "wrapped";

    static synchronized byte[] passphrase(Context c) {
        try {
            SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String stored = sp.getString(PREF_BLOB, null);
            SecretKey wrapKey = wrapKey();
            if (stored != null) {
                byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
                byte[] iv = new byte[12];
                System.arraycopy(blob, 0, iv, 0, 12);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, wrapKey, new GCMParameterSpec(128, iv));
                return cipher.doFinal(blob, 12, blob.length - 12);
            }
            byte[] pass = new byte[32];
            new SecureRandom().nextBytes(pass);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey);
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(pass);
            byte[] blob = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(enc, 0, blob, iv.length, enc.length);
            sp.edit().putString(PREF_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).commit();
            return pass;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot access database key", e);
        }
    }

    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
