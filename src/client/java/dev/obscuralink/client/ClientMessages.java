package dev.obscuralink.client;

import net.minecraft.client.resource.language.I18n;

public final class ClientMessages {
    private ClientMessages() {
    }

    public static String tr(String key, Object... args) {
        return I18n.translate(key, args);
    }
}
