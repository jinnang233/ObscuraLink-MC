package dev.obscuralink.crypto;

import dev.obscuralink.model.AlgorithmSuite;
import dev.obscuralink.model.EncryptedPacket;
import dev.obscuralink.model.KeyRecord;
import dev.obscuralink.model.LocalKeyMaterial;
import dev.obscuralink.model.PacketType;
import dev.obscuralink.model.PublicIdentity;
import dev.obscuralink.protocol.PacketCodec;
import dev.obscuralink.util.Base64Url;
import dev.obscuralink.util.Hex;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;
import org.bouncycastle.pqc.jcajce.spec.CMCEParameterSpec;
import org.bouncycastle.pqc.jcajce.spec.FalconParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;

public final class CryptoService {
    public static final int MESSAGE_ID_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;
    private static final String BCPQC = "BCPQC";
    private static final String BC = "BC";

    private final SecureRandom secureRandom;
    private final PacketCodec packetCodec;

    public CryptoService() {
        this(new SecureRandom(), new PacketCodec());
    }

    public CryptoService(SecureRandom secureRandom, PacketCodec packetCodec) {
        ensureProviders();
        this.secureRandom = secureRandom;
        this.packetCodec = packetCodec;
    }

    public LocalKeyMaterial generateLocalKeys(String owner, String uuid) throws CryptoException {
        try {
            KeyPairGenerator kemGenerator = KeyPairGenerator.getInstance("CMCE", BCPQC);
            kemGenerator.initialize(CMCEParameterSpec.mceliece348864, secureRandom);
            KeyPair kem = kemGenerator.generateKeyPair();

            KeyPairGenerator sigGenerator = KeyPairGenerator.getInstance("Falcon", BCPQC);
            sigGenerator.initialize(FalconParameterSpec.falcon_512, secureRandom);
            KeyPair sig = sigGenerator.generateKeyPair();

            Instant now = Instant.now();
            return new LocalKeyMaterial(
                    keyRecord("CMCE/mceliece348864/public", owner, uuid, now, kem.getPublic().getEncoded()),
                    keyRecord("CMCE/mceliece348864/private", owner, uuid, now, kem.getPrivate().getEncoded()),
                    keyRecord("Falcon-512/public", owner, uuid, now, sig.getPublic().getEncoded()),
                    keyRecord("Falcon-512/private", owner, uuid, now, sig.getPrivate().getEncoded())
            );
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Unable to generate post-quantum keys", e);
        }
    }

    public byte[] randomMessageId() {
        byte[] id = new byte[MESSAGE_ID_BYTES];
        secureRandom.nextBytes(id);
        return id;
    }

    public EncryptedPacket encryptFor(PublicIdentity receiver, LocalKeyMaterial senderKeys, String sender, String message, boolean sign)
            throws CryptoException {
        try {
            byte[] messageId = randomMessageId();
            PublicKey kemPublic = decodePublicKey("CMCE", receiver.kemPublicKey().keyData());
            KeyGenerator keyGenerator = KeyGenerator.getInstance("CMCE", BCPQC);
            keyGenerator.init(new KEMGenerateSpec(kemPublic, "AES", AES_KEY_BYTES * 8), secureRandom);
            SecretKey kemSecret = keyGenerator.generateKey();
            byte[] encapsulation = extractEncapsulation(kemSecret);
            byte[] derivedKey = hkdf(kemSecret.getEncoded(), messageId, "obscuralink message aead".getBytes(StandardCharsets.UTF_8), AES_KEY_BYTES);
            byte[] nonce = randomNonce();

            EncryptedPacket packetTemplate = new EncryptedPacket(EncryptedPacket.VERSION,
                    sign ? PacketType.SIGNED_KEM_MESSAGE : PacketType.KEM_MESSAGE,
                    (byte) (sign ? 1 : 0), sender, receiver.owner(), System.currentTimeMillis(), messageId,
                    (short) 0, (short) 1, AlgorithmSuite.defaults(), nonce, encapsulation, new byte[0], new byte[0]);

            byte[] ciphertext = aeadEncrypt(derivedKey, nonce, packetCodec.aadFor(packetTemplate), message.getBytes(StandardCharsets.UTF_8));
            EncryptedPacket unsigned = new EncryptedPacket(packetTemplate.protocolVersion(), packetTemplate.type(), packetTemplate.flags(),
                    packetTemplate.sender(), packetTemplate.receiver(), packetTemplate.timestampMillis(), packetTemplate.messageId(),
                    packetTemplate.aadFragmentIndex(), packetTemplate.aadFragmentTotal(), packetTemplate.algorithms(), nonce,
                    encapsulation, ciphertext, new byte[0]);
            byte[] signature = sign ? sign(senderKeys.signaturePrivateKey(), packetCodec.signatureInput(unsigned)) : new byte[0];
            return new EncryptedPacket(unsigned.protocolVersion(), unsigned.type(), unsigned.flags(), unsigned.sender(),
                    unsigned.receiver(), unsigned.timestampMillis(), unsigned.messageId(), unsigned.aadFragmentIndex(),
                    unsigned.aadFragmentTotal(), unsigned.algorithms(), unsigned.nonce(), unsigned.kemCiphertext(),
                    unsigned.ciphertext(), signature);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Unable to encrypt message", e);
        }
    }

