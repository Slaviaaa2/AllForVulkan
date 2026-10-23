package dev.allforvulkan.neoforge;

import dev.allforvulkan.api.PlatformServices;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Path;
import java.util.Optional;

public final class NeoForgePlatformServices implements PlatformServices {
    @Override public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
    @Override public String version() { return modVersion("allforvulkan").orElseThrow(); }
    @Override public Optional<String> modVersion(String modId) {
        return LoadingModList.get().getMods().stream()
                .filter(mod -> mod.getModId().equals(modId))
                .map(mod -> mod.getVersion().toString()).findFirst();
    }
}
