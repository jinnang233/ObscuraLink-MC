package dev.krypt04mcg.service;

import dev.krypt04mcg.crypto.CryptoService;
import dev.krypt04mcg.model.LocalKeyMaterial;
import dev.krypt04mcg.model.PublicIdentity;
import dev.krypt04mcg.util.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KeyStoreServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void generatesAndReloadsLocalKeys() throws Exception {
        CryptoService cryptoService = new CryptoService();
        KeyStoreService first = new KeyStoreService(tempDir, cryptoService);
        first.init("alice", "alice-uuid");

        Path localKeys = tempDir.resolve("keys").resolve("private").resolve("local.json");
        Path publicKeys = tempDir.resolve("keys").resolve("public").resolve("self-public.json");
        assertTrue(Files.exists(localKeys));
        assertTrue(Files.exists(publicKeys));

        KeyStoreService second = new KeyStoreService(tempDir, cryptoService);
        second.init("alice", "alice-uuid");

        assertEquals(first.local().kemPublicKey().fingerprint(), second.local().kemPublicKey().fingerprint());
        assertEquals(first.local().signaturePublicKey().fingerprint(), second.local().signaturePublicKey().fingerprint());
    }

    @Test
    void findsPublicIdentityByOwnerWhenFilenameDiffers() throws Exception {
        CryptoService cryptoService = new CryptoService();
        KeyStoreService keyStoreService = new KeyStoreService(tempDir, cryptoService);
        keyStoreService.init("alice", "alice-uuid");
        LocalKeyMaterial peerKeys = cryptoService.generateLocalKeys("foerdi", "foerdi-uuid");
        PublicIdentity peer = publicIdentity(peerKeys);

        Path publicDir = tempDir.resolve("keys").resolve("public");
        Files.writeString(publicDir.resolve("foerdi-public.json"), JsonSupport.prettyGson().toJson(peer));

        PublicIdentity found = keyStoreService.findPublicIdentity("foerdi").orElseThrow();
        assertEquals(peer.kemPublicKey().fingerprint(), found.kemPublicKey().fingerprint());
    }

    @Test
    void importsPublicIdentityFromConfigRelativeFile() throws Exception {
        CryptoService cryptoService = new CryptoService();
        KeyStoreService keyStoreService = new KeyStoreService(tempDir, cryptoService);
        keyStoreService.init("alice", "alice-uuid");
        LocalKeyMaterial peerKeys = cryptoService.generateLocalKeys("foerdi", "foerdi-uuid");
        PublicIdentity peer = publicIdentity(peerKeys);

        Files.writeString(tempDir.resolve("foerdi.json"), JsonSupport.prettyGson().toJson(peer));
        keyStoreService.importPublicIdentity("foerdi", "foerdi.json");

        PublicIdentity found = keyStoreService.findPublicIdentity("foerdi").orElseThrow();
        assertEquals(peer.signaturePublicKey().fingerprint(), found.signaturePublicKey().fingerprint());
    }

    @Test
    void importsPublicIdentityFromQuotedConfigRelativeFile() throws Exception {
        CryptoService cryptoService = new CryptoService();
        KeyStoreService keyStoreService = new KeyStoreService(tempDir, cryptoService);
        keyStoreService.init("alice", "alice-uuid");
        LocalKeyMaterial peerKeys = cryptoService.generateLocalKeys("foerdi", "foerdi-uuid");
        PublicIdentity peer = publicIdentity(peerKeys);

        Files.writeString(tempDir.resolve("foerdi quoted.json"), JsonSupport.prettyGson().toJson(peer));
        keyStoreService.importPublicIdentity("foerdi", "\"foerdi quoted.json\"");

        PublicIdentity found = keyStoreService.findPublicIdentity("foerdi").orElseThrow();
        assertEquals(peer.signaturePublicKey().fingerprint(), found.signaturePublicKey().fingerprint());
    }

    private static PublicIdentity publicIdentity(LocalKeyMaterial material) {
        return new PublicIdentity(material.kemPublicKey().owner(), material.kemPublicKey().uuid(),
                material.kemPublicKey(), material.signaturePublicKey());
    }
}
