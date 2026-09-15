package com.ftpview.entity;

public class ConnectionConfig {
    public String host;
    public String user = "anonymous";
    public String password = "";
    public String protocol = "FTP";
    public String encoding = "UTF-8";
    public int port = 21;
    public boolean ignoreCertificate = true;
}
