package dev.obscuralink;

import dev.obscuralink.chat.ChatReceiveHandler;
import dev.obscuralink.chat.ChatSendService;
import dev.obscuralink.command.CommandRegistrar;
import dev.obscuralink.config.ObscuraLinkConfig;
import dev.obscuralink.crypto.CryptoService;
import dev.obscuralink.fragment.FragmentReassembler;
import dev.obscuralink.fragment.FragmentService;
import dev.obscuralink.protocol.PacketCodec;
import dev.obscuralink.service.KeyStoreService;
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

public final class ObscuraLinkMod implements ClientModInitializer {
    public static final String MOD_ID = "obscuralink";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ObscuraLinkMod instance;

    private ObscuraLinkConfig config;
    private KeyStoreService keyStoreService;
    private SessionService sessionService;
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

        chatSendService = new ChatSendService(config, keyStoreService, sessionService, cryptoService, packetCodec,
                fragmentService, this::sendChatLine, this::system);
        chatReceiveHandler = new ChatReceiveHandler(config, keyStoreService, cryptoService, packetCodec, fragmentService,
                reassembler, this::system);

        CommandRegistrar.register(chatSendService, keyStoreService, sessionService, config);
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                !chatReceiveHandler.shouldHide(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String senderName = sender == null ? "unknown" : sender.getName();
            chatReceiveHandler.handle(senderName, message.getString());
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
}
