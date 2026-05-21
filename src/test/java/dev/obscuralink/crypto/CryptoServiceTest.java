package dev.obscuralink.crypto;

import dev.obscuralink.model.EncryptedPacket;
import dev.obscuralink.model.LocalKeyMaterial;
import dev.obscuralink.model.PacketType;
import dev.obscuralink.model.PublicIdentity;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CryptoServiceTest {
    @Test
    void encryptDecryptAndSignVerify() throws Exception {
        CryptoService crypto = new CryptoService();
        LocalKeyMaterial alice = crypto.generateLocalKeys("alice", "alice-uuid");
        LocalKeyMaterial bob = crypto.generateLocalKeys("bob", "bob-uuid");

        EncryptedPacket packet = crypto.encryptFor(publicIdentity(bob), alice, "alice", "hello bob", true);
        String plaintext = crypto.decrypt(packet, bob, publicIdentity(alice));

        assertEquals("hello bob", plaintext);
        assertTrue(packet.signed());
    }

    @Test
    void wrongReceiverIsRejected() throws Exception {
        CryptoService crypto = new CryptoService();
        LocalKeyMaterial alice = crypto.generateLocalKeys("alice", "alice-uuid");
        LocalKeyMaterial bob = crypto.generateLocalKeys("bob", "bob-uuid");
        LocalKeyMaterial charlie = crypto.generateLocalKeys("charlie", "charlie-uuid");

        EncryptedPacket packet = crypto.encryptFor(publicIdentity(bob), alice, "alice", "hello bob", false);

        assertThrows(CryptoException.class, () -> crypto.decrypt(packet, charlie, publicIdentity(alice)));
    }

    @Test
    void modifiedCiphertextIsRejected() throws Exception {
        CryptoService crypto = new CryptoService();
        LocalKeyMaterial alice = crypto.generateLocalKeys("alice", "alice-uuid");
        LocalKeyMaterial bob = crypto.generateLocalKeys("bob", "bob-uuid");
        EncryptedPacket packet = crypto.encryptFor(publicIdentity(bob), alice, "alice", "hello bob", true);
        byte[] changed = packet.ciphertext().clone();
        changed[0] ^= 0x01;
        EncryptedPacket tampered = new EncryptedPacket(packet.protocolVersion(), packet.type(), packet.flags(),
                packet.sender(), packet.receiver(), packet.timestampMillis(), packet.messageId(),
                packet.aadFragmentIndex(), packet.aadFragmentTotal(), packet.algorithms(), packet.nonce(),
                packet.kemCiphertext(), changed, packet.signature());

        assertThrows(CryptoException.class, () -> crypto.decrypt(tampered, bob, publicIdentity(alice)));
    }

    @Test
    void compressedMessagesRoundTrip() throws Exception {
        CryptoService crypto = new CryptoService();
        LocalKeyMaterial alice = crypto.generateLocalKeys("alice", "alice-uuid");
        LocalKeyMaterial bob = crypto.generateLocalKeys("bob", "bob-uuid");
        String message = "hello bob ".repeat(80);

        EncryptedPacket packet = crypto.encryptFor(publicIdentity(bob), alice, "alice", message, true, true);
        String plaintext = crypto.decrypt(packet, bob, publicIdentity(alice));

        assertEquals(message, plaintext);
        assertTrue((packet.flags() & CryptoService.FLAG_COMPRESSED) != 0);
    }

    @Test
    void sessionMessagesUseSessionSecret() throws Exception {
        CryptoService crypto = new CryptoService();
        LocalKeyMaterial alice = crypto.generateLocalKeys("alice", "alice-uuid");
        LocalKeyMaterial bob = crypto.generateLocalKeys("bob", "bob-uuid");
        byte[] sessionSecret = new byte[32];
        new SecureRandom().nextBytes(sessionSecret);

        EncryptedPacket packet = crypto.encryptWithSession("bob", alice, "alice", sessionSecret,
                "hello over session", true, true);
        String plaintext = crypto.decryptWithSession(packet, bob, publicIdentity(alice), sessionSecret);

        assertEquals(PacketType.SESSION_MESSAGE, packet.type());
        assertEquals(0, packet.kemCiphertext().length);
        assertEquals("hello over session", plaintext);
        assertTrue(packet.ciphertext().length > 0);
    }

    @Test
    void sessionMessageWithWrongSecretIsRejected() throws Exception {
        CryptoService crypto = new CryptoService();
        LocalKeyMaterial alice = crypto.generateLocalKeys("alice", "alice-uuid");
        LocalKeyMaterial bob = crypto.generateLocalKeys("bob", "bob-uuid");
        byte[] sessionSecret = new byte[32];
        byte[] wrongSecret = new byte[32];
        new SecureRandom().nextBytes(sessionSecret);
        new SecureRandom().nextBytes(wrongSecret);

        EncryptedPacket packet = crypto.encryptWithSession("bob", alice, "alice", sessionSecret,
                "hello over session", true, false);

        assertThrows(CryptoException.class, () ->
                crypto.decryptWithSession(packet, bob, publicIdentity(alice), wrongSecret));
    }

    private static PublicIdentity publicIdentity(LocalKeyMaterial material) {
        return new PublicIdentity(material.kemPublicKey().owner(), material.kemPublicKey().uuid(),
                material.kemPublicKey(), material.signaturePublicKey());
    }
}
