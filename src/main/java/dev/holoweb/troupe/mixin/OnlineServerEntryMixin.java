package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.ServerBadge;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
*///?}

/** The Troupe chip on servers whose ping said so, and its click ({@link ServerBadge}). */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class OnlineServerEntryMixin {
    @Shadow
    @Final
    private ServerData serverData;

    //? if >=26.1 {
    @Inject(method = "extractContent", at = @At("TAIL"))
    private void troupe$badge(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta, CallbackInfo ci) {
        var self = (ServerSelectionList.Entry) (Object) this;
        ServerBadge.draw(g, serverData, self.getContentX(), self.getContentY(), self.getContentWidth(), mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void troupe$click(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (ServerBadge.click(serverData, event.x(), event.y())) cir.setReturnValue(true); // any button
    }
    //?} else {
    /*@Shadow
    @Final
    private JoinMultiplayerScreen screen;

    @Inject(method = "render", at = @At("TAIL"))
    private void troupe$badge(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                              boolean hovered, float delta, CallbackInfo ci) {
        ServerBadge.draw(g, screen, serverData, left, top, width, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void troupe$click(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (ServerBadge.click(serverData, mouseX, mouseY)) cir.setReturnValue(true); // any button
    }
    *///?}
}
