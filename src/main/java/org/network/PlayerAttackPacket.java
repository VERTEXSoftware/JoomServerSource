package org.network;

public class PlayerAttackPacket {
    public int id;
    public int idEnemy;
    public int damage;
    public int type;
    public float aX;
    public float aY;
    public float aZ;

    public float pX;
    public float pY;
    public float pZ;

    public PlayerAttackPacket(int id, int idEnemy,int type, int damage,float aX,float aY,float aZ,float pX,float pY,float pZ) {
        this.id = id; this.idEnemy = idEnemy; this.damage = damage;
        this.type = type;
        this.aX = aX;
        this.aY = aY;
        this.aZ = aZ;
        this.pX=pX;
        this.pY=pY;
        this.pZ=pZ;
    }
}
