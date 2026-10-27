package org.network;

public class LoginResponsePacket {
    public int id;
    public int model;
    public String name;

    public LoginResponsePacket(int id,int model, String name) {
        this.id = id;
        this.name = name;
        this.model =  model;
    }
}