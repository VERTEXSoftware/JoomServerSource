package org.network;

public class PlayerRespawnPacket {
    public int id;
    public int model;
    public String name;

    public PlayerRespawnPacket(int id,int model, String name) {
        this.id = id;
        this.model = model;
        this.name = name;
    }
}