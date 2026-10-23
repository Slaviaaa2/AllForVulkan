package net.vulkanmod;

import dev.allforvulkan.api.PlatformServices;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.video.VideoModeManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class Initializer {
	public static final Logger LOGGER = LogManager.getLogger("AllForVulkan");

	private static String VERSION;
	public static Config CONFIG;

	public static synchronized void initialize(PlatformServices platform) {
        if (CONFIG != null) return;

        VERSION = platform.version();
        LOGGER.info("== All For Vulkan {} (VulkanMod fork) ==", VERSION);
        Platform.init();
        VideoModeManager.init();
        var configPath = platform.configDirectory().resolve("allforvulkan_settings.json");

		CONFIG = loadConfig(configPath);

	}

	private static Config loadConfig(Path path) {
		Config config = Config.load(path);

		if(config == null) {
			config = new Config();
			config.write();
		}

		return config;
	}

	public static String getVersion() {
		return VERSION;
	}
}
