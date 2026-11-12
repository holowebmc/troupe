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

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
*///?}

/** Draws the Troupe badge on servers whose ping said so ({@link ServerBadge}). */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class OnlineServerEntryMixin {
    @Shadow
    @Final
    private ServerData serverData;

    //? if >=26.1 {
    @Inject(method = "extractContent", at = @At("TAIL"))
    private void troupe$badge(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float delta, CallbackInfo ci) {
        var self = (ServerSelectionList.Entry) (Object) this;
        ServerBadge.draw(g, serverData, self.getContentX(), self.getContentY(), mouseX, mouseY);
    }
    //?} else {
    /*@Shadow
    @Final
    private JoinMultiplayerScreen screen;

    @Inject(method = "render", at = @At("TAIL"))
    private void troupe$badge(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                              boolean hovered, float delta, CallbackInfo ci) {
        ServerBadge.draw(g, screen, serverData, left, top, mouseX, mouseY);
    }
    *///?}
}
