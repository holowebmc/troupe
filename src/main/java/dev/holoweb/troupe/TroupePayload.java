package dev.holoweb.troupe;

//? if >=1.20.5 {
import dev.holoweb.troupe.net.Protocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The {@code troupe:v1} custom payload: raw protocol bytes, no Minecraft framing inside (see {@link Protocol}). */
public record TroupePayload(byte[] data) implements CustomPacketPayload {
    public static final Type<TroupePayload> TYPE = new Type<>(Identifier.parse(Protocol.CHANNEL));
    public static final StreamCodec<FriendlyByteBuf, TroupePayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data),
            buf -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new TroupePayload(data);
            });

    @Override
    public Type<TroupePayload> type() {
        return TYPE;
    }
}
//?} else {
/*import dev.holoweb.troupe.net.Protocol;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

/^* The {@code troupe:v1} custom payload: raw protocol bytes, no Minecraft framing inside (see {@link Protocol}). *^/
public record TroupePayload(byte[] data) implements FabricPacket {
    public static final PacketType<TroupePayload> TYPE = PacketType.create(new Identifier(Protocol.CHANNEL), buf -> {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new TroupePayload(data);
    });

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBytes(data);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
*///?}
