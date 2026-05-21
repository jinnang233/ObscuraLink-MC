package dev.obscuralink.model;

public record AlgorithmSuite(String kem, String signature, String aead, String hkdf) {
    public static AlgorithmSuite defaults() {
        return new AlgorithmSuite("CMCE/mceliece348864", "Falcon-512", "AES-256-GCM", "HKDF-SHA256");
    }
}
