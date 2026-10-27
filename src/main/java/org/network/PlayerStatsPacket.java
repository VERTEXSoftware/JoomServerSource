package org.network;

public class PlayerStatsPacket {
    public int id;
    public String name;
    public int kills;
    public int deaths;

    public PlayerStatsPacket(int id, String name, int kills, int deaths) {
        this.id = id;
        this.name = name;
        this.kills = kills;
        this.deaths = deaths;
    }
}