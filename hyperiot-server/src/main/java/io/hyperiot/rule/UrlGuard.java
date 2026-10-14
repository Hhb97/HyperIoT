package io.hyperiot.rule;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Keeps webhook actions from being used to reach internal services (SSRF): only http(s), and unless explicitly
 * allowed no loopback, private, link-local or multicast destinations.
 */
public final class UrlGuard {

    private UrlGuard() {
    }

    /** @return null if the URL is acceptable, otherwise the reason it is not */
    public static String check(String url, boolean allowPrivate) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "URL 格式不正确";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "只支持 http / https";
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            return "URL 必须包含主机名且不能带用户信息";
        }
        if (allowPrivate) {
            return null;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                        || a.isMulticastAddress() || isUniqueLocalV6(a)) {
                    return "不允许访问内网地址（可通过 hyperiot.webhook.allow-private-addresses 放开）";
                }
            }
        } catch (UnknownHostException e) {
            return "无法解析主机名";
        }
        return null;
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }
}
