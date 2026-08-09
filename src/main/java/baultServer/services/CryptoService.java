package baultServer.services;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Wrap/unwrap de DEKs por carpeta usando una master key (KEK) versionada.
 * Las KEK se cargan de {@code bault.crypto.keys.<version>} y la activa de
 * {@code bault.crypto.active-version}.
 */
@Service
public class CryptoService {

    private static final String ALGO = "AES";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int DEK_LEN = 32;
    private static final int MAX_KEY_VERSION_SCAN = 100;

    private final Map<Integer, SecretKey> keks;
    private final int activeVersion;
    private final SecureRandom rng = new SecureRandom();

    public CryptoService(Environment env) {
        String activeRaw = env.getProperty("bault.crypto.active-version");
        if (activeRaw == null || activeRaw.isBlank()) {
            throw new IllegalStateException("bault.crypto.active-version not configured");
        }
        int active;
        try {
            active = Integer.parseInt(activeRaw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("bault.crypto.active-version must be an integer");
        }

        Map<Integer, SecretKey> loaded = new HashMap<>();
        for (int v = 1; v <= MAX_KEY_VERSION_SCAN; v++) {
            String b64 = env.getProperty("bault.crypto.keys." + v);
            if (b64 == null || b64.isBlank()) continue;
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(b64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("bault.crypto.keys." + v + " is not valid base64");
            }
            if (raw.length != DEK_LEN) {
                throw new IllegalStateException("bault.crypto.keys." + v + " must decode to 32 bytes");
            }
            loaded.put(v, new SecretKeySpec(raw, ALGO));
        }
        if (!loaded.containsKey(active)) {
            throw new IllegalStateException("Active KEK version " + active + " not present in bault.crypto.keys.*");
        }
        this.keks = Map.copyOf(loaded);
        this.activeVersion = active;
    }

    public record WrappedKey(byte[] wrapped, byte[] iv, int version) {}

    /** DEK aleatoria de 256 bits. Llamar {@code Arrays.fill(dek, (byte)0)} tras usarla. */
    public byte[] newDek() {
        byte[] dek = new byte[DEK_LEN];
        rng.nextBytes(dek);
        return dek;
    }

    /** Envuelve la DEK con la KEK activa. La AAD se liga al folderId para evitar swap entre carpetas. */
    public WrappedKey wrap(byte[] dek, long folderId) {
        try {
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, keks.get(activeVersion), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(folderId));
            byte[] wrapped = c.doFinal(dek);
            return new WrappedKey(wrapped, iv, activeVersion);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("wrap failed", e);
        }
    }

    public byte[] unwrap(byte[] wrapped, byte[] iv, int version, long folderId) {
        SecretKey kek = keks.get(version);
        if (kek == null) throw new IllegalStateException("Unknown KEK version: " + version);
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(folderId));
            return c.doFinal(wrapped);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("unwrap failed", e);
        }
    }

    public int activeVersion() {
        return activeVersion;
    }

    private static byte[] aad(long folderId) {
        return ("folder:" + folderId).getBytes(StandardCharsets.UTF_8);
    }
}
