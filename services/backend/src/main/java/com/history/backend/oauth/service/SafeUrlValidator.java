package com.history.backend.oauth.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

// CIMD client_id URL(https)을 서버가 직접 GET하기 전에 거치는 SSRF 가드 — 클라이언트가 임의로
// 지정한 URL을 서버가 그대로 fetch하므로, 사설망·루프백·클라우드 메타데이터(169.254.169.254) 등
// 내부 자원을 두드리게 하는 우회를 막는다. 해석된 주소 "중 하나라도" 위험하면 전체를 거부하는
// 이유는, 여러 A 레코드 중 하나에만 사설 IP를 섞어 안전한 주소로 응답받은 뒤 실제로는 사설
// 주소로 연결하게 하는 우회를 차단하기 위함이다.
// JDK 판정이 놓치는 예약 대역은 RESERVED 목록으로 거절한다.
// 알려진 한계: 이 검증과 실제 연결 시점의 DNS 조회가 서로 다르므로, 그 사이 레코드가 바뀌면
// (DNS 리바인딩) 검증을 통과한 주소와 실제로 접속하는 주소가 달라질 수 있는 창이 있다.
@Component
public class SafeUrlValidator {

    // 네트워크 바이트와 접두사 길이. 주소 패밀리(4바이트/16바이트)가 다르면 매치하지 않는다.
    private record Cidr(byte[] network, int prefixBits) {
        static Cidr of(String literal, int prefixBits) {
            try {
                // 리터럴이라 DNS 조회가 일어나지 않는다.
                return new Cidr(InetAddress.getByName(literal).getAddress(), prefixBits);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainder = prefixBits % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = (0xff << (8 - remainder)) & 0xff;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private static final List<Cidr> RESERVED = List.of(
            // "this network" — 리눅스는 이 대역으로의 연결을 로컬 호스트로 라우팅한다(isAnyLocalAddress는 0.0.0.0 하나만 잡는다)
            Cidr.of("0.0.0.0", 8),
            // CGNAT(통신사 NAT 공유 대역, 클라우드 내부망·VPN도 쓴다)
            Cidr.of("100.64.0.0", 10),
            // IETF 프로토콜 할당
            Cidr.of("192.0.0.0", 24),
            // 벤치마크용
            Cidr.of("198.18.0.0", 15),
            // 예약(브로드캐스트 255.255.255.255 포함)
            Cidr.of("240.0.0.0", 4),
            // IPv6 Unique Local Address — isSiteLocalAddress()는 IPv4 사설 대역만 판정한다
            Cidr.of("fc00::", 7),
            // IPv4-compatible IPv6(::a.b.c.d, 폐기됨). JDK는 ::ffff:a.b.c.d(IPv4-mapped)만 Inet4Address로 접어 주고
            // 이 표기는 Inet6Address로 남겨 loopback·사설 판정이 전부 비켜 간다
            Cidr.of("::", 96),
            // NAT64 잘 알려진 접두사(IPv4 주소를 품는다)
            Cidr.of("64:ff9b::", 96),
            // 로컬용 NAT64
            Cidr.of("64:ff9b:1::", 48),
            // 6to4(IPv4 주소를 품는다)
            Cidr.of("2002::", 16),
            // Teredo(IPv4 주소를 품는다)
            Cidr.of("2001::", 32));

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
        byte[] bytes = address.getAddress();
        return RESERVED.stream().anyMatch(cidr -> cidr.contains(bytes));
    }

    private static InetAddress[] resolveAll(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
