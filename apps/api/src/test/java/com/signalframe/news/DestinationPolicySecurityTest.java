package com.signalframe.news;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.infrastructure.news.PublicDestinationPolicy;
import com.signalframe.news.domain.IngestionException;
import com.signalframe.news.domain.IngestionFailure;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SSRF boundary tests. Deterministic: literal addresses only, no DNS lookups to
 * the public internet.
 */
class DestinationPolicySecurityTest {

  private final PublicDestinationPolicy policy = new PublicDestinationPolicy();

  @Test
  void rejectsLoopbackPrivateLinkLocalAndMetadataDestinations() throws Exception {
    List<String> blocked = List.of(
      "http://127.0.0.1/",
      "http://127.1.2.3/",
      "http://localhost/private",
      "http://0.0.0.0/",
      "http://[::ffff:127.0.0.1]/",
      "http://10.1.2.3/article",
      "http://172.16.0.1/",
      "http://172.31.255.255/",
      "http://192.168.1.1/",
      "http://169.254.169.254/latest/meta-data/",
      "http://100.64.0.1/",
      "http://198.18.0.1/",
      "http://224.0.0.1/",
      "http://255.255.255.255/",
      "http://[::1]/",
      "http://[::]/",
      "http://[fc00::1]/",
      "http://[fe80::1]/",
      "http://[ff02::1]/"
    );
    for (String url : blocked) {
      URI uri = URI.create(url);
      IngestionException failure = assertThrows(
        IngestionException.class,
        () -> policy.verify(uri, resolve(uri)),
        url
      );
      assertEquals(IngestionFailure.UNSAFE_DESTINATION, failure.failure(), url);
    }
  }

  @Test
  void acceptsPublicDestinations() throws Exception {
    List<String> allowed = List.of(
      "http://8.8.8.8/",
      "https://1.1.1.1/",
      "http://93.184.216.34/article",
      "http://172.32.0.1/",
      "http://100.128.0.1/",
      "http://169.255.0.1/",
      "https://[2606:4700:4700::1111]/"
    );
    for (String url : allowed) {
      URI uri = URI.create(url);
      assertDoesNotThrow(() -> policy.verify(uri, resolve(uri)), url);
    }
  }

  @Test
  void rejectsIpv6AddressesThatHidePrivateIpv4() throws Exception {
    assertFalse(
      PublicDestinationPolicy.isPublicDestination(
        InetAddress.getByAddress(mapped(127, 0, 0, 1))
      ),
      "IPv4-mapped loopback"
    );
    assertFalse(
      PublicDestinationPolicy.isPublicDestination(
        InetAddress.getByAddress(mapped(10, 0, 0, 1))
      ),
      "IPv4-mapped private"
    );
    assertFalse(
      PublicDestinationPolicy.isPublicDestination(
        InetAddress.getByAddress(ipv4Compatible(127, 0, 0, 1))
      ),
      "IPv4-compatible loopback"
    );
    assertFalse(
      PublicDestinationPolicy.isPublicDestination(
        InetAddress.getByAddress(sixToFour(192, 168, 1, 1))
      ),
      "6to4 embedding a private address"
    );
    assertFalse(
      PublicDestinationPolicy.isPublicDestination(
        InetAddress.getByAddress(nat64(169, 254, 169, 254))
      ),
      "NAT64 embedding the metadata address"
    );
    assertTrue(
      PublicDestinationPolicy.isPublicDestination(
        InetAddress.getByAddress(mapped(8, 8, 8, 8))
      ),
      "IPv4-mapped public address"
    );
    assertTrue(
      PublicDestinationPolicy.isPublicDestination(
        InetAddress.getByAddress(sixToFour(8, 8, 8, 8))
      ),
      "6to4 embedding a public address"
    );
  }

  @Test
  void rejectsUnsupportedSchemesCredentialsAndNonDefaultPorts() {
    List<String> blocked = List.of(
      "file:///etc/passwd",
      "ftp://example.com/article",
      "gopher://example.com/",
      "data:text/html,hello",
      "http://user:pass@example.com/article",
      "https://user@example.com/article",
      "http://example.com:8080/article",
      "http://example.com:22/",
      "https://example.com:8443/"
    );
    for (String url : blocked) {
      URI uri = URI.create(url);
      IngestionException failure = assertThrows(
        IngestionException.class,
        () -> policy.verifyShape(uri),
        url
      );
      assertEquals(IngestionFailure.INVALID_URL, failure.failure(), url);
    }
  }

  @Test
  void rejectsHostnameThatResolvesToAPrivateAddress() throws Exception {
    URI uri = URI.create("http://internal.example.com/article");
    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> policy.verifyAddresses(uri, List.of(address("10.0.0.5")))
    );
    assertEquals(IngestionFailure.UNSAFE_DESTINATION, failure.failure());
  }

  @Test
  void rejectsMixedPublicAndPrivateResolution() throws Exception {
    URI uri = URI.create("http://mixed.example.com/article");
    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> policy.verifyAddresses(
        uri,
        List.of(address("93.184.216.34"), address("127.0.0.1"))
      )
    );
    assertEquals(IngestionFailure.UNSAFE_DESTINATION, failure.failure());
  }

  @Test
  void rejectsEmptyResolution() {
    URI uri = URI.create("http://empty.example.com/article");
    IngestionException failure = assertThrows(
      IngestionException.class,
      () -> policy.verifyAddresses(uri, List.of())
    );
    assertEquals(IngestionFailure.NETWORK_FAILURE, failure.failure());
  }

  private static List<InetAddress> resolve(URI uri) throws UnknownHostException {
    return List.of(InetAddress.getAllByName(PublicDestinationPolicy.hostName(uri)));
  }

  private static InetAddress address(String literal) throws UnknownHostException {
    return InetAddress.getByName(literal);
  }

  private static byte[] mapped(int a, int b, int c, int d) {
    byte[] bytes = new byte[16];
    bytes[10] = (byte) 0xff;
    bytes[11] = (byte) 0xff;
    return tail(bytes, a, b, c, d);
  }

  private static byte[] ipv4Compatible(int a, int b, int c, int d) {
    return tail(new byte[16], a, b, c, d);
  }

  /** RFC 3056 puts the embedded IPv4 address in bytes 2..5. */
  private static byte[] sixToFour(int a, int b, int c, int d) {
    byte[] bytes = new byte[16];
    bytes[0] = 0x20;
    bytes[1] = 0x02;
    bytes[2] = (byte) a;
    bytes[3] = (byte) b;
    bytes[4] = (byte) c;
    bytes[5] = (byte) d;
    return bytes;
  }

  private static byte[] nat64(int a, int b, int c, int d) {
    byte[] bytes = new byte[16];
    bytes[0] = 0x00;
    bytes[1] = 0x64;
    bytes[2] = (byte) 0xff;
    bytes[3] = (byte) 0x9b;
    return tail(bytes, a, b, c, d);
  }

  private static byte[] tail(byte[] bytes, int a, int b, int c, int d) {
    bytes[12] = (byte) a;
    bytes[13] = (byte) b;
    bytes[14] = (byte) c;
    bytes[15] = (byte) d;
    return bytes;
  }
}
