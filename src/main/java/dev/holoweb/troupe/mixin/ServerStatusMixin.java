package dev.holoweb.troupe.mixin;

import com.mojang.serialization.Codec;
import dev.holoweb.troupe.TroupeStatus;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The status codec carries a {@code troupe} object both ways ({@link TroupeStatus}). */
@Mixin(ServerStatus.class)
public abstract class ServerStatusMixin {
    @Shadow
    @Final
    @Mutable
    public static Codec<ServerStatus> CODEC;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void troupe$wrapCodec(CallbackInfo ci) {
        CODEC = TroupeStatus.wrap(CODEC);
    }
}
