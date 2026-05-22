package dev.krypt04mcg.gui;

import dev.krypt04mcg.chat.ChatConversationStore;
import dev.krypt04mcg.chat.ChatSendService;
import dev.krypt04mcg.model.PublicIdentity;
import dev.krypt04mcg.service.KeyStoreService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Krypt04McgChatScreen extends Screen {
    private static final int PANEL_COLOR = 0xE812171C;
    private static final int HEADER_COLOR = 0xF01B2730;
    private static final int LIST_COLOR = 0xD91D252D;
    private static final int SELECTED_COLOR = 0xFF2D5F73;
    private static final int OUTGOING_COLOR = 0xFF8DDBA4;
    private static final int INCOMING_COLOR = 0xFFE6EEF7;

    private final ChatSendService chatSendService;
    private final KeyStoreService keyStoreService;
    private final ChatConversationStore conversationStore;
    private final List<String> players = new ArrayList<>();
    private final List<Button> playerButtons = new ArrayList<>();
    private EditBox playerBox;
    private EditBox messageBox;
    private int selectedPlayer;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listWidth;

    public Krypt04McgChatScreen(ChatSendService chatSendService, KeyStoreService keyStoreService,
                                ChatConversationStore conversationStore) {
        super(Component.translatable("text.krypt04mcg.gui.title"));
        this.chatSendService = chatSendService;
        this.keyStoreService = keyStoreService;
        this.conversationStore = conversationStore;
    }

    @Override
    protected void init() {
        loadPlayers();
        panelWidth = Math.min(620, width - 32);
        panelHeight = Math.min(320, height - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(16, (height - panelHeight) / 2);
        listWidth = Math.min(170, Math.max(126, panelWidth / 3));

        addPlayerButtons();

        int rightX = panelX + listWidth + 14;
        int rightWidth = panelWidth - listWidth - 28;
        playerBox = new EditBox(font, rightX, panelY + 38, rightWidth, 20,
                Component.translatable("text.krypt04mcg.gui.player"));
        playerBox.setMaxLength(32);
        playerBox.setHint(Component.translatable("text.krypt04mcg.gui.player_hint"));
        if (!players.isEmpty()) {
            selectedPlayer = Math.min(selectedPlayer, players.size() - 1);
            playerBox.setValue(players.get(selectedPlayer));
        }
        addRenderableWidget(playerBox);

        messageBox = new EditBox(font, rightX, panelY + panelHeight - 34, rightWidth - 178, 20,
                Component.translatable("text.krypt04mcg.gui.message"));
        messageBox.setMaxLength(512);
        messageBox.setHint(Component.translatable("text.krypt04mcg.gui.message_hint"));
        addRenderableWidget(messageBox);
        setInitialFocus(messageBox);

        addRenderableWidget(Button.builder(Component.translatable("text.krypt04mcg.gui.exchange"), button -> exchange())
                .bounds(rightX, panelY + panelHeight - 60, 86, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("text.krypt04mcg.gui.session"), button -> sendSession())
                .bounds(rightX + rightWidth - 174, panelY + panelHeight - 34, 82, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("text.krypt04mcg.gui.send"), button -> sendSigned())
                .bounds(rightX + rightWidth - 86, panelY + panelHeight - 34, 86, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_COLOR);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 26, HEADER_COLOR);
        graphics.fill(panelX, panelY + 26, panelX + listWidth, panelY + panelHeight, LIST_COLOR);
        graphics.text(font, title, panelX + 12, panelY + 9, 0xE8F3FF, false);
        graphics.text(font, Component.translatable("text.krypt04mcg.gui.players"), panelX + 12, panelY + 36, 0xAFC4D6, false);

        String receiver = currentReceiver();
        int rightX = panelX + listWidth + 14;
        int rightWidth = panelWidth - listWidth - 28;
        graphics.text(font, Component.translatable("text.krypt04mcg.gui.player"), rightX, panelY + 28, 0xAFC4D6, false);
        graphics.text(font, Component.translatable("text.krypt04mcg.gui.conversation", receiver.isEmpty() ? "-" : receiver),
                rightX, panelY + 66, 0xAFC4D6, false);

        int historyTop = panelY + 78;
        int historyBottom = panelY + panelHeight - 66;
        graphics.fill(rightX, historyTop, rightX + rightWidth, historyBottom, 0x6A071016);
        drawHistory(graphics, receiver, rightX + 8, historyTop + 8, rightWidth - 16, historyBottom - historyTop - 16);

        if (players.isEmpty()) {
            graphics.text(font, Component.translatable("text.krypt04mcg.gui.no_players"), panelX + 12,
                    panelY + 56, 0xD8A657, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257 || event.key() == 335) {
            sendSigned();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addPlayerButtons() {
        playerButtons.clear();
        int buttonY = panelY + 50;
        int visibleRows = Math.max(0, (panelHeight - 64) / 22);
        for (int i = 0; i < Math.min(players.size(), visibleRows); i++) {
            int index = i;
            Button button = Button.builder(Component.literal(players.get(i)), ignored -> selectPlayer(index))
                    .bounds(panelX + 10, buttonY + i * 22, listWidth - 20, 20)
                    .build();
            playerButtons.add(button);
            addRenderableWidget(button);
        }
    }

    private void selectPlayer(int index) {
        if (index < 0 || index >= players.size()) {
            return;
        }
        selectedPlayer = index;
        playerBox.setValue(players.get(selectedPlayer));
    }

    private void drawHistory(GuiGraphicsExtractor graphics, String receiver, int x, int y, int width, int height) {
        if (receiver.isEmpty()) {
            graphics.text(font, Component.translatable("text.krypt04mcg.gui.empty_conversation"), x, y, 0x8899A6, false);
            return;
        }
        List<String> lines = historyLines(receiver, width);
        int maxLines = Math.max(1, height / 10);
        int start = Math.max(0, lines.size() - maxLines);
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            int color = line.startsWith(">") ? OUTGOING_COLOR : INCOMING_COLOR;
            graphics.text(font, line, x, y + (i - start) * 10, color, false);
        }
    }

    private List<String> historyLines(String receiver, int width) {
        List<String> lines = new ArrayList<>();
        for (ChatConversationStore.Entry entry : conversationStore.messagesFor(receiver)) {
            String prefix = entry.outgoing() ? "> " : "< ";
            for (String wrapped : wrap(prefix + entry.message(), Math.max(12, width / 6))) {
                lines.add(wrapped);
            }
        }
        return lines;
    }

    private List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > maxChars) {
            lines.add(remaining.substring(0, maxChars));
            remaining = "  " + remaining.substring(maxChars);
        }
        lines.add(remaining);
        return lines;
    }

    private void sendSigned() {
        String receiver = currentReceiver();
        String message = messageBox.getValue().trim();
        if (receiver.isEmpty() || message.isEmpty()) {
            return;
        }
        if (chatSendService.sendKemMessage(receiver, message, true)) {
            conversationStore.outgoing(receiver, message);
            rememberPlayer(receiver);
            messageBox.setValue("");
        }
        messageBox.setFocused(true);
    }

    private void sendSession() {
        String receiver = currentReceiver();
        String message = messageBox.getValue().trim();
        if (receiver.isEmpty() || message.isEmpty()) {
            return;
        }
        if (chatSendService.sendSessionMessage(receiver, message)) {
            conversationStore.outgoing(receiver, message);
            rememberPlayer(receiver);
            messageBox.setValue("");
        }
        messageBox.setFocused(true);
    }

    private void exchange() {
        String receiver = currentReceiver();
        if (!receiver.isEmpty()) {
            if (chatSendService.exchange(receiver)) {
                rememberPlayer(receiver);
            }
        }
    }

    private String currentReceiver() {
        return playerBox == null ? "" : playerBox.getValue().trim();
    }

    private void rememberPlayer(String player) {
        if (players.stream().noneMatch(value -> value.equalsIgnoreCase(player))) {
            players.add(player);
            players.sort(Comparator.comparing(String::toLowerCase));
        }
    }

    private void loadPlayers() {
        players.clear();
        try {
            String localOwner = keyStoreService.local().kemPublicKey().owner();
            Set<String> names = new LinkedHashSet<>();
            keyStoreService.listPublicIdentities().stream()
                    .map(PublicIdentity::owner)
                    .filter(owner -> owner != null && !owner.isBlank())
                    .filter(owner -> !owner.equalsIgnoreCase(localOwner))
                    .forEach(names::add);
            names.addAll(conversationStore.peers());
            names.stream()
                    .sorted(Comparator.comparing(String::toLowerCase))
                    .forEach(players::add);
        } catch (Exception e) {
            Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal("[Krypt04Mcg][ERROR] " + e.getMessage()));
        }
    }
}
