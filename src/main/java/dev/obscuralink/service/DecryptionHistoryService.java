package dev.obscuralink.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.obscuralink.util.JsonSupport;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class DecryptionHistoryService {
    private static final Type HISTORY_TYPE = new TypeToken<Map<String, Instant>>() {
    }.getType();

    private final Path historyFile;
    private final Gson gson = JsonSupport.prettyGson();

    public DecryptionHistoryService(Path root) {
        this.historyFile = root.resolve("cache").resolve("decryption-history.json");
    }

    public synchronized void recordSuccess(String player) throws IOException {
        Map<String, Instant> history = readHistory();
        history.put(normalize(player), Instant.now());
        Files.createDirectories(historyFile.getParent());
        Files.writeString(historyFile, gson.toJson(history, HISTORY_TYPE), StandardCharsets.UTF_8);
    }

    public synchronized Optional<Instant> lastSuccess(String player) throws IOException {
        return Optional.ofNullable(readHistory().get(normalize(player)));
    }

    private Map<String, Instant> readHistory() throws IOException {
        if (!Files.exists(historyFile)) {
            return new HashMap<>();
        }
        Map<String, Instant> history = gson.fromJson(Files.readString(historyFile, StandardCharsets.UTF_8), HISTORY_TYPE);
        return history == null ? new HashMap<>() : new HashMap<>(history);
    }

    private static String normalize(String player) {
        return player.toLowerCase(Locale.ROOT);
    }
}
