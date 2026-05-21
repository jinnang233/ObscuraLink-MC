package dev.obscuralink.chat;

import dev.obscuralink.client.ClientMessages;
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
                    .orElseThrow(() -> new IllegalStateException(ClientMessages.tr("text.obscuralink.error.no_public_key", receiver)));
            ensureSendAllowed(receiver, identity);
            EncryptedPacket packet = cryptoService.encryptFor(identity, keyStoreService.local(),
                    keyStoreService.local().kemPublicKey().owner(), message, sign, config.enableCompression);
            sendPacket(packet, receiver);
            system.accept(ClientMessages.tr("text.obscuralink.sent_encrypted", receiver));
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    public void exchange(String receiver) {
        try {
            PublicIdentity identity = keyStoreService.findPublicIdentity(receiver)
                    .orElseThrow(() -> new IllegalStateException(ClientMessages.tr("text.obscuralink.error.no_public_key", receiver)));
            ensureSendAllowed(receiver, identity);
            SessionRecord record = sessionService.createLocalSession(receiver, identity.kemPublicKey().fingerprint());
            sendKemMessage(receiver, "/session " + record.sessionId() + " " + record.secret(), true);
            system.accept(ClientMessages.tr("text.obscuralink.session_prepared", receiver));
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    public void sendSessionMessage(String receiver, String message) {
        try {
            SessionRecord session = sessionService.find(receiver)
                    .orElseThrow(() -> new IllegalStateException(ClientMessages.tr("text.obscuralink.error.no_session", receiver)));
            if (sessionService.isExpired(session, config.sessionTtlMinutes, config.maxMessagesPerSession, config.rotateAfterBytes)) {
                throw new IllegalStateException(ClientMessages.tr("text.obscuralink.error.session_expired", receiver));
            }
            sendKemMessage(receiver, message, true);
            sessionService.recordMessage(receiver, message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    public void sendGroupMessage(String groupName, List<String> members, String message) {
        if (members.isEmpty()) {
            system.accept(ClientMessages.tr("text.obscuralink.error.group_empty", groupName));
            return;
        }
        system.accept(ClientMessages.tr("text.obscuralink.group_sending", groupName, members.size()));
        for (String member : members) {
            sendKemMessage(member, message, true);
        }
    }

    public void resendLatest() {
        try {
            CachedSentMessage cached = sentMessageCacheService.latest()
                    .orElseThrow(() -> new IllegalStateException(ClientMessages.tr("text.obscuralink.error.no_cached_message")));
            resend(cached);
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    public void resend(String messageId) {
        try {
            CachedSentMessage cached = sentMessageCacheService.find(messageId)
                    .orElseThrow(() -> new IllegalStateException(ClientMessages.tr("text.obscuralink.error.no_cached_message_id", messageId)));
            resend(cached);
        } catch (Exception e) {
            system.accept("[ObscuraLink][ERROR] " + e.getMessage());
        }
    }

    private void resend(CachedSentMessage cached) {
        sendFragments(cached.fragments());
        system.accept(ClientMessages.tr("text.obscuralink.resending", cached.receiver(), cached.messageId()));
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
                    system.accept(ClientMessages.tr("text.obscuralink.fragment_sent"));
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
            throw new IllegalStateException(ClientMessages.tr("text.obscuralink.error.distrusted_key", receiver));
        }
        if (trustState == TrustState.TOFU_TRUSTED) {
            system.accept(ClientMessages.tr("text.obscuralink.warning.tofu_unverified", identity.owner()));
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
