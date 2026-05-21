package dev.obscuralink.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.obscuralink.model.CachedSentMessage;
import dev.obscuralink.util.JsonSupport;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SentMessageCacheService {
    private static final Type CACHE_TYPE = new TypeToken<Map<String, CachedSentMessage>>() {
    }.getType();
    private static final int MAX_CACHED_MESSAGES = 12;

    private final Path cacheFile;
    private final Gson gson = JsonSupport.prettyGson();

    public SentMessageCacheService(Path root) {
        this.cacheFile = root.resolve("cache").resolve("sent-fragments.json");
    }

    public synchronized void remember(String messageId, String receiver, List<String> fragments) throws IOException {
        Map<String, CachedSentMessage> cache = readCache();
        cache.put(messageId, new CachedSentMessage(messageId, receiver, Instant.now(), List.copyOf(fragments)));
        trim(cache);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, gson.toJson(cache, CACHE_TYPE), StandardCharsets.UTF_8);
    }

    public synchronized Optional<CachedSentMessage> latest() throws IOException {
        return readCache().values().stream()
                .max(Comparator.comparing(CachedSentMessage::createdAt));
    }

    public synchronized Optional<CachedSentMessage> find(String messageId) throws IOException {
        return Optional.ofNullable(readCache().get(messageId));
    }

    private Map<String, CachedSentMessage> readCache() throws IOException {
        if (!Files.exists(cacheFile)) {
            return new LinkedHashMap<>();
        }
        Map<String, CachedSentMessage> cache = gson.fromJson(Files.readString(cacheFile, StandardCharsets.UTF_8), CACHE_TYPE);
        return cache == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cache);
    }

    private static void trim(Map<String, CachedSentMessage> cache) {
        while (cache.size() > MAX_CACHED_MESSAGES) {
            String oldest = cache.values().stream()
                    .min(Comparator.comparing(CachedSentMessage::createdAt))
                    .map(CachedSentMessage::messageId)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            cache.remove(oldest);
        }
    }
}
