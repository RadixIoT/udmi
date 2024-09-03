package daq.pubber;

import static com.google.udmi.util.GeneralUtils.deepCopy;
import static com.google.udmi.util.GeneralUtils.friendlyStackTrace;
import static java.lang.String.format;

import daq.pubber.client.AbstractDeviceManager;
import daq.pubber.client.AbstractProxyDevice;
import java.util.concurrent.atomic.AtomicBoolean;
import udmi.schema.Config;
import udmi.schema.Metadata;
import udmi.schema.PubberConfiguration;

/**
 * Wrapper for a complete device construct.
 */
public class ProxyDevice extends AbstractProxyDevice {

  final DeviceManager deviceManager;
  final Pubber pubberHost;
  private final AtomicBoolean active = new AtomicBoolean();

  /**
   * New instance.
   */
  public ProxyDevice(ManagerHost host, String id, PubberConfiguration config) {
    super(host, id, config);
    // Simple shortcut to get access to some foundational mechanisms inside of Pubber.
    pubberHost = (Pubber) host;
    deviceManager = new DeviceManager(this, makeProxyConfiguration(id, config));
  }

  private static PubberConfiguration makeProxyConfiguration(String id, PubberConfiguration config) {
    PubberConfiguration proxyConfiguration = deepCopy(config);
    proxyConfiguration.deviceId = id;
    return proxyConfiguration;
  }

  @Override
  public void activate() {
    try {
      active.set(false);
      MqttDevice mqttDevice = pubberHost.getMqttDevice(getDeviceId());
      mqttDevice.registerHandler(MqttDevice.CONFIG_TOPIC, this::configHandler, Config.class);
      mqttDevice.connect(getDeviceId());
      deviceManager.activate();
      active.set(true);
      info("Activated proxy device " + getDeviceId());
    } catch (Exception e) {
      error(format("Could not connect proxy device %s: %s", getDeviceId(), friendlyStackTrace(e)));
    }
  }

  @Override
  public void configHandler(Config config) {
    pubberHost.configPreprocess(getDeviceId(), config);
    deviceManager.updateConfig(config);
    pubberHost.publisherConfigLog("apply", null, getDeviceId());
  }

  @Override
  public void shutdown() {
    deviceManager.shutdown();
  }

  @Override
  public void stop() {
    deviceManager.stop();
  }

  @Override
  public void publish(Object message) {
    if (active.get()) {
      pubberHost.publish(getDeviceId(), message);
    }
  }

  @Override
  public void setMetadata(Metadata metadata) {
    deviceManager.setMetadata(metadata);
  }

  @Override
  public AbstractDeviceManager getDeviceManager() {
    return deviceManager;
  }
}
