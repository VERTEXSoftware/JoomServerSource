package org.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PacketDecoder extends MessageToMessageDecoder<ByteBuf> {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int packetId = in.readShort();


        switch (packetId) {
            case PacketType.PLAYER_STATE:
                out.add(new PlayerStatePacket(
                        in.readInt(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readInt(), in.readInt()
                ));
                break;
            case PacketType.PLAYER_ATTACK:
                out.add(new PlayerAttackPacket(in.readInt(), in.readInt(), in.readInt(), in.readInt(),in.readFloat(), in.readFloat(), in.readFloat(),in.readFloat(), in.readFloat(), in.readFloat()));
                break;
            case PacketType.LOGIN_RESPONSE:
                out.add(new LoginResponsePacket(in.readInt(),in.readInt(), readString(in)));
                break;
            case PacketType.MAP_INFO:
                out.add(new MapInfoPacket(readString(in), readString(in),in.readLong()));
                break;
            case PacketType.CLIENT_READY:
                out.add(new ClientReadyPacket());
                break;
            case PacketType.MATCH_START:
                out.add(new MatchStartPacket(in.readInt()));
                break;
            case PacketType.MATCH_END:
                out.add(new MatchEndPacket());
                break;
            case PacketType.PLAYER_STATS:
                out.add(new PlayerStatsPacket(in.readInt(), readString(in), in.readInt(), in.readInt()));
                break;
            case PacketType.LOGIN:
                out.add(new LoginPacket(readString(in),in.readInt(),in.readLong()));
                break;
            case PacketType.PLAYER_RESPAWN:
                out.add(new PlayerRespawnPacket(in.readInt(),in.readInt(), readString(in)));
                break;
            case PacketType.PLAYER_DEATH:
                out.add(new PlayerDeathPacket(in.readInt(), in.readInt()));
                break;
            case PacketType.SPAWN:
                out.add(new SpawnPacket(in.readInt(),in.readInt() , readString(in)));
                break;
            case PacketType.DESPAWN:
                out.add(new DespawnPacket(in.readInt()));
                break;
            default:
                System.err.println("Неизвестный пакет: " + packetId);
                break;
        }
    }

    // Вспомогательный метод для чтения строк
    private String readString(ByteBuf in) {
        short length = in.readShort();
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}