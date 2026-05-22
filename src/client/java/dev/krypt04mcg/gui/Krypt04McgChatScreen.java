package dev.krypt04mcg.gui;

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
import java.util.List;

public final class Krypt04McgChatScreen extends Screen {
    private final ChatSendService chatSendService;
    private final KeyStoreService keyStoreService;
    private final List<String> players = new ArrayList<>();
    private EditBox playerBox;
    private EditBox messageBox;
    private int selectedPlayer;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public Krypt04McgChatScreen(ChatSendService chatSendService, KeyStoreService keyStoreService) {
        super(Component.literal("Krypt04Mcg"));
        this.chatSendService = chatSendService;
        this.keyStoreService = keyStoreService;
    }

    @Override
    protected void init() {
        loadPlayers();
        panelWidth = Math.min(360, width - 32);
        panelHeight = 166;
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(24, (height - panelHeight) / 2);

        playerBox = new EditBox(font, panelX + 18, panelY + 42, panelWidth - 36, 20, Component.literal("Player"));
        playerBox.setMaxLength(32);
        if (!players.isEmpty()) {
            selectedPlayer = Math.min(selectedPlayer, players.size() - 1);
            playerBox.setValue(players.get(selectedPlayer));
        }
        addRenderableWidget(playerBox);

        messageBox = new EditBox(font, panelX + 18, panelY + 82, panelWidth - 36, 20, Component.literal("Message"));
        messageBox.setMaxLength(512);
        addRenderableWidget(messageBox);
        setInitialFocus(messageBox);

        Button previous = Button.builder(Component.literal("<"), button -> selectPlayer(-1))
                .bounds(panelX + 18, panelY + 112, 28, 20)
                .build();
        previous.active = players.size() > 1;
        addRenderableWidget(previous);

        Button next = Button.builder(Component.literal(">"), button -> selectPlayer(1))
                .bounds(panelX + 50, panelY + 112, 28, 20)
                .build();
        next.active = players.size() > 1;
        addRenderableWidget(next);

        addRenderableWidget(Button.builder(Component.literal("Exchange"), button -> exchange())
                .bounds(panelX + 86, panelY + 112, 82, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Session"), button -> sendSession())
                .bounds(panelX + panelWidth - 186, panelY + 112, 78, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Send"), button -> sendSigned())
                .bounds(panelX + panelWidth - 102, panelY + 112, 84, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xDD101418);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 24, 0xEE1C2730);
        graphics.text(font, title, panelX + 12, panelY + 8, 0xE8F3FF, false);
        graphics.text(font, Component.literal("Player"), panelX + 18, panelY + 32, 0xAFC4D6, false);
        graphics.text(font, Component.literal("Message"), panelX + 18, panelY + 72, 0xAFC4D6, false);
        if (players.isEmpty()) {
            graphics.text(font, Component.literal("No imported keys; type a player name."), panelX + 18,
                    panelY + 140, 0xD8A657, false);
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

    private void selectPlayer(int direction) {
        if (players.isEmpty()) {
            return;
        }
        selectedPlayer = Math.floorMod(selectedPlayer + direction, players.size());
        playerBox.setValue(players.get(selectedPlayer));
    }

    private void sendSigned() {
        String receiver = playerBox.getValue().trim();
        String message = messageBox.getValue().trim();
        if (receiver.isEmpty() || message.isEmpty()) {
            return;
        }
        chatSendService.sendKemMessage(receiver, message, true);
        messageBox.setValue("");
        messageBox.setFocused(true);
    }

    private void sendSession() {
        String receiver = playerBox.getValue().trim();
        String message = messageBox.getValue().trim();
        if (receiver.isEmpty() || message.isEmpty()) {
            return;
        }
        chatSendService.sendSessionMessage(receiver, message);
        messageBox.setValue("");
        messageBox.setFocused(true);
    }

    private void exchange() {
        String receiver = playerBox.getValue().trim();
        if (!receiver.isEmpty()) {
            chatSendService.exchange(receiver);
        }
    }

    private void loadPlayers() {
        players.clear();
        try {
            String localOwner = keyStoreService.local().kemPublicKey().owner();
            keyStoreService.listPublicIdentities().stream()
                    .map(PublicIdentity::owner)
                    .filter(owner -> owner != null && !owner.isBlank())
                    .filter(owner -> !owner.equalsIgnoreCase(localOwner))
                    .distinct()
                    .sorted(Comparator.comparing(String::toLowerCase))
                    .forEach(players::add);
        } catch (Exception e) {
            Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal("[Krypt04Mcg][ERROR] " + e.getMessage()));
        }
    }
}
