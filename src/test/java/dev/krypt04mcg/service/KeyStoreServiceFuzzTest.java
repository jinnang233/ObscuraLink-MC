package dev.krypt04mcg.service;

import dev.krypt04mcg.crypto.CryptoService;
import dev.krypt04mcg.model.LocalKeyMaterial;
import dev.krypt04mcg.model.PublicIdentity;
import dev.krypt04mcg.util.Base64Url;
import dev.krypt04mcg.util.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KeyStoreServiceFuzzTest {
    private static final long SEED = 0x4B455953544F5245L;
    private static final int CASES = 36;

    @TempDir
    private Path tempDir;

    @Test
    void randomizedPublicIdentityImportsRoundTrip() throws Exception {
        CryptoService cryptoService = new CryptoService();
        KeyStoreService keyStoreService = new KeyStoreService(tempDir, cryptoService);
        keyStoreService.init("alice", "alice-uuid");
        PublicIdentity peer = publicIdentity(cryptoService.generateLocalKeys("peer", "peer-uuid"));
        String json = JsonSupport.prettyGson().toJson(peer);
        String encoded = Base64Url.encode(json.getBytes(StandardCharsets.UTF_8));
        Random random = new Random(SEED);

        for (int i = 0; i < CASES; i++) {
            String player = randomPlayer(random);
            String importData = random.nextBoolean() ? json : encoded;

            PublicIdentity imported = keyStoreService.importPublicIdentity(player, importData);
            PublicIdentity found = keyStoreService.findPublicIdentity(player).orElseThrow();

            assertEquals(peer.kemPublicKey().fingerprint(), imported.kemPublicKey().fingerprint());
            assertEquals(peer.signaturePublicKey().fingerprint(), found.signaturePublicKey().fingerprint());
            assertTrue(publicDir().normalize().startsWith(tempDir.normalize()));
        }
    }

    @Test
    void randomizedConfigRelativeFileImportsRoundTrip() throws Exception {
        CryptoService cryptoService = new CryptoService();
        KeyStoreService keyStoreService = new KeyStoreService(tempDir, cryptoService);
        keyStoreService.init("alice", "alice-uuid");
        PublicIdentity peer = publicIdentity(cryptoService.generateLocalKeys("disk-peer", "disk-peer-uuid"));
        String json = JsonSupport.prettyGson().toJson(peer);
        Random random = new Random(SEED ^ 0x46494C45L);

        for (int i = 0; i < 12; i++) {
            String fileName = "import-" + i + "-" + randomSafeName(random) + ".json";
            Files.writeString(tempDir.resolve(fileName), json, StandardCharsets.UTF_8);

            PublicIdentity imported = keyStoreService.importPublicIdentity(randomPlayer(random), fileName);

            assertEquals(peer.kemPublicKey().fingerprint(), imported.kemPublicKey().fingerprint());
        }
    }

    @Test
    void randomizedMalformedImportsAreRejectedWithoutWritingPublicKeys() throws Exception {
        CryptoService cryptoService = new CryptoService();
        KeyStoreService keyStoreService = new KeyStoreService(tempDir, cryptoService);
        keyStoreService.init("alice", "alice-uuid");
        int before = publicFileCount();
        Random random = new Random(SEED ^ 0xBADL);

        for (int i = 0; i < CASES; i++) {
            String player = "bad-" + i + "-" + randomSafeName(random);
            String malformed = randomMalformedInput(random);

            assertThrows(Exception.class, () -> keyStoreService.importPublicIdentity(player, malformed));
        }

        assertEquals(before, publicFileCount());
    }

    @Test
    void randomizedTofuChangesAreRejected() throws Exception {
        CryptoService cryptoService = new CryptoService();
        KeyStoreService keyStoreService = new KeyStoreService(tempDir, cryptoService);
        keyStoreService.init("alice", "alice-uuid");
        PublicIdentity first = publicIdentity(cryptoService.generateLocalKeys("first", "first-uuid"));
        PublicIdentity second = publicIdentity(cryptoService.generateLocalKeys("second", "second-uuid"));
        String firstJson = JsonSupport.prettyGson().toJson(first);
        String secondJson = JsonSupport.prettyGson().toJson(second);

        keyStoreService.importPublicIdentity("same-player", firstJson);

        assertThrows(Exception.class, () -> keyStoreService.importPublicIdentity("same-player", secondJson));
        assertEquals(first.kemPublicKey().fingerprint(),
                keyStoreService.findPublicIdentity("same-player").orElseThrow().kemPublicKey().fingerprint());
    }

    private int publicFileCount() throws Exception {
        try (var stream = Files.list(publicDir())) {
            return (int) stream.filter(Files::isRegularFile).count();
        }
    }

    private Path publicDir() {
        return tempDir.resolve("keys").resolve("public");
    }

    private static String randomPlayer(Random random) {
        int length = 1 + random.nextInt(28);
        StringBuilder builder = new StringBuilder(length);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.- []{}!@#$%^&()+=";
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private static String randomSafeName(Random random) {
        int length = 4 + random.nextInt(18);
        StringBuilder builder = new StringBuilder(length);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789_-";
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private static String randomMalformedInput(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> "";
            case 1 -> "{ \"owner\": \"missing keys\" }";
            case 2 -> Base64Url.encode(randomSafeName(random).getBytes(StandardCharsets.UTF_8));
            default -> randomSafeName(random) + "!";
        };
    }

    private static PublicIdentity publicIdentity(LocalKeyMaterial material) {
        return new PublicIdentity(material.kemPublicKey().owner(), material.kemPublicKey().uuid(),
                material.kemPublicKey(), material.signaturePublicKey());
    }
}
