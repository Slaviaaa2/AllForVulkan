package dev.allforvulkan.neoforge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.io.IOException;

public final class BackendScreen extends Screen {
    private final Screen parent;
    private BackendBootstrap.Mode selected = BackendBootstrap.requested();
    private BackendBootstrap.Presentation selectedPresentation = BackendBootstrap.presentation();
    private BackendBootstrap.ShaderBackend selectedShader = BackendBootstrap.shaderBackend();
    private Component status;
    private MultiLineLabel detail;

    public BackendScreen(Screen parent) {
        super(Component.translatable("afv.backend.title"));
        this.parent = parent;
        status = Component.translatable("afv.backend.active", BackendBootstrap.usesVulkan() ? "Vulkan" : "OpenGL");
    }

    private static Component label(BackendBootstrap.Mode mode) {
        return mode == BackendBootstrap.Mode.AUTO ? Component.translatable("afv.backend.auto") : Component.literal(mode == BackendBootstrap.Mode.VULKAN ? "Vulkan" : "OpenGL");
    }

    @Override protected void init() {
        detail = MultiLineLabel.create(font, Component.literal(BackendBootstrap.reason()), Math.min(width - 40, 440));
        addRenderableWidget(CycleButton.<BackendBootstrap.Mode>builder(BackendScreen::label)
                .withValues(BackendBootstrap.Mode.values()).withInitialValue(selected)
                .create(width / 2 - 140, height - 135, 280, 20, Component.translatable("afv.backend.renderer"), (button, value) -> selected = value));
        addRenderableWidget(CycleButton.<BackendBootstrap.Presentation>builder(value -> Component.translatable(value == BackendBootstrap.Presentation.NATIVE ? "afv.presentation.native" : "afv.presentation.bridge"))
                .withValues(BackendBootstrap.Presentation.values()).withInitialValue(selectedPresentation)
                .create(width / 2 - 140, height - 110, 280, 20, Component.translatable("afv.presentation.title"), (button, value) -> selectedPresentation = value));
        addRenderableWidget(CycleButton.<BackendBootstrap.ShaderBackend>builder(value -> Component.translatable(value == BackendBootstrap.ShaderBackend.DIRECT ? "afv.shader.direct" : "afv.shader.zink"))
                .withValues(BackendBootstrap.ShaderBackend.values()).withInitialValue(selectedShader)
                .create(width / 2 - 140, height - 85, 280, 20, Component.translatable("afv.shader.title"), (button, value) -> selectedShader = value));
        addRenderableWidget(Button.builder(Component.translatable("afv.backend.save"), button -> {
            try {
                BackendBootstrap.save(selected, selectedPresentation, selectedShader);
                status = Component.translatable("afv.backend.saved");
            } catch (IOException | RuntimeException failure) {
                status = Component.translatable("afv.backend.error", failure.getMessage());
            }
        }).bounds(width / 2 - 140, height - 55, 135, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 + 5, height - 55, 135, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 25, 0xFFFFFF);
        graphics.drawCenteredString(font, status, width / 2, 47, 0xFFFFFF);
        if (height >= 250) detail.renderCentered(graphics, width / 2, 66, 12, 0xB0B0B0);
        graphics.drawCenteredString(font, Component.translatable("afv.backend.restart"), width / 2, height - 25, 0xFFD080);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
