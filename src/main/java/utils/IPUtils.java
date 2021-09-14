package utils;

import scala.Int;

public  class IPUtils {
    public static Integer getPort(String address){
        String[] fields = address.split("@");
        String hostAndPort = fields[1];
        try{
            Integer port = Integer.parseInt(hostAndPort.split(":")[1]);
            return port;
        } catch (Exception e){
            new RuntimeException("Não foi possível obter a porta do serviço");
        }
        return -1;
    }

   public static String getIP(String address){
        String[] fields = address.split("@");
        String hostAndPort = fields[1];
        try{
            var ip = hostAndPort.split(":")[0];
            return ip;
        } catch (Exception e){
            new RuntimeException("Não foi possível obter o IP do serviço");
        }
        return null;
    }


}
