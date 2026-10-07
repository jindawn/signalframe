package com.signalframe.infrastructure.news;

import com.signalframe.news.domain.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

/** System DNS resolver. Called once per fetch hop; results are pinned. */
@Component
public class SystemHostResolver implements HostResolver {

  @Override
  public List<InetAddress> resolve(String host) throws UnknownHostException {
    return List.of(InetAddress.getAllByName(host));
  }
}
