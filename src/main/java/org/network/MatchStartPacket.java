package org.network;

public class MatchStartPacket {
    public int timeSeconds; // 600 для 10 минут

    public MatchStartPacket(int timeSeconds) {
        this.timeSeconds = timeSeconds;
    }
}