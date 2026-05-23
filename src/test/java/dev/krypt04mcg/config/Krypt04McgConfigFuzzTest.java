package dev.krypt04mcg.config;

import dev.krypt04mcg.util.JsonSupport;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class Krypt04McgConfigFuzzTest {
    private static final long SEED = 0x434F4E464947L;
    private static final int CASES = 200;

    @Test
    void randomizedConfigsJsonRoundTrip() {
        Random random = new Random(SEED);

        for (int i = 0; i < CASES; i++) {
            Krypt04McgConfig config = randomConfig(random);

            Krypt04McgConfig decoded = JsonSupport.prettyGson().fromJson(
                    JsonSupport.prettyGson().toJson(config), Krypt04McgConfig.class);

            assertNotNull(decoded);
            assertEquals(config.showProgress, decoded.showProgress);
            assertEquals(config.hideEncryptedRawMessage, decoded.hideEncryptedRawMessage);
            assertEquals(config.verboseMessages, decoded.verboseMessages);
            assertEquals(config.enableCompression, decoded.enableCompression);
            assertEquals(config.showReceiveProgress, decoded.showReceiveProgress);
            assertEquals(config.fragmentSize, decoded.fragmentSize);
            assertEquals(config.sendDelayMs, decoded.sendDelayMs);
            assertEquals(config.sessionTtlMinutes, decoded.sessionTtlMinutes);
            assertEquals(config.maxMessagesPerSession, decoded.maxMessagesPerSession);
            assertEquals(config.rotateAfterBytes, decoded.rotateAfterBytes);
            assertEquals(config.receiveRegexMode, decoded.receiveRegexMode);
            assertEquals(config.receiveRegex, decoded.receiveRegex);
            assertEquals(config.shadowListenMode, decoded.shadowListenMode);
            assertEquals(config.shadowListenRegex, decoded.shadowListenRegex);
            assertEquals(config.kemAlgorithm, decoded.kemAlgorithm);
            assertEquals(config.signatureAlgorithm, decoded.signatureAlgorithm);
            assertEquals(config.aeadAlgorithm, decoded.aeadAlgorithm);
        }
    }

    @Test
    void randomizedRegexConfigsCompileOrFailCleanly() {
        Random random = new Random(SEED ^ 0x5245474558L);

        for (int i = 0; i < CASES; i++) {
            Krypt04McgConfig config = randomConfig(random);

            compileOrReject(config.receiveRegex);
            compileOrReject(config.shadowListenRegex);
        }
    }

    private static void compileOrReject(String regex) {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static Krypt04McgConfig randomConfig(Random random) {
        Krypt04McgConfig config = new Krypt04McgConfig();
        config.showProgress = random.nextBoolean();
        config.hideEncryptedRawMessage = random.nextBoolean();
        config.verboseMessages = random.nextBoolean();
        config.enableCompression = random.nextBoolean();
        config.showReceiveProgress = random.nextBoolean();
        config.fragmentSize = 1 + random.nextInt(512);
        config.sendDelayMs = random.nextInt(10_000);
        config.sessionTtlMinutes = 1 + random.nextInt(3_000);
        config.maxMessagesPerSession = 1 + random.nextInt(20_000);
        config.rotateAfterBytes = Math.abs(random.nextLong());
        config.receiveRegexMode = random.nextBoolean();
        config.receiveRegex = randomRegex(random);
        config.shadowListenMode = random.nextBoolean();
        config.shadowListenRegex = randomRegex(random);
        config.kemAlgorithm = randomToken(random);
        config.signatureAlgorithm = randomToken(random);
        config.aeadAlgorithm = randomToken(random);
        return config;
    }

    private static String randomRegex(Random random) {
        String[] corpus = {
                "^\\[KRYPT04MCG\\] .+",
                "^<(?<player>[^>]+)>\\s*(?<message>.*)$",
                ".*",
                "[",
                "(?<player>.+",
                randomToken(random)
        };
        return corpus[random.nextInt(corpus.length)];
    }

    private static String randomToken(Random random) {
        int length = 1 + random.nextInt(32);
        StringBuilder builder = new StringBuilder(length);
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/_+.-[](){}?*^$ ";
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }
}
