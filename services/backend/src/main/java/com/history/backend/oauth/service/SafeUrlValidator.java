package com.history.backend.oauth.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.function.Function;

import org.springframework.stereotype.Component;

// CIMD client_id URL(https)을 서버가 직접 GET하기 전에 거치는 SSRF 가드 — 클라이언트가 임의로
// 지정한 URL을 서버가 그대로 fetch하므로, 사설망·루프백·클라우드 메타데이터(169.254.169.254) 등
// 내부 자원을 두드리게 하는 우회를 막는다. 해석된 주소 "중 하나라도" 위험하면 전체를 거부하는
// 이유는, 여러 A 레코드 중 하나에만 사설 IP를 섞어 안전한 주소로 응답받은 뒤 실제로는 사설
// 주소로 연결하게 하는 우회를 차단하기 위함이다.
// 알려진 한계: 이 검증과 실제 연결 시점의 DNS 조회가 서로 다르므로, 그 사이 레코드가 바뀌면
// (DNS 리바인딩) 검증을 통과한 주소와 실제로 접속하는 주소가 달라질 수 있는 창이 있다.
@Component
public class SafeUrlValidator {

    private final Function<String, InetAddress[]> resolver;

    public SafeUrlValidator() {
        this(SafeUrlValidator::resolveAll);
    }

    SafeUrlValidator(Function<String, InetAddress[]> resolver) {
        this.resolver = resolver;
    }

    public boolean isSafe(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equals(uri.getScheme())) {
            return false;
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.apply(host);
        } catch (Exception e) {
            return false;
        }
        if (addresses == null || addresses.length == 0) {
            return false;
        }
        for (InetAddress address : addresses) {
            if (isUnsafeAddress(address)) {
                return false;
            }
        }
        return true;
    }

    private boolean isUnsafeAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        // IPv6 Unique Local Address(fc00::/7) — isSiteLocalAddress()는 IPv4 사설 대역만 판정한다.
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static InetAddress[] resolveAll(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
