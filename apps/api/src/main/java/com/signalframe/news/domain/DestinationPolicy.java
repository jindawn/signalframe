package com.signalframe.news.domain;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/**
 * SSRF boundary for outbound news fetching.
 *
 * <p>The check is split in two so the call order is explicit: a URL shape is
 * validated before DNS resolution, and the resolved addresses are validated
 * before any connection is made. Implementations decide whether a destination
 * may be fetched at all; the fetch chain must call both steps on every hop,
 * including every redirect target.
 */
public interface DestinationPolicy {
  /** Scheme, credentials and port rules. Must not perform I/O. */
  void verifyShape(URI uri) throws IngestionException;

  /**
   * @param uri      the URL about to be fetched
   * @param resolved every address the hostname currently resolves to
   * @throws IngestionException when the destination must not be fetched
   */
  void verifyAddresses(URI uri, List<InetAddress> resolved)
    throws IngestionException;

  /** Convenience for callers that already resolved the host. */
  default void verify(URI uri, List<InetAddress> resolved) {
    verifyShape(uri);
    verifyAddresses(uri, resolved);
  }
}