    public String decrypt(EncryptedPacket packet, LocalKeyMaterial receiverKeys, PublicIdentity claimedSender)
            throws CryptoException {
        if (!packet.receiver().equalsIgnoreCase(receiverKeys.kemPublicKey().owner())) {
            throw new CryptoException("Packet receiver mismatch: expected " + receiverKeys.kemPublicKey().owner() + ", got " + packet.receiver());
        }
        try {
            PrivateKey privateKey = decodePrivateKey("CMCE", receiverKeys.kemPrivateKey().keyData());
            KeyGenerator keyGenerator = KeyGenerator.getInstance("CMCE", BCPQC);
            keyGenerator.init(new KEMExtractSpec(privateKey, packet.kemCiphertext(), "AES", AES_KEY_BYTES * 8));
            SecretKey kemSecret = keyGenerator.generateKey();
            byte[] derivedKey = hkdf(kemSecret.getEncoded(), packet.messageId(),
                    "obscuralink message aead".getBytes(StandardCharsets.UTF_8), AES_KEY_BYTES);
            byte[] plaintext = aeadDecrypt(derivedKey, packet.nonce(), packetCodec.aadFor(packet), packet.ciphertext());
            if (packet.signed()) {
                EncryptedPacket unsigned = packetCodec.withoutSignature(packet);
                boolean valid = verify(claimedSender.signaturePublicKey(), packetCodec.signatureInput(unsigned), packet.signature());
                if (!valid) {
                    throw new CryptoException("Signature verification failed for " + packet.sender());
                }
            }
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Unable to decrypt message", e);
        }
    }

    public byte[] sign(KeyRecord privateKeyRecord, byte[] input) throws CryptoException {
        try {
            Signature signature = Signature.getInstance("Falcon", BCPQC);
            signature.initSign(decodePrivateKey("Falcon", privateKeyRecord.keyData()), secureRandom);
            signature.update(input);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Unable to sign packet", e);
        }
    }

    public boolean verify(KeyRecord publicKeyRecord, byte[] input, byte[] signatureBytes) throws CryptoException {
        try {
            Signature signature = Signature.getInstance("Falcon", BCPQC);
            signature.initVerify(decodePublicKey("Falcon", publicKeyRecord.keyData()));
            signature.update(input);
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Unable to verify packet signature", e);
        }
    }

    public byte[] deriveSessionSecret(byte[] secret, byte[] messageId) throws CryptoException {
        return hkdf(secret, messageId, "obscuralink session".getBytes(StandardCharsets.UTF_8), AES_KEY_BYTES);
    }

    public String fingerprint(byte[] encoded) throws CryptoException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            return Hex.encode(Arrays.copyOf(digest, 16));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Unable to fingerprint key", e);
        }
    }

    public KeyRecord keyRecord(String algorithm, String owner, String uuid, Instant createdAt, byte[] encoded) throws CryptoException {
        return new KeyRecord(algorithm, owner, uuid, fingerprint(encoded), createdAt, Base64Url.encode(encoded));
    }

    private static void ensureProviders() {
        if (Security.getProvider(BCPQC) == null) {
            Security.addProvider(new BouncyCastlePQCProvider());
        }
        if (Security.getProvider(BC) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        return nonce;
    }

    private static PublicKey decodePublicKey(String algorithm, String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance(algorithm, BCPQC).generatePublic(new X509EncodedKeySpec(Base64Url.decode(base64)));
    }

    private static PrivateKey decodePrivateKey(String algorithm, String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance(algorithm, BCPQC).generatePrivate(new PKCS8EncodedKeySpec(Base64Url.decode(base64)));
    }

    private static byte[] extractEncapsulation(SecretKey secretKey) throws GeneralSecurityException {
        try {
            return (byte[]) secretKey.getClass().getMethod("getEncapsulation").invoke(secretKey);
        } catch (ReflectiveOperationException first) {
            try {
                return (byte[]) secretKey.getClass().getMethod("getEncodedEncapsulation").invoke(secretKey);
            } catch (ReflectiveOperationException second) {
                GeneralSecurityException e = new GeneralSecurityException("BC KEM secret key does not expose encapsulation");
                e.addSuppressed(first);
                e.addSuppressed(second);
                throw e;
            }
        }
    }

    private static byte[] aeadEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    private static byte[] aeadDecrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws CryptoException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt == null || salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            byte[] okm = new byte[length];
            byte[] previous = new byte[0];
            int offset = 0;
            int counter = 1;
            while (offset < length) {
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                previous = mac.doFinal();
                int copy = Math.min(previous.length, length - offset);
                System.arraycopy(previous, 0, okm, offset, copy);
                offset += copy;
                counter++;
            }
            return okm;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("HKDF failed", e);
        }
    }
}
