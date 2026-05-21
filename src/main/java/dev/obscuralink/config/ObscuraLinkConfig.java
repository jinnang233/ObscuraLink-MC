package dev.obscuralink.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "obscuralink")
public final class ObscuraLinkConfig implements ConfigData {
    public boolean showProgress = true;
    public boolean hideEncryptedRawMessage = true;
    public boolean verboseMessages = false;

    @ConfigEntry.BoundedDiscrete(min = 64, max = 200)
    public int fragmentSize = 180;

    @ConfigEntry.BoundedDiscrete(min = 0, max = 5000)
    public int sendDelayMs = 250;

    public boolean receiveRegexMode = false;
    public String receiveRegex = "^\\[OBSCURA\\] .+";
    public String kemAlgorithm = "CMCE/mceliece348864";
    public String signatureAlgorithm = "Falcon-512";
    public String aeadAlgorithm = "AES-256-GCM";
}
