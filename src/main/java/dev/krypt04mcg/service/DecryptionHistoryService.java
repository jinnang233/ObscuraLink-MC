package dev.krypt04mcg.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.krypt04mcg.util.Hex;
import dev.krypt04mcg.util.JsonSupport;

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
    private static final String PACKET_PREFIX = "packet:";
    private static final String NONCE_PREFIX = "nonce:";
    private static final int MAX_REPLAY_ENTRIES = 2048;

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

    public synchronized boolean recordAcceptedPacket(String player, byte[] messageId, byte[] nonce) throws IOException {
        Map<String, Instant> history = readHistory();
        String normalized = normalize(player);
        String packetKey = PACKET_PREFIX + normalized + ":" + Hex.encode(messageId);
        String nonceKey = NONCE_PREFIX + normalized + ":" + Hex.encode(nonce);
        if (history.containsKey(packetKey) || history.containsKey(nonceKey)) {
            return false;
        }
        Instant now = Instant.now();
        history.put(packetKey, now);
        history.put(nonceKey, now);
        trimReplayEntries(history);
        Files.createDirectories(historyFile.getParent());
        Files.writeString(historyFile, gson.toJson(history, HISTORY_TYPE), StandardCharsets.UTF_8);
        return true;
    }

    private Map<String, Instant> readHistory() throws IOException {
        if (!Files.exists(historyFile)) {
            return new HashMap<>();
        }
        Map<String, Instant> history = gson.fromJson(Files.readString(historyFile, StandardCharsets.UTF_8), HISTORY_TYPE);
        return history == null ? new HashMap<>() : new HashMap<>(history);
    }

    private static void trimReplayEntries(Map<String, Instant> history) {
        while (history.keySet().stream().filter(DecryptionHistoryService::isReplayKey).count() > MAX_REPLAY_ENTRIES) {
            String oldest = history.entrySet().stream()
                    .filter(entry -> isReplayKey(entry.getKey()))
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            history.remove(oldest);
        }
    }

    private static boolean isReplayKey(String key) {
        return key.startsWith(PACKET_PREFIX) || key.startsWith(NONCE_PREFIX);
    }

    private static String normalize(String player) {
        return player.toLowerCase(Locale.ROOT);
    }
}
