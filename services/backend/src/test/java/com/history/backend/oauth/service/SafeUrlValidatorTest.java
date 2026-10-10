package com.history.backend.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SafeUrlValidator: CIMD client_id URL의 SSRF 가드")
class SafeUrlValidatorTest {

    @Test
    @DisplayName("https + 공인 IP로 해석 → true")
    void httpsWithPublicAddressIsSafe() {
        SafeUrlValidator validator = validatorResolving("93.184.216.34");

        assertThat(validator.isSafe("https://example.com/doc")).isTrue();
    }

    @Test
    @DisplayName("http 스킴은 거부")
    void rejectsHttpScheme() {
        SafeUrlValidator validator = validatorResolving("93.184.216.34");

        assertThat(validator.isSafe("http://example.com/doc")).isFalse();
    }

    @Test
    @DisplayName("userinfo가 있으면 거부")
    void rejectsUrlWithUserInfo() {
        SafeUrlValidator validator = validatorResolving("93.184.216.34");

        assertThat(validator.isSafe("https://user@example.com/doc")).isFalse();
    }

    @Test
    @DisplayName("프래그먼트가 있으면 거부")
    void rejectsUrlWithFragment() {
        SafeUrlValidator validator = validatorResolving("93.184.216.34");

        assertThat(validator.isSafe("https://example.com/doc#frag")).isFalse();
    }

    @Test
    @DisplayName("호스트가 없으면 거부")
    void rejectsUrlWithoutHost() {
        SafeUrlValidator validator = validatorResolving("93.184.216.34");

        assertThat(validator.isSafe("https:///doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 loopback(127.0.0.1)이면 거부")
    void rejectsLoopbackAddress() {
        assertThat(validatorResolving("127.0.0.1").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 site-local(10.x)이면 거부")
    void rejectsSiteLocalClassA() {
        assertThat(validatorResolving("10.1.2.3").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 site-local(172.16.x)이면 거부")
    void rejectsSiteLocalClassB() {
        assertThat(validatorResolving("172.16.0.1").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 site-local(192.168.x)이면 거부")
    void rejectsSiteLocalClassC() {
        assertThat(validatorResolving("192.168.1.1").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 link-local(169.254.x, 클라우드 메타데이터)이면 거부")
    void rejectsLinkLocalAddress() {
        assertThat(validatorResolving("169.254.169.254").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 any-local(0.0.0.0)이면 거부")
    void rejectsAnyLocalAddress() {
        assertThat(validatorResolving("0.0.0.0").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 multicast(224.0.0.1)이면 거부")
    void rejectsMulticastAddress() {
        assertThat(validatorResolving("224.0.0.1").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 IPv6 loopback(::1)이면 거부")
    void rejectsIpv6Loopback() {
        assertThat(validatorResolving("::1").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 IPv6 link-local(fe80::1)이면 거부")
    void rejectsIpv6LinkLocal() {
        assertThat(validatorResolving("fe80::1").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("해석된 주소가 IPv6 ULA(fd00::1, fc00::/7)이면 거부")
    void rejectsIpv6UniqueLocal() {
        assertThat(validatorResolving("fd00::1").isSafe("https://internal.example/doc")).isFalse();
    }

    @ParameterizedTest
    @DisplayName("JDK가 사설로 보지 않는 예약 대역(0/8·CGNAT·192.0.0/24·벤치마크·240/4·NAT64·6to4·Teredo)으로 해석되면 거부")
    @ValueSource(strings = {
            "0.1.2.3",
            "100.64.0.1", "100.127.255.255",
            "192.0.0.8",
            "198.18.0.1", "198.19.255.255",
            "240.0.0.1", "255.255.255.255",
            "64:ff9b::a00:1",
            "64:ff9b:1::1",
            "2002:a00:1::1",
            "2001:0:a00:1::1"
    })
    void rejectsReservedRanges(String literalIp) {
        assertThat(validatorResolving(literalIp).isSafe("https://internal.example/doc")).isFalse();
    }

    @ParameterizedTest
    @DisplayName("예약 대역 경계 바로 밖의 공인 주소는 통과")
    @ValueSource(strings = {
            "100.63.255.255", "100.128.0.0",
            "198.17.255.255", "198.20.0.1",
            "64:ff9c::1",
            "2001:4860::1"
    })
    void allowsAddressesJustOutsideReservedRanges(String literalIp) {
        assertThat(validatorResolving(literalIp).isSafe("https://internal.example/doc")).isTrue();
    }

    @Test
    @DisplayName("공인 주소와 사설 주소가 섞이면 거부(하나라도 걸리면 전체 거부)")
    void rejectsWhenAnyResolvedAddressIsPrivate() {
        assertThat(validatorResolving("93.184.216.34", "10.0.0.1").isSafe("https://internal.example/doc")).isFalse();
    }

    @Test
    @DisplayName("resolver가 예외를 던지면 거부")
    void rejectsWhenResolverThrows() {
        SafeUrlValidator validator = new SafeUrlValidator(host -> {
            throw new IllegalStateException(new UnknownHostException("no such host: " + host));
        });

        assertThat(validator.isSafe("https://nowhere.example/doc")).isFalse();
    }

    @Test
    @DisplayName("resolver가 null을 반환하면 거부")
    void rejectsWhenResolverReturnsNull() {
        SafeUrlValidator validator = new SafeUrlValidator(host -> null);

        assertThat(validator.isSafe("https://nowhere.example/doc")).isFalse();
    }

    @Test
    @DisplayName("resolver가 빈 배열을 반환하면 거부")
    void rejectsWhenResolverReturnsEmptyArray() {
        SafeUrlValidator validator = new SafeUrlValidator(host -> new InetAddress[0]);

        assertThat(validator.isSafe("https://nowhere.example/doc")).isFalse();
    }

    // ── 헬퍼 — 실제 DNS 없이, 주어진 IP 리터럴들을 그대로 해석 결과로 돌려주는 resolver ──

    private SafeUrlValidator validatorResolving(String... literalIps) {
        Function<String, InetAddress[]> resolver = host -> {
            try {
                InetAddress[] addresses = new InetAddress[literalIps.length];
                for (int i = 0; i < literalIps.length; i++) {
                    addresses[i] = InetAddress.getByName(literalIps[i]);
                }
                return addresses;
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        };
        return new SafeUrlValidator(resolver);
    }
}
