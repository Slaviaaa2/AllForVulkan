package net.vulkanmod.mixin.screen;

import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenM extends Screen {
    protected OptionsScreenM(Component title) { super(title); }

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;",
            ordinal = 2), index = 1)
    private Supplier<Screen> afv$videoOptions(Supplier<Screen> original) {
        return () -> new VOptionScreen(Component.translatable("options.video"), this);
    }
}
