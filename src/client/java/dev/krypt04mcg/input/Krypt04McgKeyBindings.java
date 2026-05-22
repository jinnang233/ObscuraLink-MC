package dev.krypt04mcg.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.krypt04mcg.Krypt04McgMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class Krypt04McgKeyBindings {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(Krypt04McgMod.MOD_ID, "krypt04mcg")
    );
    private static KeyMapping openChatGui;

    private Krypt04McgKeyBindings() {
    }

    public static void register(Krypt04McgMod mod) {
        openChatGui = new KeyMapping(
                "key.krypt04mcg.open_chat_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                CATEGORY
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openChatGui.consumeClick()) {
                mod.openChatScreen();
            }
        });
    }
}
