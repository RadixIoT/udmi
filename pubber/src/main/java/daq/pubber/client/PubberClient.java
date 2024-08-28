package daq.pubber.client;

import static java.util.Optional.ofNullable;

import daq.pubber.FamilyProvider;
import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import daq.pubber.MqttDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udmi.schema.Config;
import udmi.schema.PubberConfiguration;

/**
 * Pubber client.
 */
public interface PubberClient extends ManagerHost {

  static String getGatewayId(String targetId, PubberConfiguration configuration) {
    return ofNullable(configuration.gatewayId).orElse(
        targetId.equals(configuration.deviceId) ? null : configuration.deviceId);
  }

}
