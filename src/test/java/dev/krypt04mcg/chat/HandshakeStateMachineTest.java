package dev.krypt04mcg.chat;

import dev.krypt04mcg.config.Krypt04McgConfig;
import dev.krypt04mcg.crypto.CryptoService;
import dev.krypt04mcg.fragment.FragmentReassembler;
import dev.krypt04mcg.fragment.FragmentService;
import dev.krypt04mcg.model.EncryptedPacket;
import dev.krypt04mcg.model.LocalKeyMaterial;
import dev.krypt04mcg.model.PublicIdentity;
import dev.krypt04mcg.protocol.PacketCodec;
import dev.krypt04mcg.service.DecryptionHistoryService;
import dev.krypt04mcg.service.KeyStoreService;
import dev.krypt04mcg.service.SessionService;
import dev.krypt04mcg.util.Base64Url;
import dev.krypt04mcg.util.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HandshakeStateMachineTest {
    @TempDir
    private Path tempDir;

    @Test
    void signedSessionHandshakeAcceptsOnceAndRejectsReplay() throws Exception {
        Fixture fixture = fixture();
        byte[] sessionId = randomBytes(16);
        byte[] secret = randomBytes(32);
        EncryptedPacket packet = fixture.crypto.encryptFor(fixture.bobKeys.ownPublicIdentity(),
                fixture.aliceMaterial, "alice",
                "/session " + Base64Url.encode(sessionId) + " " + Base64Url.encode(secret), true, false);
        List<String> fragments = fixture.fragments.fragment(fixture.codec.encode(packet), packet.messageId(), 96);

        for (String fragment : fragments) {
            fixture.handler.handle("alice", fragment);
        }

        assertTrue(fixture.sessionService.find("alice").isPresent());
        assertTrue(!fixture.history.recordAcceptedPacket("alice", packet.messageId(), packet.nonce()));

        for (String fragment : fragments) {
            fixture.handler.handle("alice", fragment);
        }

        assertTrue(fixture.decryptedMessages.isEmpty());
    }

    @Test
    void outOfOrderAndDuplicateFragmentsStillProduceSinglePlaintext() throws Exception {
        Fixture fixture = fixture();
        EncryptedPacket packet = fixture.crypto.encryptFor(fixture.bobKeys.ownPublicIdentity(),
                fixture.aliceMaterial, "alice", "hello state machine", true, false);
        List<String> fragments = new ArrayList<>(fixture.fragments.fragment(fixture.codec.encode(packet), packet.messageId(), 96));

        fixture.handler.handle("alice", fragments.get(1));
        fixture.handler.handle("alice", fragments.get(1));
        fixture.handler.handle("alice", fragments.get(0));
        for (int i = 2; i < fragments.size(); i++) {
            fixture.handler.handle("alice", fragments.get(i));
        }

        assertEquals(List.of("hello state machine"), fixture.decryptedMessages);
    }

    @Test
    void missingFragmentsDoNotAdvanceStateMachine() throws Exception {
        Fixture fixture = fixture();
        EncryptedPacket packet = fixture.crypto.encryptFor(fixture.bobKeys.ownPublicIdentity(),
                fixture.aliceMaterial, "alice", "missing fragment", true, false);
        List<String> fragments = fixture.fragments.fragment(fixture.codec.encode(packet), packet.messageId(), 80);

        for (int i = 0; i < fragments.size() - 1; i++) {
            fixture.handler.handle("alice", fragments.get(i));
        }

        assertTrue(fixture.decryptedMessages.isEmpty());
        assertTrue(fixture.sessionService.find("alice").isEmpty());
    }

    @Test
    void decryptionHistoryRejectsDuplicateMessageIdOrNonce() throws Exception {
        DecryptionHistoryService history = new DecryptionHistoryService(tempDir);
        byte[] messageId = randomBytes(16);
        byte[] nonce = randomBytes(12);

        assertTrue(history.recordAcceptedPacket("alice", messageId, nonce));
        assertTrue(!history.recordAcceptedPacket("alice", messageId, randomBytes(12)));
        assertTrue(!history.recordAcceptedPacket("alice", randomBytes(16), nonce));
        assertTrue(history.recordAcceptedPacket("bob", messageId, nonce));
    }

    private Fixture fixture() throws Exception {
        CryptoService crypto = new CryptoService();
        KeyStoreService bobKeys = new KeyStoreService(tempDir.resolve("bob"), crypto);
        bobKeys.init("bob", "bob-uuid");
        LocalKeyMaterial alice = crypto.generateLocalKeys("alice", "alice-uuid");
        Files.writeString(tempDir.resolve("bob").resolve("keys").resolve("public").resolve("alice.json"),
                JsonSupport.prettyGson().toJson(publicIdentity(alice)));
        PacketCodec codec = new PacketCodec();
        FragmentService fragments = new FragmentService();
        SessionService sessionService = new SessionService(tempDir.resolve("bob"));
        List<String> systemMessages = new ArrayList<>();
        List<String> decryptedMessages = new ArrayList<>();
        DecryptionHistoryService history = new DecryptionHistoryService(tempDir.resolve("bob"));
        ChatReceiveHandler handler = new ChatReceiveHandler(new Krypt04McgConfig(), bobKeys, crypto, codec, fragments,
                new FragmentReassembler(), history, sessionService,
                systemMessages::add, (player, message) -> decryptedMessages.add(message));
        return new Fixture(crypto, bobKeys, alice, codec, fragments, sessionService, history, systemMessages, decryptedMessages, handler);
    }

    private static PublicIdentity publicIdentity(LocalKeyMaterial material) {
        return new PublicIdentity(material.kemPublicKey().owner(), material.kemPublicKey().uuid(),
                material.kemPublicKey(), material.signaturePublicKey());
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private record Fixture(CryptoService crypto, KeyStoreService bobKeys, LocalKeyMaterial aliceMaterial,
                           PacketCodec codec, FragmentService fragments, SessionService sessionService,
                           DecryptionHistoryService history, List<String> systemMessages,
                           List<String> decryptedMessages, ChatReceiveHandler handler) {
    }
}
