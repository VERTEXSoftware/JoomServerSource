package org.network;

public class PlayerDeathPacket {
    public int id;
    public int killerId;

    public PlayerDeathPacket(int id, int killerId) {
        this.id = id;
        this.killerId = killerId;
    }
}