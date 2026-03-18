package com.valley.ShareIt.utils;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * @author dale
 * @since 2024/12/7
 **/
public class NetWorkUtils {

    public static String getLocalIpAddress() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();

            // 忽略回环接口和未激活的接口
            if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                continue;
            }

            String name = networkInterface.getName();
            // 跳过虚拟接口
            if (name.startsWith("utun")
                    || name.startsWith("awdl")
                    || name.startsWith("llw")
                    || name.startsWith("bridge")
            ) {
                continue;
            }

            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();

                // 过滤 IPv4 地址
                if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return null; // 没有找到合适的地址
    }

}
