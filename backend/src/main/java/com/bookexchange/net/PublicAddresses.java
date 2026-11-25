package com.bookexchange.net;

import java.net.InetAddress;

public final class PublicAddresses {

    private PublicAddresses() {
    }

    /** True for ordinary internet addresses; false for anything internal (loopback, private, link-local, etc.). */
    public static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0) {
                return false; // "this network"
            }
            return !(first == 100 && second >= 64 && second <= 127); // carrier-grade NAT
        }
        return (b[0] & 0xfe) != 0xfc; // IPv6 unique local (fc00::/7)
    }
}
