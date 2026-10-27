package org.network;

// IDs пакетов
public class PacketType {
    public static final int PLAYER_STATE = 1;
    public static final int PLAYER_ATTACK = 2;
    public static final int LOGIN = 3;
    public static final int SPAWN = 4;
    public static final int DESPAWN = 5;
    public static final int LOGIN_RESPONSE = 6;
    public static final int PLAYER_DEATH = 7;
    public static final int PLAYER_RESPAWN = 8;

    public static final int MAP_INFO = 9;
    public static final int CLIENT_READY = 10;
    public static final int MATCH_START = 11;
    public static final int MATCH_END = 12;
    public static final int PLAYER_STATS = 13;
}