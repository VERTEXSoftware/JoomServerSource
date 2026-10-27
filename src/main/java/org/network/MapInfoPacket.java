package org.network;

public class MapInfoPacket {
    public String mapName;
    public String urlDownload;
    public Long hashcrc64;

    public MapInfoPacket(String mapName, String urlDownload, Long hashcrc64) {
        this.mapName = mapName;
        this.urlDownload = urlDownload;
        this.hashcrc64 = hashcrc64;
    }
}