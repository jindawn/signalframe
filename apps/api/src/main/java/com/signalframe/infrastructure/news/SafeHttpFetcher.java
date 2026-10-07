package com.signalframe.infrastructure.news;

import com.signalframe.news.domain.DestinationPolicy;
import com.signalframe.news.domain.HostResolver;
import com.signalframe.news.domain.IngestionException;
import com.signalframe.news.domain.IngestionFailure;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded, SSRF-aware HTTP/1.1 GET used by URL ingestion.
 *
 * <p>Security boundary (see {@link PublicDestinationPolicy}):
 *
 * <ul>
 *   <li>The hostname is resolved <em>once</em> per hop, every returned address
 *       is validated, and the connection is opened against that pinned
 *       {@link InetAddress}. The socket layer therefore cannot re-resolve the
 *       name after validation, which is what defeats DNS rebinding.
 *   <li>Redirects are followed manually with a hard limit; every hop re-runs
 *       shape validation, resolution and address validation. A redirect to a
 *       private address, or to a non-http(s) scheme, is refused.
 *   <li>Only the default ports are used and only already-validated schemes are
 *       requested, so this client never speaks to another protocol.
 *   <li>Timeouts (connect, read, total), response size, redirect count, header
 *       size, User-Agent and accepted content types are all bounded and
 *       centrally configured in {@link NewsFetchProperties}.
 * </ul>
 *
 * <p>TLS uses {@code HTTPS} endpoint identification against the original
 * hostname, so connecting to a pinned IP still verifies the certificate. No
 * cookies, credentials, request bodies or proxies are used.
 */
@Component
public class SafeHttpFetcher {

  private static final int MAX_HEADER_BYTES = 16 * 1024;
  private static final int READ_BUFFER_BYTES = 8 * 1024;
  private static final String ACCEPT =
    "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1";

  private final HostResolver resolver;
  private final DestinationPolicy policy;
  private final NewsFetchProperties properties;

  public SafeHttpFetcher(
    HostResolver resolver,
    DestinationPolicy policy,
    NewsFetchProperties properties
  ) {
    this.resolver = resolver;
    this.policy = policy;
    this.properties = properties;
  }

  public FetchedPage fetch(String rawUrl) {
    URI target = parse(rawUrl);
    long deadline = System.nanoTime() + properties.totalTimeout().toNanos();
    int redirects = 0;
    while (true) {
      boolean redirected = redirects > 0;
      try {
        policy.verifyShape(target);
      } catch (IngestionException e) {
        throw redirectAware(e, redirected);
      }
      // Resolve once, validate every address, then connect to the pinned one.
      List<InetAddress> resolved = resolve(target);
      try {
        policy.verifyAddresses(target, resolved);
      } catch (IngestionException e) {
        throw redirectAware(e, redirected);
      }
      Socket socket = connect(target, resolved.get(0), deadline);
      try {
        page:
        {
          OutputStream out = socket.getOutputStream();
          writeRequest(out, target);
          out.flush();
          InputStream in = new BufferedInputStream(
            socket.getInputStream(),
            READ_BUFFER_BYTES
          );
          Head head = readHead(in);
          if (head.isRedirect()) {
            String location = head.header("location");
            if (location != null && !location.isBlank()) {
              if (redirects >= properties.maxRedirects()) throw new IngestionException(
                IngestionFailure.TOO_MANY_REDIRECTS
              );
              redirects++;
              target = redirectTarget(target, location);
              break page;
            }
          }
          if (head.isAccessBlocked()) throw new IngestionException(
            IngestionFailure.ACCESS_BLOCKED
          );
          if (head.status() != 200) throw new IngestionException(
            IngestionFailure.HTTP_ERROR
          );
          String contentType = normalizeContentType(head.header("content-type"));
          if (!supported(contentType)) throw new IngestionException(
            IngestionFailure.UNSUPPORTED_CONTENT_TYPE
          );
          String encoding = head.header("content-encoding");
          if (
            declaredLength(head.header("content-length")) >
              properties.maxResponseBytes() && isIdentity(encoding)
          ) throw new IngestionException(IngestionFailure.RESPONSE_TOO_LARGE);
          byte[] body = readBody(in, encoding, deadline);
          return new FetchedPage(
            target,
            head.status(),
            contentType,
            charsetOf(head.header("content-type")),
            body,
            redirects
          );
        }
      } catch (IngestionException e) {
        throw e;
      } catch (SocketTimeoutException e) {
        throw new IngestionException(IngestionFailure.TIMEOUT, e);
      } catch (IOException e) {
        throw new IngestionException(IngestionFailure.NETWORK_FAILURE, e);
      } finally {
        close(socket);
      }
    }
  }

