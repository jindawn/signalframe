package com.signalframe.news.support;

import com.signalframe.infrastructure.news.PublicDestinationPolicy;
import com.signalframe.news.domain.DestinationPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Test-only destination policy.
 *
 * <p>It permits the loopback fixture host (which uses an ephemeral port) while
 * delegating every other destination to the production
 * {@link PublicDestinationPolicy}, so redirect and DNS decisions are still
 * exercised end to end. Production code has no such bypass.
 */
public final class FixtureDestinationPolicy implements DestinationPolicy {

  private final PublicDestinationPolicy production = new PublicDestinationPolicy();
  private final Set<String> allowedHosts;

  public FixtureDestinationPolicy(String... allowedHosts) {
    this.allowedHosts = Set.of(allowedHosts);
  }

  @Override
  public void verifyShape(URI uri) {
    if (isFixtureHost(uri)) {
      // Scheme and credentials are still enforced; only the port rule is relaxed
      // so the ephemeral fixture port can be used.
      PublicDestinationPolicy.validateSchemeAndAuthority(uri);
      return;
    }
    production.verifyShape(uri);
  }

  @Override
  public void verifyAddresses(URI uri, List<InetAddress> resolved) {
    if (isFixtureHost(uri)) return;
    production.verifyAddresses(uri, resolved);
  }

  private boolean isFixtureHost(URI uri) {
    String host = PublicDestinationPolicy.hostName(uri);
    return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
  }
}
