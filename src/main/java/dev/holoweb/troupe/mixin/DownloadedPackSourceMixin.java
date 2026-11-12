package dev.holoweb.troupe.mixin;

//? if >=1.20.3 {
import dev.holoweb.troupe.ClientAssets;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackLoadFeedback;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Troupe's asset packs are local: the server never sent them, so the client mustn't answer for them. During
 * configuration vanilla would take the answer as the end of its own resource pack task.
 */
@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {
    @Inject(method = "createPackResponseSender", at = @At("RETURN"), cancellable = true)
    private static void troupe$skipOwnPacks(Connection connection, CallbackInfoReturnable<PackLoadFeedback> cir) {
        PackLoadFeedback inner = cir.getReturnValue();
        cir.setReturnValue(new PackLoadFeedback() {
            @Override
            public void reportUpdate(UUID id, Update update) {
                if (!ClientAssets.ours(id)) inner.reportUpdate(id, update);
            }

            @Override
            public void reportFinalResult(UUID id, FinalResult result) {
                if (!ClientAssets.ours(id)) inner.reportFinalResult(id, result);
            }
        });
    }
}
//?}
