package net.vulkanmod.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // LWJGL 3.3.3 enumerates all device extensions on the thread-local stack.
        // Current drivers can exceed the default 64 KiB. Configure before Main
        // initializes its render-thread stack, including production launches.
        org.lwjgl.system.Configuration.STACK_SIZE.set(Math.max(1024,
                org.lwjgl.system.Configuration.STACK_SIZE.get(64)));
        dev.allforvulkan.neoforge.BackendBootstrap.initialize();
        if ((dev.allforvulkan.neoforge.BackendBootstrap.vulkan() && Boolean.getBoolean("afv.zinkInterop"))
                || dev.allforvulkan.neoforge.BackendBootstrap.zinkClient() || Boolean.getBoolean("afv.validationEgl"))
            org.lwjgl.system.Configuration.OPENGL_EXPLICIT_INIT.set(true);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".ZinkClientWindowMixin") || mixinClassName.endsWith(".ZinkClientFrameMixin")
                || mixinClassName.endsWith(".ZinkClientGameMixin"))
            return dev.allforvulkan.neoforge.BackendBootstrap.zinkClient();
        if (mixinClassName.endsWith(".EglValidationMixin"))
            return !dev.allforvulkan.neoforge.BackendBootstrap.usesVulkan() && Boolean.getBoolean("afv.validationEgl");
        if (mixinClassName.endsWith(".PackValidationMixin"))
            return Boolean.getBoolean("afv.validationPack");
        return dev.allforvulkan.neoforge.BackendBootstrap.vulkan();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }
}
