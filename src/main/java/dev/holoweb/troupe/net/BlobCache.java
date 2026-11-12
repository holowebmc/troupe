package dev.holoweb.troupe.net;

import dev.holoweb.troupe.net.Protocol.Blob;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The client's copy of one server's files, encrypted at rest with that server's key (sent on every connection, never
 * written by the client). A blob lives at {@code dir/<HMAC(key, sha256)>} as a 12-byte nonce plus AES-GCM ciphertext,
 * so neither its name nor its content says anything without the key. Reads check the SHA-256 again.
 * <p>
 * This keeps casual hands off the files (browsing the folder, copying it); it doesn't stop a modified client, which
 * gets the key like any other.
 */
public final class BlobCache {
    private static final int NONCE = 12, TAG = 16;
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;
    /** Separate keys for content and names, both derived from the server's. */
    private final SecretKeySpec key, nameKey;

    /** {@code keyHex}: the server's 32-byte key, as the manifest carries it. */
    public BlobCache(Path dir, String keyHex) {
        this.dir = dir;
        if (keyHex.isEmpty()) { // Manifest.EMPTY: nothing to read
            key = nameKey = null;
            return;
        }
        SecretKeySpec server = new SecretKeySpec(HEX.parseHex(keyHex), "HmacSHA256");
        this.key = new SecretKeySpec(hmac(server, "troupe cache content"), "AES");
        this.nameKey = new SecretKeySpec(hmac(server, "troupe cache names"), "HmacSHA256");
    }

    private static byte[] hmac(SecretKeySpec k, String label) {
        return hmac(k, label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] hmac(SecretKeySpec k, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(k);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    public Path dir() {
        return dir;
    }

    /** Present and the right size (contents are checked on read). */
    public boolean has(Blob b) {
        try {
            return Files.size(file(b)) == b.size() + NONCE + TAG;
        } catch (IOException e) {
            return false;
        }
    }

    /** The blob's bytes; IOException when it's missing, tampered with or not what the manifest says. */
    public byte[] read(Blob b) throws IOException {
        byte[] stored = Files.readAllBytes(file(b));
        if (stored.length < NONCE + TAG) throw new IOException("cached file too short: " + b.sha256());
        byte[] plain;
        try {
            Cipher c = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(stored, NONCE), b);
            plain = c.doFinal(stored, NONCE, stored.length - NONCE);
        } catch (GeneralSecurityException e) {
            throw new IOException("cached file doesn't decrypt: " + b.sha256());
        }
        if (!HEX.formatHex(sha256().digest(plain)).equals(b.sha256())) throw new IOException("cached file doesn't match: " + b.sha256());
        return plain;
    }

    /** Writes the whole blob at once (small or empty ones). */
    public void write(Blob b, byte[] plain) throws IOException {
        try (OutputStream out = start(b)) {
            out.write(plain);
        }
        commit(b);
    }

    /** Streams a blob into its {@code .part} file; {@link #commit} once the bytes are checked, {@link #discard} if not. */
    public OutputStream start(Blob b) throws IOException {
        Files.createDirectories(dir);
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        OutputStream out = Files.newOutputStream(part(b));
        out.write(nonce);
        try {
            return new CipherOutputStream(out, cipher(Cipher.ENCRYPT_MODE, nonce, b));
        } catch (GeneralSecurityException e) {
            out.close();
            throw new IOException(e);
        }
    }

    public void commit(Blob b) throws IOException {
        Files.move(part(b), file(b), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void discard(Blob b) {
        try {
            Files.deleteIfExists(part(b));
        } catch (IOException ignored) {
        }
    }

    private Cipher cipher(int mode, byte[] nonce, Blob b) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG * 8, nonce));
        c.updateAAD(HEX.parseHex(b.sha256())); // a file renamed to another blob's name fails to decrypt
        return c;
    }

    private Path file(Blob b) {
        return dir.resolve(name(b));
    }

    private Path part(Blob b) {
        return dir.resolve(name(b) + ".part");
    }

    private String name(Blob b) {
        if (nameKey == null) throw new IllegalStateException("no cache key");
        return HEX.formatHex(hmac(nameKey, HEX.parseHex(b.sha256())));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    /** A fresh server key, as hex. */
    public static String newKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return HEX.formatHex(k);
    }
}
