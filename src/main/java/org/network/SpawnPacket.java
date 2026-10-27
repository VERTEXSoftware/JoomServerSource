package org.network;

public class SpawnPacket {
    public int id;
    public int model;
    public String name;
    public SpawnPacket(int id,int model, String name) {
        this.id = id;
        this.model = model;
        this.name = name;
    }
}