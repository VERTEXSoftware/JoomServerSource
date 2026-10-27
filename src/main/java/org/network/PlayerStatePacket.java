package org.network;

public class PlayerStatePacket {
    public int id;
    public float x, y, z, rotY;
    public int health;
    public int gunType;

    public PlayerStatePacket(int id, float x, float y, float z, float rotY, int health, int gunType) {
        this.id = id; this.x = x; this.y = y; this.z = z; this.rotY = rotY;
        this.health = health; this.gunType = gunType;
    }
}