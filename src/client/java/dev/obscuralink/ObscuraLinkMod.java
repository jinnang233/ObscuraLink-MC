package dev.obscuralink;

import dev.obscuralink.chat.ChatReceiveHandler;
import dev.obscuralink.chat.ChatSendService;
import dev.obscuralink.command.CommandRegistrar;
import dev.obscuralink.config.ObscuraLinkConfig;
import dev.obscuralink.crypto.CryptoService;
import dev.obscuralink.fragment.FragmentReassembler;
import dev.obscuralink.fragment.FragmentService;
import dev.obscuralink.protocol.PacketCodec;
import dev.obscuralink.service.DecryptionHistoryService;
import dev.obscuralink.service.KeyStoreService;
import dev.obscuralink.service.KeyTrustService;
import dev.obscuralink.service.SentMessageCacheService;
import dev.obscuralink.service.SessionService;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ObscuraLinkMod implements ClientModInitializer {
    public static final String MOD_ID = "obscuralink";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ObscuraLinkMod instance;

    private ObscuraLinkConfig config;
    private KeyStoreService keyStoreService;
    private SessionService sessionService;
    private DecryptionHistoryService decryptionHistoryService;
    private KeyTrustService keyTrustService;
    private SentMessageCacheService sentMessageCacheService;
    private ChatSendService chatSendService;
    private ChatReceiveHandler chatReceiveHandler;

    public static ObscuraLinkMod instance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        AutoConfig.register(ObscuraLinkConfig.class, GsonConfigSerializer::new);
        config = AutoConfig.getConfigHolder(ObscuraLinkConfig.class).getConfig();

        PacketCodec packetCodec = new PacketCodec();
        CryptoService cryptoService = new CryptoService();
        FragmentService fragmentService = new FragmentService();
        FragmentReassembler reassembler = new FragmentReassembler();
        Path root = FabricLoader.getInstance().getConfigDir().resolve("obscuralink");
        keyStoreService = new KeyStoreService(root, cryptoService);
        sessionService = new SessionService(root);
        decryptionHistoryService = new DecryptionHistoryService(root);
        keyTrustService = new KeyTrustService(root);
        sentMessageCacheService = new SentMessageCacheService(root);

        MinecraftClient client = MinecraftClient.getInstance();
        String owner = client.getSession().getUsername();
        String uuid = client.getSession().getUuidOrNull() == null ? "" : client.getSession().getUuidOrNull().toString();
        try {
            keyStoreService.init(owner, uuid);
        } catch (Exception e) {
            LOGGER.error("Unable to initialize ObscuraLink keys", e);
            system("[ObscuraLink][ERROR] Key initialization failed. Encrypted chat is disabled; check the client log.");
            return;
        }

        chatSendService = new ChatSendService(config, keyStoreService, keyTrustService, sessionService, sentMessageCacheService, cryptoService, packetCodec,
                fragmentService, this::sendChatLine, this::system);
        chatReceiveHandler = new ChatReceiveHandler(config, keyStoreService, cryptoService, packetCodec, fragmentService,
                reassembler, decryptionHistoryService, this::system);

        CommandRegistrar.register(chatSendService, keyStoreService, keyTrustService, sessionService, decryptionHistoryService, config);
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String senderName = sender == null ? "unknown" : sender.getName();
            String raw = message.getString();
            chatReceiveHandler.handle(senderName, raw);
            return !chatReceiveHandler.shouldHide(raw);
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            Optional<ShadowMessage> shadowMessage = extractShadowMessage(message.getString());
            shadowMessage.ifPresent(value -> chatReceiveHandler.handle(value.player(), value.message()));
            return shadowMessage.map(value -> !chatReceiveHandler.shouldHide(value.message())).orElse(true);
        });
        LOGGER.info("ObscuraLink initialized");
    }

    private void sendChatLine(String line) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage(line);
        }
    }

    private void system(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(Text.literal(message));
            }
        });
    }

    private Optional<ShadowMessage> extractShadowMessage(String raw) {
        if (!config.shadowListenMode || raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Matcher matcher = Pattern.compile(config.shadowListenRegex).matcher(raw);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            String player = matcher.group("player");
            String message = matcher.group("message");
            if (player == null || message == null) {
                return Optional.empty();
            }
            return Optional.of(new ShadowMessage(player, message));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid ObscuraLink shadow listen regex: {}", config.shadowListenRegex, e);
            return Optional.empty();
        }
    }

    private record ShadowMessage(String player, String message) {
    }
}
