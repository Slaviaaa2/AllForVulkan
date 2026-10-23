package dev.allforvulkan.api;

import java.nio.file.Path;
import java.util.Optional;

/** Loader services; no Minecraft, loader, or LWJGL types may cross this boundary. */
public interface PlatformServices {
    Path configDirectory();
    String version();
    Optional<String> modVersion(String modId);
}
