package dev.obscuralink.chat;

import dev.obscuralink.config.ObscuraLinkConfig;
import dev.obscuralink.crypto.CryptoService;
import dev.obscuralink.fragment.FragmentReassembler;
import dev.obscuralink.fragment.FragmentService;
import dev.obscuralink.model.EncryptedPacket;
import dev.obscuralink.model.Fragment;
import dev.obscuralink.model.FragmentProgress;
import dev.obscuralink.model.PublicIdentity;
import dev.obscuralink.protocol.PacketCodec;
import dev.obscuralink.service.DecryptionHistoryService;
import dev.obscuralink.service.KeyStoreService;

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
    private final ConcurrentMap<String, String> fragmentSenders = new ConcurrentHashMap<>();
    private final Consumer<String> system;

    public ChatReceiveHandler(ObscuraLinkConfig config, KeyStoreService keyStoreService, CryptoService cryptoService,
                              PacketCodec packetCodec, FragmentService fragmentService, FragmentReassembler reassembler,
                              DecryptionHistoryService decryptionHistoryService, Consumer<String> system) {
        this.config = config;
        this.keyStoreService = keyStoreService;
        this.cryptoService = cryptoService;
        this.packetCodec = packetCodec;
        this.fragmentService = fragmentService;
        this.reassembler = reassembler;
        this.decryptionHistoryService = decryptionHistoryService;
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
                    system.accept("[ObscuraLink] " + sender + "'s encrypted message timed out; received "
                            + timeout.received() + "/" + timeout.total() + " fragments.");
                }
            }
            Fragment fragment = fragmentService.parse(fragmentLine.get());
            fragmentSenders.putIfAbsent(fragment.messageId(), senderName);
            Optional<byte[]> packetBytes = reassembler.accept(fragment);
            if (packetBytes.isEmpty()) {
                if (config.showReceiveProgress) {
                    reassembler.progress(fragment.messageId()).ifPresent(progress ->
                            system.accept("[ObscuraLink] Receiving encrypted message from " + senderName + ": "
                                    + progress.received() + "/" + progress.total()));
                }
                return;
            }
            fragmentSenders.remove(fragment.messageId());
            if (config.showReceiveProgress) {
                system.accept("[ObscuraLink] Encrypted message from " + senderName + " complete; decrypting...");
            }
            EncryptedPacket packet = packetCodec.decode(packetBytes.get());
            if (!packet.receiver().equalsIgnoreCase(keyStoreService.local().kemPublicKey().owner())) {
                if (config.verboseMessages) {
                    system.accept("[ObscuraLink] Ignored encrypted packet for " + packet.receiver() + ".");
                }
                return;
            }
            PublicIdentity sender = keyStoreService.findPublicIdentity(packet.sender())
                    .orElseThrow(() -> new IllegalStateException("No public key for sender " + packet.sender()));
            String plaintext = cryptoService.decrypt(packet, keyStoreService.local(), sender);
            decryptionHistoryService.recordSuccess(packet.sender());
            String status = packet.signed() ? "VALID" : "UNSIGNED";
            system.accept("[Encrypted][" + packet.sender() + "][" + status + "]: " + plaintext);
        } catch (Exception e) {
            system.accept("[Encrypted][" + senderName + "][INVALID]: " + e.getMessage());
        }
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
