package dev.obscuralink.chat;

import dev.obscuralink.config.ObscuraLinkConfig;
import dev.obscuralink.crypto.CryptoService;
import dev.obscuralink.fragment.FragmentService;
import dev.obscuralink.model.CachedSentMessage;
import dev.obscuralink.model.EncryptedPacket;
import dev.obscuralink.model.PublicIdentity;
import dev.obscuralink.model.SessionRecord;
import dev.obscuralink.model.TrustState;
import dev.obscuralink.protocol.PacketCodec;
import dev.obscuralink.service.KeyStoreService;
import dev.obscuralink.service.KeyTrustService;
import dev.obscuralink.service.SentMessageCacheService;
import dev.obscuralink.service.SessionService;
import dev.obscuralink.util.Hex;

import java.util.List;
import java.util.function.Consumer;

public final class ChatSendService {
    private final ObscuraLinkConfig config;
    private final KeyStoreService keyStoreService;
    private final KeyTrustService keyTrustService;
    private final SessionService sessionService;
    private final SentMessageCacheService sentMessageCacheService;
    private final CryptoService cryptoService;
    private final PacketCodec packetCodec;
    private final FragmentService fragmentService;
    private final Consumer<String> chatSender;
    private final Consumer<String> system;

    public ChatSendService(ObscuraLinkConfig config, KeyStoreService keyStoreService, KeyTrustService keyTrustService,
                           SessionService sessionService, SentMessageCacheService sentMessageCacheService,
                           CryptoService cryptoService, PacketCodec packetCodec, FragmentService fragmentService,
                           Consumer<String> chatSender, Consumer<String> system) {
        this.config = config;
        this.keyStoreService = keyStoreService;
        this.keyTrustService = keyTrustService;
        this.sessionService = sessionService;
        this.sentMessageCacheService = sentMessageCacheService;
        this.cryptoService = cryptoService;
        this.packetCodec = packetCodec;
        this.fragmentService = fragmentService;
        this.chatSender = chatSender;
        this.system = system;
    }

    public void sendKemMessage(String receiver, String message, boolean sign) {
        try {
            PublicIdentity identity = keyStoreService.findPublicIdentity(receiver)
                    .orElseThrow(() -> new IllegalStateException("No public key for " + receiver + ". Use /enc key import first."));
            ensureSendAllowed(receiver, identity);
            EncryptedPacket packet = cryptoService.encryptFor(identity, keyStoreService.local(),
                    keyStoreService.local().kemPublicKey().owner(), message, sign, config.enableCompression);
            sendPacket(packet, receiver);
            system.accept("[ObscuraLink] Sent encrypted message to " + receiver + ".");
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    public void exchange(String receiver) {
        try {
            PublicIdentity identity = keyStoreService.findPublicIdentity(receiver)
                    .orElseThrow(() -> new IllegalStateException("No public key for " + receiver + ". Use /enc key import first."));
            ensureSendAllowed(receiver, identity);
            SessionRecord record = sessionService.createLocalSession(receiver, identity.kemPublicKey().fingerprint());
            sendKemMessage(receiver, "/session " + record.sessionId() + " " + record.secret(), true);
            system.accept("[ObscuraLink] Session material prepared for " + receiver + ".");
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    public void sendSessionMessage(String receiver, String message) {
        // The current transport sends session payloads through the same authenticated KEM envelope.
        // Session records are persisted and command-compatible; a future packet type can swap in direct PSK AEAD.
        sendKemMessage(receiver, message, true);
    }

    public void resendLatest() {
        try {
            CachedSentMessage cached = sentMessageCacheService.latest()
                    .orElseThrow(() -> new IllegalStateException("No sent encrypted message is cached."));
            resend(cached);
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    public void resend(String messageId) {
        try {
            CachedSentMessage cached = sentMessageCacheService.find(messageId)
                    .orElseThrow(() -> new IllegalStateException("No cached encrypted message with id " + messageId + "."));
            resend(cached);
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    private void resend(CachedSentMessage cached) {
        sendFragments(cached.fragments());
        system.accept("[ObscuraLink] Resending encrypted fragments for " + cached.receiver()
                + " messageId=" + cached.messageId() + ".");
    }

    private void sendPacket(EncryptedPacket packet, String receiver) throws Exception {
        byte[] encoded = packetCodec.encode(packet);
        List<String> fragments = fragmentService.fragment(encoded, packet.messageId(), config.fragmentSize);
        sentMessageCacheService.remember(Hex.encode(packet.messageId()), receiver, fragments);
        sendFragments(fragments);
    }

    private void sendFragments(List<String> fragments) {
        Thread sender = new Thread(() -> {
            for (String fragment : fragments) {
                chatSender.accept(fragment);
                if (config.showProgress) {
                    system.accept("[ObscuraLink] Fragment sent.");
                }
                sleep(config.sendDelayMs);
            }
        }, "ObscuraLink Sender");
        sender.setDaemon(true);
        sender.start();
    }

    private void ensureSendAllowed(String receiver, PublicIdentity identity) throws Exception {
        TrustState trustState = keyTrustService.trustState(receiver, true);
        if (trustState == TrustState.DISTRUSTED) {
            throw new IllegalStateException("Public key for " + receiver + " is distrusted. Refusing to send.");
        }
        if (trustState == TrustState.TOFU_TRUSTED) {
            system.accept("[ObscuraLink] Encrypted send allowed, but " + identity.owner()
                    + "'s fingerprint has not been manually verified.");
        }
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
