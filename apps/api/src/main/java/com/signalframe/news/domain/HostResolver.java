package com.signalframe.news.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * DNS resolution port.
 *
 * <p>Kept behind a port so that destination checks can be tested
 * deterministically and so that the fetch path resolves a hostname exactly
 * once: the validated addresses are pinned for the actual connection
 * (DNS-rebinding defense).
 */
public interface HostResolver {
  List<InetAddress> resolve(String host) throws UnknownHostException;
}
