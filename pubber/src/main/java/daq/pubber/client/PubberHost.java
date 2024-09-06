package daq.pubber.client;

import static java.util.Optional.ofNullable;

import daq.pubber.ManagerHost;
import udmi.schema.PubberConfiguration;

/**
 * Pubber client.
 */
public interface PubberHost extends ManagerHost {

  static String getGatewayId(String targetId, PubberConfiguration configuration) {
    return ofNullable(configuration.gatewayId).orElse(
        targetId.equals(configuration.deviceId) ? null : configuration.deviceId);
  }

}
