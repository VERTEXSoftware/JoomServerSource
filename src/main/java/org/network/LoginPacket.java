package org.network;

public class LoginPacket {
    public String name;
    public int model;
    public long hash;
    public LoginPacket(String name, int model, long hash) {
        this.name = name;
        this.model=model;
        this.hash = hash;
    }
}