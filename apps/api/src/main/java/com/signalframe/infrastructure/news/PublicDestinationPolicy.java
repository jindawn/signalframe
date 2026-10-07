package com.signalframe.infrastructure.news;

import com.signalframe.news.domain.DestinationPolicy;
import com.signalframe.news.domain.IngestionException;
import com.signalframe.news.domain.IngestionFailure;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Default SSRF boundary: refuses URL shapes and destinations that a local
 * research workspace must never call.
 *
 * <p>Security rationale
 *
 * <ul>
 *   <li>Only {@code http}/{@code https} are fetchable; {@code file:},
 *       {@code ftp:}, {@code data:} … are rejected before any I/O.
 *   <li>URLs carrying {@code user:password@} userinfo are rejected so
 *       credentials are never stored or transmitted.
 *   <li>Only the default ports are allowed, which blocks internal services
 *       that listen on high ports of otherwise public hosts.
 *   <li>The decision is made on <em>resolved addresses</em>, not on hostname
 *       strings: loopback, private, link-local (including the cloud metadata
 *       endpoint 169.254.169.254), CGNAT, multicast, reserved, IPv6 ULA and
 *       link-local ranges, plus IPv4-mapped/-compatible and NAT64/6to4
 *       addresses that hide a private IPv4 address, are all refused.
 *   <li>Every resolved address must be public: a hostname that resolves to a
 *       mix of public and private addresses is refused.
 * </ul>
 *
 * <p>This class performs no I/O; resolution is the caller's job and the
 * validated address is the one that gets used for the connection.
 */
@Component
public class PublicDestinationPolicy implements DestinationPolicy {

  private static final String IPV4_LITERAL = "\\d{1,3}(\\.\\d{1,3}){3}";

  @Override
  public void verifyShape(URI uri) {
    validateSchemeAndAuthority(uri);
    validatePort(uri);
  }

  @Override
  public void verifyAddresses(URI uri, List<InetAddress> resolved) {
    if (resolved == null || resolved.isEmpty()) throw new IngestionException(
      IngestionFailure.NETWORK_FAILURE
    );
    for (InetAddress address : resolved) if (
      !isPublicDestination(address)
    ) throw new IngestionException(IngestionFailure.UNSAFE_DESTINATION);
  }

