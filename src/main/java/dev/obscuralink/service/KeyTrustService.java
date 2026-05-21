package dev.obscuralink.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.obscuralink.model.PublicIdentity;
import dev.obscuralink.model.TrustState;
import dev.obscuralink.util.JsonSupport;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class KeyTrustService {
    private static final Type TRUST_TYPE = new TypeToken<Map<String, TrustState>>() {
    }.getType();

    private final Path trustFile;
    private final Gson gson = JsonSupport.prettyGson();

    public KeyTrustService(Path root) {
        this.trustFile = root.resolve("keys").resolve("trust.json");
    }

    public synchronized TrustState trustState(String player, boolean keyExists) throws IOException {
        TrustState stored = readTrust().get(normalize(player));
        if (stored != null) {
            return stored;
        }
        return keyExists ? TrustState.TOFU_TRUSTED : TrustState.UNTRUSTED;
    }

    public synchronized void markTofuTrusted(String player) throws IOException {
        setTrustState(player, TrustState.TOFU_TRUSTED);
    }

    public synchronized void markVerified(String player) throws IOException {
        setTrustState(player, TrustState.VERIFIED);
    }

    public synchronized void markDistrusted(String player) throws IOException {
        setTrustState(player, TrustState.DISTRUSTED);
    }

    public boolean fingerprintMatches(PublicIdentity identity, String fingerprint) {
        String normalized = fingerprint.toLowerCase(Locale.ROOT).trim();
        String kem = identity.kemPublicKey().fingerprint().toLowerCase(Locale.ROOT);
        String sig = identity.signaturePublicKey().fingerprint().toLowerCase(Locale.ROOT);
        return normalized.equals(kem) || normalized.equals(sig) || normalized.equals(kem + ":" + sig);
    }

    private void setTrustState(String player, TrustState state) throws IOException {
        Map<String, TrustState> trust = readTrust();
        trust.put(normalize(player), state);
        Files.createDirectories(trustFile.getParent());
        Files.writeString(trustFile, gson.toJson(trust, TRUST_TYPE), StandardCharsets.UTF_8);
    }

    private Map<String, TrustState> readTrust() throws IOException {
        if (!Files.exists(trustFile)) {
            return new HashMap<>();
        }
        Map<String, TrustState> trust = gson.fromJson(Files.readString(trustFile, StandardCharsets.UTF_8), TRUST_TYPE);
        return trust == null ? new HashMap<>() : new HashMap<>(trust);
    }

    private static String normalize(String player) {
        return player.toLowerCase(Locale.ROOT);
    }
}
