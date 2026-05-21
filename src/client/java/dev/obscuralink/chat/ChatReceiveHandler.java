package dev.obscuralink.chat;

import dev.obscuralink.client.ClientMessages;
import dev.obscuralink.config.ObscuraLinkConfig;
import dev.obscuralink.crypto.CryptoService;
import dev.obscuralink.fragment.FragmentReassembler;
import dev.obscuralink.fragment.FragmentService;
import dev.obscuralink.model.EncryptedPacket;
import dev.obscuralink.model.Fragment;
import dev.obscuralink.model.FragmentProgress;
import dev.obscuralink.model.PacketType;
import dev.obscuralink.model.PublicIdentity;
import dev.obscuralink.model.SessionRecord;
import dev.obscuralink.protocol.PacketCodec;
import dev.obscuralink.service.DecryptionHistoryService;
import dev.obscuralink.service.KeyStoreService;
import dev.obscuralink.service.SessionService;
import dev.obscuralink.util.Base64Url;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class ChatReceiveHandler {
    private final ObscuraLinkConfig config;
    private final KeyStoreService keyStoreService;
    private final CryptoService cryptoService;
    private final PacketCodec packetCodec;
    private final FragmentService fragmentService;
    private final FragmentReassembler reassembler;
    private final DecryptionHistoryService decryptionHistoryService;
    private final SessionService sessionService;
    private final ConcurrentMap<String, String> fragmentSenders = new ConcurrentHashMap<>();
    private final Consumer<String> system;

    public ChatReceiveHandler(ObscuraLinkConfig config, KeyStoreService keyStoreService, CryptoService cryptoService,
                              PacketCodec packetCodec, FragmentService fragmentService, FragmentReassembler reassembler,
                              DecryptionHistoryService decryptionHistoryService, SessionService sessionService,
                              Consumer<String> system) {
        this.config = config;
        this.keyStoreService = keyStoreService;
        this.cryptoService = cryptoService;
        this.packetCodec = packetCodec;
        this.fragmentService = fragmentService;
        this.reassembler = reassembler;
        this.decryptionHistoryService = decryptionHistoryService;
        this.sessionService = sessionService;
        this.system = system;
    }

    public boolean shouldHide(String raw) {
        return config.hideEncryptedRawMessage && extractFragmentLine(raw).isPresent();
    }

    public void handle(String senderName, String raw) {
        Optional<String> fragmentLine = extractFragmentLine(raw);
        if (fragmentLine.isEmpty()) {
            return;
        }
        try {
            for (FragmentProgress timeout : reassembler.cleanupTimedOut()) {
                String sender = fragmentSenders.getOrDefault(timeout.messageId(), senderName);
                fragmentSenders.remove(timeout.messageId());
                if (config.showReceiveProgress) {
                    system.accept(ClientMessages.tr("text.obscuralink.receive_timeout", sender, timeout.received(), timeout.total()));
                }
            }
            Fragment fragment = fragmentService.parse(fragmentLine.get());
            fragmentSenders.putIfAbsent(fragment.messageId(), senderName);
            Optional<byte[]> packetBytes = reassembler.accept(fragment);
            if (packetBytes.isEmpty()) {
                if (config.showReceiveProgress) {
                    reassembler.progress(fragment.messageId()).ifPresent(progress ->
                            system.accept(ClientMessages.tr("text.obscuralink.receiving", senderName,
                                    progress.received(), progress.total())));
                }
                return;
            }
            fragmentSenders.remove(fragment.messageId());
            if (config.showReceiveProgress) {
                system.accept(ClientMessages.tr("text.obscuralink.receive_complete", senderName));
            }
            EncryptedPacket packet = packetCodec.decode(packetBytes.get());
            if (!packet.receiver().equalsIgnoreCase(keyStoreService.local().kemPublicKey().owner())) {
                if (config.verboseMessages) {
                    system.accept(ClientMessages.tr("text.obscuralink.ignored_packet", packet.receiver()));
                }
                return;
            }
            PublicIdentity sender = keyStoreService.findPublicIdentity(packet.sender())
                    .orElseThrow(() -> new IllegalStateException(
                            ClientMessages.tr("text.obscuralink.error.no_sender_public_key", packet.sender())));
            String plaintext = switch (packet.type()) {
                case KEM_MESSAGE, SIGNED_KEM_MESSAGE, SESSION_EXCHANGE ->
                        cryptoService.decrypt(packet, keyStoreService.local(), sender);
                case SESSION_MESSAGE -> {
                    SessionRecord session = sessionService.find(packet.sender())
                            .orElseThrow(() -> new IllegalStateException(
                                    ClientMessages.tr("text.obscuralink.error.no_session", packet.sender())));
                    yield cryptoService.decryptWithSession(packet, keyStoreService.local(), sender,
                            Base64Url.decode(session.secret()));
                }
            };
            if (packet.type() == PacketType.SESSION_MESSAGE) {
                sessionService.recordMessage(packet.sender(), plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
            if (tryAcceptSessionExchange(packet, sender, plaintext)) {
                return;
            }
            decryptionHistoryService.recordSuccess(packet.sender());
            String status = packet.signed()
                    ? ClientMessages.tr("text.obscuralink.signature.valid")
                    : ClientMessages.tr("text.obscuralink.signature.unsigned");
            system.accept(ClientMessages.tr("text.obscuralink.decrypt_display", packet.sender(), status, plaintext));
        } catch (Exception e) {
            system.accept(ClientMessages.tr("text.obscuralink.decrypt_invalid", senderName, e.getMessage()));
        }
    }

    private boolean tryAcceptSessionExchange(EncryptedPacket packet, PublicIdentity sender, String plaintext) throws Exception {
        if (!plaintext.startsWith("/session ")) {
            return false;
        }
        if (!packet.signed()) {
            throw new IllegalArgumentException(ClientMessages.tr("text.obscuralink.error.session_exchange_unsigned"));
        }
        String[] parts = plaintext.split("\\s+", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException(ClientMessages.tr("text.obscuralink.error.session_exchange_invalid"));
        }
        if (Base64Url.decode(parts[1]).length != CryptoService.MESSAGE_ID_BYTES || Base64Url.decode(parts[2]).length != 32) {
            throw new IllegalArgumentException(ClientMessages.tr("text.obscuralink.error.session_exchange_key_material"));
        }
        sessionService.acceptRemoteSession(packet.sender(), sender.kemPublicKey().fingerprint(), parts[1], parts[2]);
        decryptionHistoryService.recordSuccess(packet.sender());
        system.accept(ClientMessages.tr("text.obscuralink.session_accepted", packet.sender()));
        return true;
    }

    private Optional<String> extractFragmentLine(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int index = raw.indexOf(FragmentService.PREFIX);
        if (index < 0) {
            return Optional.empty();
        }
        String fragment = raw.substring(index);
        if (config.receiveRegexMode && !Pattern.compile(config.receiveRegex).matcher(fragment).matches()) {
            return Optional.empty();
        }
        return Optional.of(fragment);
    }
}
