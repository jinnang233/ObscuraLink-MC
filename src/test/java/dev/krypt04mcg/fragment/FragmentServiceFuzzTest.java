package dev.krypt04mcg.fragment;

import dev.krypt04mcg.model.Fragment;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FragmentServiceFuzzTest {
    private static final SecureRandom SEED_RANDOM = new SecureRandom();
    private static final int CASES = 250;

    @Test
    void randomizedFragmentsStayWithinChatBufferLimitAndReassemble() {
        FragmentService service = new FragmentService();
        Random random = random("randomizedFragmentsStayWithinChatBufferLimitAndReassemble");

        for (int i = 0; i < CASES; i++) {
            byte[] packet = randomBytes(random, random.nextInt(4096));
            byte[] messageId = randomBytes(random, 16);
            int configuredPayloadSize = random.nextInt(1024);
            FragmentReassembler reassembler = new FragmentReassembler();

            List<String> lines = service.fragment(packet, messageId, configuredPayloadSize);

            for (String line : lines) {
                assertTrue(line.length() <= FragmentService.MAX_CHAT_MESSAGE_LENGTH,
                        "fragment length was " + line.length());
            }
            for (int j = 0; j < lines.size() - 1; j++) {
                assertTrue(reassembler.accept(service.parse(lines.get(j))).isEmpty());
            }
            assertArrayEquals(packet, reassembler.accept(service.parse(lines.get(lines.size() - 1))).orElseThrow());
        }
    }

    @Test
    void oversizedOrMalformedFragmentsAreRejectedCleanly() {
        FragmentService service = new FragmentService();
        Random random = random("oversizedOrMalformedFragmentsAreRejectedCleanly");

        for (int i = 0; i < CASES; i++) {
            String message = switch (random.nextInt(3)) {
                case 0 -> FragmentService.PREFIX + " id 0 1 " + "A".repeat(FragmentService.MAX_CHAT_MESSAGE_LENGTH);
                case 1 -> FragmentService.PREFIX + " id " + Integer.MAX_VALUE + " 1 A";
                default -> FragmentService.PREFIX + " id not-a-number 1 A";
            };

            assertThrows(RuntimeException.class, () -> service.parse(message));
        }
    }

    @Test
    void reassemblerRejectsFragmentCountsAboveConfiguredBufferLimit() {
        Random random = random("reassemblerRejectsFragmentCountsAboveConfiguredBufferLimit");

        for (int i = 0; i < CASES; i++) {
            int maxFragments = 1 + random.nextInt(32);
            FragmentReassembler reassembler = new FragmentReassembler(
                    java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(1), 16, maxFragments);
            Fragment fragment = new Fragment("msg-" + i, 0, maxFragments + 1 + random.nextInt(1024), "AA");

            assertThrows(IllegalArgumentException.class, () -> reassembler.accept(fragment));
        }
    }

    private static Random random(String testName) {
        long seed = SEED_RANDOM.nextLong();
        System.out.println(FragmentServiceFuzzTest.class.getSimpleName() + "." + testName + " seed=" + seed);
        return new Random(seed);
    }

    private static byte[] randomBytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
