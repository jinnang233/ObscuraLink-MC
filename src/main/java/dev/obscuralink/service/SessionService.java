package dev.obscuralink.service;

import com.google.gson.Gson;
import dev.obscuralink.model.SessionRecord;
import dev.obscuralink.util.Base64Url;
import dev.obscuralink.util.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class SessionService {
    private final Path sessionsDir;
    private final SecureRandom random = new SecureRandom();
    private final Gson gson = JsonSupport.prettyGson();

    public SessionService(Path root) {
        this.sessionsDir = root.resolve("sessions");
    }

    public SessionRecord createLocalSession(String peer, String peerFingerprint) throws IOException {
        Files.createDirectories(sessionsDir);
        byte[] id = new byte[16];
        byte[] secret = new byte[32];
        random.nextBytes(id);
        random.nextBytes(secret);
        SessionRecord record = new SessionRecord(peer, peerFingerprint, Base64Url.encode(id), Instant.now(), Instant.now(),
                Base64Url.encode(secret), 0, 0L);
        save(record);
        return record;
    }

    public SessionRecord acceptRemoteSession(String peer, String peerFingerprint, String sessionId, String secret)
            throws IOException {
        Files.createDirectories(sessionsDir);
        SessionRecord record = new SessionRecord(peer, peerFingerprint, sessionId, Instant.now(), Instant.now(),
                secret, 0, 0L);
        save(record);
        return record;
    }

    public Optional<SessionRecord> find(String peer) throws IOException {
        Path path = pathFor(peer);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), SessionRecord.class));
    }

    public void save(SessionRecord record) throws IOException {
        Files.createDirectories(sessionsDir);
        Files.writeString(pathFor(record.peer()), gson.toJson(record), StandardCharsets.UTF_8);
    }

    public List<SessionRecord> list() throws IOException {
        if (!Files.exists(sessionsDir)) {
            return List.of();
        }
        try (var stream = Files.list(sessionsDir)) {
            return stream.filter(path -> path.toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            return gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), SessionRecord.class);
                        } catch (IOException e) {
                            throw new IllegalStateException("Unable to read session " + path, e);
                        }
                    })
                    .toList();
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    public void clear(String peer) throws IOException {
        Files.deleteIfExists(pathFor(peer));
    }

    public void recordMessage(String peer, long bytes) throws IOException {
        Optional<SessionRecord> existing = find(peer);
        if (existing.isEmpty()) {
            return;
        }
        SessionRecord session = existing.get();
        save(new SessionRecord(session.peer(), session.peerFingerprint(), session.sessionId(), session.createdAt(),
                Instant.now(), session.secret(), session.messageCount() + 1, session.bytesUsed() + Math.max(0, bytes)));
    }

    public boolean isExpired(SessionRecord session, int ttlMinutes, int maxMessages, long rotateAfterBytes) {
        Instant expiresAt = session.createdAt().plus(Duration.ofMinutes(ttlMinutes));
        return Instant.now().isAfter(expiresAt)
                || session.messageCount() >= maxMessages
                || session.bytesUsed() >= rotateAfterBytes;
    }

    private Path pathFor(String peer) {
        return sessionsDir.resolve(peer.toLowerCase().replaceAll("[^a-z0-9_.-]", "_") + ".json");
    }
}