  /**
   * Scheme, credentials, host and header-safety checks. Split out so test
   * fixtures can permit a loopback host on an ephemeral port while still
   * enforcing the scheme and credential rules.
   */
  public static void validateSchemeAndAuthority(URI uri) {
    if (uri == null) throw invalid();
    String scheme = uri.getScheme();
    if (
      scheme == null ||
      !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
    ) throw invalid();
    if (uri.getUserInfo() != null) throw invalid();
    String host = hostName(uri);
    if (host == null) throw invalid();
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      // ASCII only: keeps raw headers (Host:, SNI) injection-free and
      // requires IDN hosts to arrive in punycode form.
      if (c <= 0x20 || c >= 0x7f) throw invalid();
    }
  }

  /** Only http/https default ports: internal services usually listen elsewhere. */
  public static void validatePort(URI uri) {
    int port = uri.getPort();
    if (port != -1 && port != 80 && port != 443) throw invalid();
  }

  /** Host without IPv6 brackets, or null when the URL has no host. */
  public static String hostName(URI uri) {
    if (uri == null) return null;
    String host = uri.getHost();
    if (host == null) return null;
    if (host.length() > 1 && host.startsWith("[") && host.endsWith("]")) host =
      host.substring(1, host.length() - 1);
    return host.isBlank() ? null : host;
  }

  /** Lower-case host used for provenance and logging; null when unusable. */
  public static String safeHost(String url) {
    try {
      String host = hostName(URI.create(url));
      return host == null ? null : host.toLowerCase(Locale.ROOT);
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static boolean isIpLiteral(String host) {
    if (host == null) return false;
    if (host.indexOf(':') >= 0) return true;
    return host.matches(IPV4_LITERAL);
  }

  /** True when the address may be contacted by this workspace. */
  public static boolean isPublicDestination(InetAddress address) {
    if (address == null) return false;
    if (
      address.isAnyLocalAddress() ||
      address.isLoopbackAddress() ||
      address.isLinkLocalAddress() ||
      address.isSiteLocalAddress() ||
      address.isMulticastAddress()
    ) return false;
    byte[] raw = address.getAddress();
    if (raw.length == 4) return isPublicIpv4(raw);
    if (raw.length == 16) {
      byte[] embedded = embeddedIpv4(raw);
      if (embedded != null) return isPublicIpv4(embedded);
      return isPublicIpv6(raw);
    }
    return false;
  }

  private static boolean isPublicIpv4(byte[] b) {
    int a0 = b[0] & 0xff,
      a1 = b[1] & 0xff,
      a2 = b[2] & 0xff;
    if (a0 == 0 || a0 == 10 || a0 == 127) return false; // this-network, private, loopback
    if (a0 == 100 && a1 >= 64 && a1 <= 127) return false; // 100.64/10 CGNAT
    if (a0 == 169 && a1 == 254) return false; // 169.254/16 link-local + metadata services
    if (a0 == 172 && a1 >= 16 && a1 <= 31) return false; // 172.16/12 private
    if (a0 == 192 && a1 == 168) return false; // 192.168/16 private
    if (a0 == 192 && a1 == 0 && a2 == 0) return false; // 192.0.0/24 IETF assignments
    if (a0 == 192 && a1 == 0 && a2 == 2) return false; // TEST-NET-1
    if (a0 == 192 && a1 == 88 && a2 == 99) return false; // 6to4 relay anycast
    if (a0 == 198 && (a1 == 18 || a1 == 19)) return false; // benchmarking
    if (a0 == 198 && a1 == 51 && a2 == 100) return false; // TEST-NET-2
    if (a0 == 203 && a1 == 0 && a2 == 113) return false; // TEST-NET-3
    if (a0 >= 224) return false; // multicast, reserved, broadcast
    return true;
  }

  private static boolean isPublicIpv6(byte[] b) {
    int first = b[0] & 0xff,
      second = b[1] & 0xff;
    if ((first & 0xfe) == 0xfc) return false; // fc00::/7 unique local
    if (first == 0xfe && (second & 0xc0) == 0x80) return false; // fe80::/10 link-local
    if (first == 0xff) return false; // multicast
    if (
      first == 0x20 &&
      second == 0x01 &&
      (b[2] & 0xff) == 0x0d &&
      (b[3] & 0xff) == 0xb8
    ) return false; // 2001:db8::/32 documentation
    if (
      first == 0x20 && second == 0x01 && (b[2] & 0xff) == 0 && (b[3] & 0xff) == 0
    ) return false; // 2001::/32 Teredo embeds an IPv4 address
    if (first == 0x20 && second == 0x02) return isPublicIpv4( // 2002::/16 6to4
      new byte[] { b[2], b[3], b[4], b[5] }
    );
    // NAT64 well-known prefix 64:ff9b::/96 embeds an IPv4 address.
    if (
      first == 0x00 &&
      second == 0x64 &&
      (b[2] & 0xff) == 0xff &&
      (b[3] & 0xff) == 0x9b
    ) return isPublicIpv4(new byte[] { b[12], b[13], b[14], b[15] });
    return true;
  }

  /** IPv4 address hidden inside an IPv6 address, or null when there is none. */
  private static byte[] embeddedIpv4(byte[] b) {
    boolean mapped = true;
    for (int i = 0; i < 10 && mapped; i++) mapped = b[i] == 0;
    if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) return new byte[] {
      b[12],
      b[13],
      b[14],
      b[15],
    };
    boolean compatible = true;
    for (int i = 0; i < 12 && compatible; i++) compatible = b[i] == 0;
    if (compatible) return new byte[] { b[12], b[13], b[14], b[15] };
    return null;
  }

  private static IngestionException invalid() {
    return new IngestionException(IngestionFailure.INVALID_URL);
  }
}
