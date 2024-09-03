package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.deepCopy;
import static com.google.udmi.util.GeneralUtils.friendlyStackTrace;
import static java.lang.String.format;

import daq.pubber.FamilyProvider;
import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import daq.pubber.MqttDevice;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import udmi.schema.Config;
import udmi.schema.Metadata;
import udmi.schema.PubberConfiguration;

/**
 * Wrapper for a complete device construct.
 */
public abstract class AbstractProxyDevice extends ManagerBase implements ManagerHost {

  final AbstractPubber pubberHost;
  private final AtomicBoolean active = new AtomicBoolean();

  /**
   * New instance.
   */
  public AbstractProxyDevice(ManagerHost host, String id, PubberConfiguration config) {
    super(host, makeProxyConfiguration(id, config));
    // Simple shortcut to get access to some foundational mechanisms inside of Pubber.
    pubberHost = (AbstractPubber) host;
  }

  private static PubberConfiguration makeProxyConfiguration(String id, PubberConfiguration config) {
    PubberConfiguration proxyConfiguration = deepCopy(config);
    proxyConfiguration.deviceId = id;
    return proxyConfiguration;
  }

  public abstract void activate();

  public void configHandler(Config config) {
    pubberHost.configPreprocess(getDeviceId(), config);
    getDeviceManager().updateConfig(config);
    pubberHost.publisherConfigLog("apply", null, getDeviceId());
  }

  @Override
  public void shutdown() {
    getDeviceManager().shutdown();
  }


  @Override
  public void stop() {
    getDeviceManager().stop();
  }

  @Override
  public void publish(Object message) {
    if (active.get()) {
      pubberHost.publish(getDeviceId(), message);
    }
  }

  @Override
  public void update(Object update) {
    String simpleName = update.getClass().getSimpleName();
    warn(format("Ignoring proxy device %s update for %s", getDeviceId(), simpleName));
  }

  @Override
  public FamilyProvider getLocalnetProvider(String family) {
    return host.getLocalnetProvider(family);
  }

  public void setMetadata(Metadata metadata) {
    getDeviceManager().setMetadata(metadata);
  }

  public abstract AbstractDeviceManager getDeviceManager();
}
