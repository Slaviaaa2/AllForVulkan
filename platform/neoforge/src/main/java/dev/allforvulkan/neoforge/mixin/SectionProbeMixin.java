package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.SectionProbe;
import java.util.function.Supplier;
import net.minecraft.util.profiling.InactiveProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's disabled profiler still receives every section change. Applied only on request. */
@Mixin(InactiveProfiler.class)
public class SectionProbeMixin {
    @Inject(method = "push(Ljava/lang/String;)V", at = @At("HEAD"))
    private void afvPush(String section, CallbackInfo callback) { SectionProbe.push(section); }
    @Inject(method = "push(Ljava/util/function/Supplier;)V", at = @At("HEAD"))
    private void afvPushLazy(Supplier<String> section, CallbackInfo callback) { SectionProbe.push(section.get()); }
    @Inject(method = "popPush(Ljava/lang/String;)V", at = @At("HEAD"))
    private void afvPopPush(String section, CallbackInfo callback) { SectionProbe.popPush(section); }
    @Inject(method = "popPush(Ljava/util/function/Supplier;)V", at = @At("HEAD"))
    private void afvPopPushLazy(Supplier<String> section, CallbackInfo callback) { SectionProbe.popPush(section.get()); }
    @Inject(method = "pop", at = @At("HEAD"))
    private void afvPop(CallbackInfo callback) { SectionProbe.pop(); }
}
