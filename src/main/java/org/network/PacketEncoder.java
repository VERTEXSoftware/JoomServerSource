package org.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;

public class PacketEncoder extends MessageToByteEncoder<Object> {
    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        if (msg instanceof PlayerStatePacket) {
            PlayerStatePacket p = (PlayerStatePacket) msg;
            out.writeShort(PacketType.PLAYER_STATE);
            out.writeInt(p.id);
            out.writeFloat(p.x); out.writeFloat(p.y); out.writeFloat(p.z); out.writeFloat(p.rotY);
            out.writeInt(p.health);
            out.writeInt(p.gunType);

        } else if (msg instanceof PlayerAttackPacket) {
            PlayerAttackPacket p = (PlayerAttackPacket) msg;
            out.writeShort(PacketType.PLAYER_ATTACK);
            out.writeInt(p.id); out.writeInt(p.idEnemy); out.writeInt(p.type); out.writeInt(p.damage);
            out.writeFloat(p.aX); out.writeFloat(p.aY); out.writeFloat(p.aZ);
            out.writeFloat(p.pX); out.writeFloat(p.pY); out.writeFloat(p.pZ);
        } else if (msg instanceof MapInfoPacket) {
            MapInfoPacket p = (MapInfoPacket) msg;
            out.writeShort(PacketType.MAP_INFO);
            writeString(out, p.mapName);
            writeString(out, p.urlDownload);
            // НОВОЕ: null-safe — если хеш неизвестен, пишем 0 (Long -> long без NPE)
            out.writeLong(p.hashcrc64 == null ? 0L : p.hashcrc64);
        } else if (msg instanceof ClientReadyPacket) {
            out.writeShort(PacketType.CLIENT_READY);
        } else if (msg instanceof MatchStartPacket) {
            MatchStartPacket p = (MatchStartPacket) msg;
            out.writeShort(PacketType.MATCH_START);
            out.writeInt(p.timeSeconds);
        } else if (msg instanceof MatchEndPacket) {
            out.writeShort(PacketType.MATCH_END);
        } else if (msg instanceof PlayerStatsPacket) {
            PlayerStatsPacket p = (PlayerStatsPacket) msg;
            out.writeShort(PacketType.PLAYER_STATS);
            out.writeInt(p.id);
            writeString(out, p.name);
            out.writeInt(p.kills);
            out.writeInt(p.deaths);
        } else if (msg instanceof LoginPacket) {
            LoginPacket p = (LoginPacket) msg;
            out.writeShort(PacketType.LOGIN);
            writeString(out, p.name);
            out.writeInt( p.model);
            out.writeLong( p.hash);
        } else if (msg instanceof LoginResponsePacket) {
            LoginResponsePacket p = (LoginResponsePacket) msg;
            out.writeShort(PacketType.LOGIN_RESPONSE);
            out.writeInt(p.id);
            out.writeInt(p.model);
            writeString(out, p.name);
        }else if (msg instanceof PlayerRespawnPacket) {
            PlayerRespawnPacket p = (PlayerRespawnPacket) msg;
            out.writeShort(PacketType.PLAYER_RESPAWN);
            out.writeInt(p.id);
            out.writeInt(p.model);
            writeString(out, p.name); // Пишем ник
        } else if (msg instanceof PlayerDeathPacket) { // НОВОЕ
                PlayerDeathPacket p = (PlayerDeathPacket) msg;
                out.writeShort(PacketType.PLAYER_DEATH);
                out.writeInt(p.id);
                out.writeInt(p.killerId);
        } else if (msg instanceof SpawnPacket) {
            SpawnPacket p = (SpawnPacket) msg;
            out.writeShort(PacketType.SPAWN);
            out.writeInt(p.id);
            out.writeInt(p.model);
            writeString(out, p.name);
        } else if (msg instanceof DespawnPacket) {
            DespawnPacket p = (DespawnPacket) msg;
            out.writeShort(PacketType.DESPAWN);
            out.writeInt(p.id);
        }
    }

    // Вспомогательный метод для отправки строк
    private void writeString(ByteBuf out, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.writeBytes(bytes);
    }
}