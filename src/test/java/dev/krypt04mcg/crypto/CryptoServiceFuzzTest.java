package dev.krypt04mcg.crypto;

import dev.krypt04mcg.model.EncryptedPacket;
import dev.krypt04mcg.model.LocalKeyMaterial;
import dev.krypt04mcg.model.PacketType;
import dev.krypt04mcg.model.PublicIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CryptoServiceFuzzTest {
    private static final long SEED = 0x43525950544FL;
    private static final int CASES = 18;

    private static CryptoService crypto;
    private static LocalKeyMaterial alice;
    private static LocalKeyMaterial bob;

    @BeforeAll
    static void generateKeys() throws Exception {
        crypto = new CryptoService();
        alice = crypto.generateLocalKeys("alice", "alice-uuid");
        bob = crypto.generateLocalKeys("bob", "bob-uuid");
    }

    @Test
    void randomizedKemMessagesRoundTrip() throws Exception {
        Random random = new Random(SEED);

        for (int i = 0; i < CASES; i++) {
            String message = randomMessage(random);
            boolean sign = random.nextBoolean();
            boolean compress = random.nextBoolean();

            EncryptedPacket packet = crypto.encryptFor(publicIdentity(bob), alice, "alice", message, sign, compress);

            assertEquals(message, crypto.decrypt(packet, bob, publicIdentity(alice)));
            assertEquals(sign, packet.signed());
            assertEquals(sign ? PacketType.SIGNED_KEM_MESSAGE : PacketType.KEM_MESSAGE, packet.type());
            assertEquals(compress, (packet.flags() & CryptoService.FLAG_COMPRESSED) != 0);
        }
    }

    @Test
    void randomizedSessionMessagesRoundTrip() throws Exception {
        Random random = new Random(SEED ^ 0x51514CL);

        for (int i = 0; i < CASES; i++) {
            byte[] sessionSecret = randomBytes(random, 32);
            String message = randomMessage(random);
            boolean sign = random.nextBoolean();
            boolean compress = random.nextBoolean();

            EncryptedPacket packet = crypto.encryptWithSession("bob", alice, "alice", sessionSecret, message, sign, compress);

            assertEquals(message, crypto.decryptWithSession(packet, bob, publicIdentity(alice), sessionSecret));
            assertEquals(PacketType.SESSION_MESSAGE, packet.type());
            assertEquals(0, packet.kemCiphertext().length);
            assertEquals(sign, packet.signed());
            assertEquals(compress, (packet.flags() & CryptoService.FLAG_COMPRESSED) != 0);
        }
    }

    @Test
    void randomizedTamperingIsRejected() throws Exception {
        Random random = new Random(SEED ^ 0x7A6D70L);

        for (int i = 0; i < CASES; i++) {
            EncryptedPacket packet = crypto.encryptFor(publicIdentity(bob), alice, "alice",
                    randomMessage(random), true, random.nextBoolean());
            EncryptedPacket tampered = switch (random.nextInt(4)) {
                case 0 -> withChangedCiphertext(packet, random);
                case 1 -> withChangedNonce(packet, random);
                case 2 -> withChangedMessageId(packet, random);
                default -> withChangedSignature(packet, random);
            };

            assertThrows(CryptoException.class, () -> crypto.decrypt(tampered, bob, publicIdentity(alice)));
        }
    }

    @Test
    void randomizedSignaturesVerifyOnlyOriginalInput() throws Exception {
        Random random = new Random(SEED ^ 0x5141L);

        for (int i = 0; i < CASES; i++) {
            byte[] input = randomBytes(random, random.nextInt(512));
            byte[] signature = crypto.sign(alice.signaturePrivateKey(), input);

            assertTrue(crypto.verify(alice.signaturePublicKey(), input, signature));

            byte[] changed = input.clone();
            if (changed.length == 0) {
                changed = new byte[] {1};
            } else {
                changed[random.nextInt(changed.length)] ^= 0x01;
            }
            assertFalse(crypto.verify(alice.signaturePublicKey(), changed, signature));
        }
    }

    private static EncryptedPacket withChangedCiphertext(EncryptedPacket packet, Random random) {
        byte[] ciphertext = packet.ciphertext().clone();
        ciphertext[random.nextInt(ciphertext.length)] ^= 0x01;
        return replace(packet, packet.messageId(), packet.nonce(), ciphertext, packet.signature());
    }

    private static EncryptedPacket withChangedNonce(EncryptedPacket packet, Random random) {
        byte[] nonce = packet.nonce().clone();
        nonce[random.nextInt(nonce.length)] ^= 0x01;
        return replace(packet, packet.messageId(), nonce, packet.ciphertext(), packet.signature());
    }

    private static EncryptedPacket withChangedMessageId(EncryptedPacket packet, Random random) {
        byte[] messageId = packet.messageId().clone();
        messageId[random.nextInt(messageId.length)] ^= 0x01;
        return replace(packet, messageId, packet.nonce(), packet.ciphertext(), packet.signature());
    }

    private static EncryptedPacket withChangedSignature(EncryptedPacket packet, Random random) {
        byte[] signature = packet.signature().clone();
        signature[random.nextInt(signature.length)] ^= 0x01;
        return replace(packet, packet.messageId(), packet.nonce(), packet.ciphertext(), signature);
    }

    private static EncryptedPacket replace(EncryptedPacket packet, byte[] messageId, byte[] nonce,
                                           byte[] ciphertext, byte[] signature) {
        return new EncryptedPacket(packet.protocolVersion(), packet.type(), packet.flags(), packet.sender(),
                packet.receiver(), packet.timestampMillis(), messageId, packet.aadFragmentIndex(),
                packet.aadFragmentTotal(), packet.algorithms(), nonce, packet.kemCiphertext(), ciphertext,
                signature);
    }

    private static String randomMessage(Random random) {
        int length = random.nextInt(180);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int choice = random.nextInt(12);
            if (choice == 0) {
                builder.append('\n');
            } else if (choice == 1) {
                builder.append((char) (0x4E00 + random.nextInt(0x200)));
            } else {
                builder.append((char) (32 + random.nextInt(95)));
            }
        }
        return builder.toString();
    }

    private static byte[] randomBytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static PublicIdentity publicIdentity(LocalKeyMaterial material) {
        return new PublicIdentity(material.kemPublicKey().owner(), material.kemPublicKey().uuid(),
                material.kemPublicKey(), material.signaturePublicKey());
    }
}