  private URI parse(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank()) throw new IngestionException(
      IngestionFailure.INVALID_URL
    );
    String value = rawUrl.trim();
    int fragment = value.indexOf('#');
    if (fragment >= 0) value = value.substring(0, fragment); // never sent on the wire
    try {
      URI uri = URI.create(value);
      policy.verifyShape(uri);
      return uri;
    } catch (IngestionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IngestionException(IngestionFailure.INVALID_URL, e);
    }
  }

  private List<InetAddress> resolve(URI uri) {
    String host = PublicDestinationPolicy.hostName(uri);
    if (host == null) throw new IngestionException(IngestionFailure.INVALID_URL);
    try {
      List<InetAddress> addresses = resolver.resolve(host);
      if (addresses == null || addresses.isEmpty()) throw new IngestionException(
        IngestionFailure.NETWORK_FAILURE
      );
      return addresses;
    } catch (UnknownHostException e) {
      throw new IngestionException(IngestionFailure.NETWORK_FAILURE, e);
    }
  }

  private Socket connect(URI uri, InetAddress pinned, long deadlineNanos) {
    int port = portOf(uri);
    int connectMs = (int) Math.max(
      1,
      Math.min(
        properties.connectTimeout().toMillis(),
        remainingMillis(deadlineNanos)
      )
    );
    int readMs = (int) Math.max(
      1,
      Math.min(
        properties.readTimeout().toMillis(),
        remainingMillis(deadlineNanos)
      )
    );
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(pinned, port), connectMs);
      socket.setSoTimeout(readMs);
      if ("https".equalsIgnoreCase(uri.getScheme())) return tls(socket, uri, port);
      return socket;
    } catch (SocketTimeoutException e) {
      close(socket);
      throw new IngestionException(IngestionFailure.TIMEOUT, e);
    } catch (IOException e) {
      close(socket);
      throw new IngestionException(IngestionFailure.NETWORK_FAILURE, e);
    }
  }

  /**
   * TLS over the pinned socket. SNI and certificate checks use the original
   * hostname, not the IP that was actually contacted.
   */
  private Socket tls(Socket plain, URI uri, int port) throws IOException {
    String host = PublicDestinationPolicy.hostName(uri);
    SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
    SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
    SSLParameters parameters = ssl.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    if (!PublicDestinationPolicy.isIpLiteral(host)) {
      try {
        parameters.setServerNames(List.of(new SNIHostName(host)));
      } catch (IllegalArgumentException ignored) {
        // SNI is optional; certificate verification still uses `host`.
      }
    }
    ssl.setSSLParameters(parameters);
    ssl.startHandshake();
    return ssl;
  }

  private void writeRequest(OutputStream out, URI uri) throws IOException {
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
      ? "/"
      : uri.getRawPath();
    if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();
    if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) throw new IngestionException(
      IngestionFailure.INVALID_URL
    );
    StringBuilder request = new StringBuilder()
      .append("GET ")
      .append(path)
      .append(" HTTP/1.1\r\n")
      .append("Host: ")
      .append(hostHeader(uri))
      .append("\r\n")
      .append("User-Agent: ")
      .append(properties.userAgent())
      .append("\r\n")
      .append("Accept: ")
      .append(ACCEPT)
      .append("\r\n")
      .append("Accept-Encoding: identity\r\n")
      .append("Connection: close\r\n\r\n");
    out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
  }

  private static String hostHeader(URI uri) {
    String host = PublicDestinationPolicy.hostName(uri);
    String header = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    int port = uri.getPort();
    int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    return port == -1 || port == defaultPort ? header : header + ":" + port;
  }

  private static Head readHead(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
    int p1 = -1, p2 = -1, p3 = -1, p4 = -1;
    while (buffer.size() <= MAX_HEADER_BYTES) {
      int next = in.read();
      if (next < 0) break;
      buffer.write(next);
      p1 = p2;
      p2 = p3;
      p3 = p4;
      p4 = next;
      if (
        (p1 == '\r' && p2 == '\n' && p3 == '\r' && p4 == '\n') ||
        (p1 == '\n' && p2 == '\n')
      ) break;
    }
    if (buffer.size() > MAX_HEADER_BYTES) throw new IngestionException(
      IngestionFailure.HTTP_ERROR
    );
    String block = buffer.toString(StandardCharsets.ISO_8859_1);
    if (block.isBlank()) throw new IngestionException(IngestionFailure.HTTP_ERROR);
    String[] lines = block.split("\r?\n");
    String[] status = lines[0].trim().split("\\s+");
    if (status.length < 2 || !status[0].startsWith("HTTP/")) throw new IngestionException(
      IngestionFailure.HTTP_ERROR
    );
    int code;
    try {
      code = Integer.parseInt(status[1]);
    } catch (NumberFormatException e) {
      throw new IngestionException(IngestionFailure.HTTP_ERROR, e);
    }
    Map<String, String> headers = new HashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon <= 0) continue;
      String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = lines[i].substring(colon + 1).trim();
      headers.merge(name, value, (first, second) -> first + ", " + second);
    }
    return new Head(code, headers);
  }

  private byte[] readBody(InputStream in, String encoding, long deadlineNanos)
    throws IOException {
    InputStream body = decode(in, encoding);
    int max = properties.maxResponseBytes();
    ByteArrayOutputStream out = new ByteArrayOutputStream(
      Math.min(max, 64 * 1024)
    );
    byte[] buffer = new byte[READ_BUFFER_BYTES];
    int total = 0;
    while (true) {
      if (System.nanoTime() > deadlineNanos) throw new IngestionException(
        IngestionFailure.TIMEOUT
      );
      int read = body.read(buffer);
      if (read < 0) break;
      total += read;
      if (total > max) throw new IngestionException(
        IngestionFailure.RESPONSE_TOO_LARGE
      );
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static InputStream decode(InputStream in, String encoding)
    throws IOException {
    if (encoding == null || encoding.isBlank()) return in;
    String value = encoding.trim().toLowerCase(Locale.ROOT);
    if (value.contains("identity")) return in;
    if (value.contains("gzip")) return new GZIPInputStream(in);
    if (value.contains("deflate")) return new InflaterInputStream(in);
    throw new IngestionException(IngestionFailure.UNSUPPORTED_CONTENT_TYPE);
  }

  private static URI redirectTarget(URI current, String location) {
    String value = location.trim();
    int fragment = value.indexOf('#');
    if (fragment >= 0) value = value.substring(0, fragment);
    try {
      return current.resolve(value);
    } catch (RuntimeException e) {
      throw new IngestionException(IngestionFailure.HTTP_ERROR, e);
    }
  }

  private static IngestionException redirectAware(
    IngestionException failure,
    boolean redirected
  ) {
    if (
      redirected &&
      (failure.failure() == IngestionFailure.UNSAFE_DESTINATION ||
        failure.failure() == IngestionFailure.INVALID_URL)
    ) return new IngestionException(
      IngestionFailure.REDIRECT_TO_UNSAFE_DESTINATION,
      failure
    );
    return failure;
  }

  private boolean supported(String contentType) {
    if (contentType == null || contentType.isBlank()) return false;
    for (String allowed : properties.allowedContentTypes()) if (
      allowed != null && allowed.trim().equalsIgnoreCase(contentType)
    ) return true;
    return false;
  }

  private static String normalizeContentType(String raw) {
    if (raw == null) return null;
    int semicolon = raw.indexOf(';');
    String value = (semicolon >= 0 ? raw.substring(0, semicolon) : raw)
      .trim()
      .toLowerCase(Locale.ROOT);
    return value.isBlank() ? null : value;
  }

  private static String charsetOf(String raw) {
    if (raw == null) return null;
    for (String part : raw.split(";")) {
      String value = part.trim();
      if (!value.toLowerCase(Locale.ROOT).startsWith("charset=")) continue;
      String charset = value.substring("charset=".length()).trim();
      if (
        charset.length() > 1 &&
        charset.startsWith("\"") &&
        charset.endsWith("\"")
      ) charset = charset.substring(1, charset.length() - 1);
      try {
        return Charset.isSupported(charset) ? charset : null;
      } catch (RuntimeException e) {
        return null;
      }
    }
    return null;
  }

  private static long declaredLength(String raw) {
    if (raw == null) return -1;
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static boolean isIdentity(String encoding) {
    return encoding == null || encoding.isBlank() || encoding
      .trim()
      .equalsIgnoreCase("identity");
  }

  private static int portOf(URI uri) {
    if (uri.getPort() != -1) return uri.getPort();
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static long remainingMillis(long deadlineNanos) {
    long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
    if (remaining <= 0) throw new IngestionException(IngestionFailure.TIMEOUT);
    return remaining;
  }

  private static void close(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // closing a failed socket must not mask the original failure
    }
  }

  private record Head(int status, Map<String, String> headers) {
    String header(String name) {
      return headers.get(name);
    }

    boolean isRedirect() {
      return (
        status == 301 || status == 302 || status == 303 || status == 307 ||
        status == 308
      );
    }

    boolean isAccessBlocked() {
      return status == 401 || status == 402 || status == 403 || status == 451;
    }
  }
}
