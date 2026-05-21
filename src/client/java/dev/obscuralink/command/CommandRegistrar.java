package dev.obscuralink.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.obscuralink.chat.ChatSendService;
import dev.obscuralink.config.ObscuraLinkConfig;
import dev.obscuralink.model.PublicIdentity;
import dev.obscuralink.model.SessionRecord;
import dev.obscuralink.model.TrustState;
import dev.obscuralink.service.DecryptionHistoryService;
import dev.obscuralink.service.KeyStoreService;
import dev.obscuralink.service.KeyTrustService;
import dev.obscuralink.service.SessionService;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public final class CommandRegistrar {
    private static final DateTimeFormatter STATUS_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private CommandRegistrar() {
    }

    public static void register(ChatSendService chatSendService, KeyStoreService keyStoreService,
                                KeyTrustService keyTrustService, SessionService sessionService,
                                DecryptionHistoryService decryptionHistoryService, ObscuraLinkConfig config) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("enc")
                        .then(ClientCommandManager.literal("tell")
                                .then(ClientCommandManager.argument("receiver", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    chatSendService.sendKemMessage(
                                                            StringArgumentType.getString(ctx, "receiver"),
                                                            StringArgumentType.getString(ctx, "message"),
                                                            false);
                                                    return 1;
                                                }))))
                        .then(ClientCommandManager.literal("stell")
                                .then(ClientCommandManager.argument("receiver", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    chatSendService.sendKemMessage(
                                                            StringArgumentType.getString(ctx, "receiver"),
                                                            StringArgumentType.getString(ctx, "message"),
                                                            true);
                                                    return 1;
                                                }))))
                        .then(ClientCommandManager.literal("exchange")
                                .then(ClientCommandManager.argument("receiver", StringArgumentType.word())
                                        .executes(ctx -> {
                                            chatSendService.exchange(StringArgumentType.getString(ctx, "receiver"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("etell")
                                .then(ClientCommandManager.argument("receiver", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    chatSendService.sendSessionMessage(
                                                            StringArgumentType.getString(ctx, "receiver"),
                                                            StringArgumentType.getString(ctx, "message"));
                                                    return 1;
                                                }))))
                        .then(ClientCommandManager.literal("showalgs")
                                .executes(ctx -> {
                                    feedback(ctx.getSource(), "KEM=" + config.kemAlgorithm
                                            + ", SIG=" + config.signatureAlgorithm
                                            + ", AEAD=" + config.aeadAlgorithm);
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("status")
                                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                        .executes(ctx -> {
                                            showStatus(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                                                    keyStoreService, keyTrustService, sessionService, decryptionHistoryService, config);
                                            return 1;
                                        })))
                                .then(ClientCommandManager.literal("key")
                                        .then(ClientCommandManager.literal("list")
                                        .executes(ctx -> {
                                            List<PublicIdentity> identities;
                                            try {
                                                identities = keyStoreService.listPublicIdentities();
                                            } catch (Exception e) {
                                                feedback(ctx.getSource(), "ERROR: " + e.getMessage());
                                                return 0;
                                            }
                                            if (identities.isEmpty()) {
                                                feedback(ctx.getSource(), "No imported public keys.");
                                            }
                                            for (PublicIdentity identity : identities) {
                                                feedback(ctx.getSource(), identity.owner() + " kem="
                                                        + identity.kemPublicKey().fingerprint() + " sig="
                                                        + identity.signaturePublicKey().fingerprint());
                                            }
                                            return identities.size();
                                        }))
                                .then(ClientCommandManager.literal("fingerprint")
                                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String player = StringArgumentType.getString(ctx, "player");
                                                    try {
                                                        PublicIdentity identity = keyStoreService.findPublicIdentity(player)
                                                                .orElseThrow(() -> new IllegalStateException("No public key for " + player));
                                                        feedback(ctx.getSource(), player + " kem="
                                                                + identity.kemPublicKey().fingerprint() + " sig="
                                                                + identity.signaturePublicKey().fingerprint());
                                                    } catch (Exception e) {
                                                        feedback(ctx.getSource(), "ERROR: " + e.getMessage());
                                                        return 0;
                                                    }
                                                    return 1;
                                                })))
                                .then(ClientCommandManager.literal("export")
                                        .executes(ctx -> {
                                            try {
                                                feedback(ctx.getSource(), keyStoreService.exportOwnPublic());
                                            } catch (Exception e) {
                                                feedback(ctx.getSource(), "ERROR: " + e.getMessage());
                                                return 0;
                                            }
                                            return 1;
                                        }))
                                .then(ClientCommandManager.literal("import")
                                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                                .then(ClientCommandManager.argument("data_or_file", StringArgumentType.greedyString())
                                                        .executes(ctx -> {
                                                            String player = StringArgumentType.getString(ctx, "player");
                                                            try {
                                                                keyStoreService.importPublicIdentity(player,
                                                                        StringArgumentType.getString(ctx, "data_or_file"));
                                                                keyTrustService.markTofuTrusted(player);
                                                                feedback(ctx.getSource(), "Imported public key for " + player + " with TOFU trust.");
                                                            } catch (Exception e) {
                                                                feedback(ctx.getSource(), "ERROR: " + e.getMessage());
                                                                return 0;
                                                            }
                                                            return 1;
                                                        }))))
                                .then(ClientCommandManager.literal("confirm")
                                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String player = StringArgumentType.getString(ctx, "player");
                                                    try {
                                                        keyStoreService.findPublicIdentity(player)
                                                                .orElseThrow(() -> new IllegalStateException("No public key for " + player));
                                                        keyTrustService.markVerified(player);
                                                        feedback(ctx.getSource(), "Confirmed fingerprint for " + player + ".");
                                                    } catch (Exception e) {
                                                        feedback(ctx.getSource(), "ERROR: " + e.getMessage());
                                                        return 0;
                                                    }
                                                    return 1;
                                                })))
                                .then(ClientCommandManager.literal("verify")
                                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                                .then(ClientCommandManager.argument("fingerprint", StringArgumentType.word())
                                                        .executes(ctx -> {
                                                            String player = StringArgumentType.getString(ctx, "player");
                                                            String fingerprint = StringArgumentType.getString(ctx, "fingerprint");
                                                            try {
                                                                PublicIdentity identity = keyStoreService.findPublicIdentity(player)
                                                                        .orElseThrow(() -> new IllegalStateException("No public key for " + player));
                                                                if (!keyTrustService.fingerprintMatches(identity, fingerprint)) {
                                                                    feedback(ctx.getSource(), "ERROR: fingerprint does not match " + player + ".");
                                                                    return 0;
                                                                }
                                                                keyTrustService.markVerified(player);
                                                                feedback(ctx.getSource(), "Verified fingerprint for " + player + ".");
                                                            } catch (Exception e) {
                                                                feedback(ctx.getSource(), "ERROR: " + e.getMessage());
                                                                return 0;
                                                            }
                                                            return 1;
                                                        }))))
                                .then(ClientCommandManager.literal("trust")
                                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String player = StringArgumentType.getString(ctx, "player");
                                                    try {
                                                        keyStoreService.findPublicIdentity(player)
                                                                .orElseThrow(() -> new IllegalStateException("No public key for " + player));
                                                        keyTrustService.markTofuTrusted(player);
                                                        feedback(ctx.getSource(), "Marked " + player + " as TOFU trusted.");
                                                    } catch (Exception e) {
                                                        feedback(ctx.getSource(), "ERROR: " + e.getMessage());
                                                        return 0;
                                                    }
                                                    return 1;
                                                })))
                                .then(ClientCommandManager.literal("distrust")
                                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String player = StringArgumentType.getString(ctx, "player");
                                                    try {
                                                        keyStoreService.findPublicIdentity(player)
                                                                .orElseThrow(() -> new IllegalStateException("No public key for " + player));
                                                        keyTrustService.markDistrusted(player);
                                                        feedback(ctx.getSource(), "Marked " + player + " as distrusted.");
                                                    } catch (Exception e) {
                                                        feedback(ctx.getSource(), "ERROR: " + e.getMessage());
                                                        return 0;
                                                    }
                                                    return 1;
                                                })))))
        ));
    }

    private static void showStatus(FabricClientCommandSource source, String player, KeyStoreService keyStoreService,
                                   KeyTrustService keyTrustService, SessionService sessionService, DecryptionHistoryService decryptionHistoryService,
                                   ObscuraLinkConfig config) {
        try {
            Optional<PublicIdentity> identity = keyStoreService.findPublicIdentity(player);
            Optional<SessionRecord> session = sessionService.find(player);
            TrustState trustState = keyTrustService.trustState(player, identity.isPresent());
            String keyStatus = identity.map(value -> "已导入 / " + trustState).orElse("未导入");
            String signatureStatus = identity.map(value -> value.signaturePublicKey() == null ? "不可用" : "可用").orElse("不可用");
            String sessionStatus = session.map(value -> "已建立，sessionId=" + value.sessionId()).orElse("未建立");
            String lastDecrypt = decryptionHistoryService.lastSuccess(player)
                    .map(STATUS_TIME_FORMATTER::format)
                    .orElse("无记录");

            feedback(source, "玩家：" + player);
            feedback(source, "公钥状态：" + keyStatus);
            identity.ifPresent(value -> feedback(source, "指纹：kem=" + value.kemPublicKey().fingerprint()
                    + " sig=" + value.signaturePublicKey().fingerprint()));
            feedback(source, "签名验证：" + signatureStatus);
            feedback(source, "Session：" + sessionStatus);
            feedback(source, "最后成功解密：" + lastDecrypt);
            feedback(source, "当前算法：" + config.kemAlgorithm + " + " + config.signatureAlgorithm + " + " + config.aeadAlgorithm);
        } catch (Exception e) {
            feedback(source, "ERROR: " + e.getMessage());
        }
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Text.literal("[ObscuraLink] " + message));
    }
}
