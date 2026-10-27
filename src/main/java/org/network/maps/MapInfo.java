package org.network.maps;

public class MapInfo {
    public String mapName;
    public String urlDownload;
    public Long hashcrc64;

    public MapInfo(String _name,String _url, Long hashcrc64){
        this.mapName=_name;
        this.urlDownload=_url;
        this.hashcrc64 = hashcrc64;
    }

    public String getMapName() {
        return mapName;
    }

    public String getUrlDownload() {
        return urlDownload;
    }

    public Long getHashcrc64() {
        return hashcrc64;
    }
}
