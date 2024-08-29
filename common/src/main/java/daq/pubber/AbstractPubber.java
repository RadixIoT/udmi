package daq.pubber;

import static java.util.Optional.ofNullable;

import java.util.Date;
import udmi.schema.Config;
import udmi.schema.PubberConfiguration;

public class AbstractPubber extends ManagerBase implements ManagerHost {

  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractPubber(ManagerHost host, PubberConfiguration configuration) {
    super(host, configuration);
  }

  @Override
  public <T> void updateConfig(T config) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public <T> void updateConfig(T config, Date timestamp) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public void update(Object update) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public void publish(Object message) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public FamilyProvider getLocalnetProvider(String family) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void publish(String deviceId, Object message) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  static String getGatewayId(String targetId, PubberConfiguration configuration) {
    return ofNullable(configuration.gatewayId).orElse(
        targetId.equals(configuration.deviceId) ? null : configuration.deviceId);
  }

  public MqttDevice getMqttDevice(String deviceId) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void configPreprocess(String deviceId, Config config) {
    throw new UnsupportedOperationException("Not supported yet.");

  }

  public void publisherConfigLog(String apply, Object o, String deviceId) {
    throw new UnsupportedOperationException("Not supported yet.");
  }
}
