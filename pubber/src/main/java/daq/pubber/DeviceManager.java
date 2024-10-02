package daq.pubber;

import com.google.udmi.util.SiteModel;
import daq.pubber.client.DeviceManagerProvider;
import daq.pubber.client.DiscoveryManagerProvider;
import daq.pubber.client.GatewayManagerProvider;
import daq.pubber.client.LocalnetManagerProvider;
import daq.pubber.client.PointsetManagerProvider;
import daq.pubber.client.SystemManagerProvider;
import udmi.schema.Config;
import udmi.schema.DevicePersistent;
import udmi.schema.Entry;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PubberConfiguration;

/**
 * Uber-manager for a complete device.
 */
public class DeviceManager extends ManagerBase implements DeviceManagerProvider {

  private final PointsetManagerProvider pointsetManager;
  private final SystemManagerProvider systemManager;
  private final LocalnetManagerProvider localnetManager;
  private final GatewayManagerProvider gatewayManager;
  private final DiscoveryManagerProvider discoveryManager;


  /**
   * Create a new instance.
   */
  public DeviceManager(ManagerHost host, PubberConfiguration configuration) {
    super(host, configuration);
    systemManager = new SystemManager(host, configuration);
    pointsetManager = new PointsetManager(host, configuration);
    localnetManager = new LocalnetManager(host, configuration);
    gatewayManager = new GatewayManager(host, configuration);
    discoveryManager = new DiscoveryManager(host, configuration, this);
  }

  @Override
  public void setPersistentData(DevicePersistent persistentData) {
    systemManager.setPersistentData(persistentData);
  }

  /**
   * Set the metadata for this device.
   */
  @Override
  public void setMetadata(Metadata metadata) {
    pointsetManager.setPointsetModel(metadata.pointset);
    systemManager.setMetadata(metadata);
    gatewayManager.setMetadata(metadata);
  }

  @Override
  public void activate() {
    gatewayManager.activate();
  }

  @Override
  public void systemLifecycle(SystemMode mode) {
    systemManager.systemLifecycle(mode);
  }

  @Override
  public void maybeRestartSystem() {
    systemManager.maybeRestartSystem();
  }

  @Override
  public void localLog(Entry report) {
    systemManager.localLog(report);
  }

  @Override
  public void localLog(String message, Level trace, String timestamp, String detail) {
    systemManager.localLog(message, trace, timestamp, detail);
  }

  @Override
  public String getTestingTag() {
    return systemManager.getTestingTag();
  }

  /**
   * Update the config of this device.
   */
  @Override
  public void updateConfig(Config config) {
    pointsetManager.updateConfig(config.pointset);
    systemManager.updateConfig(config.system, config.timestamp);
    gatewayManager.updateConfig(config.gateway);
    discoveryManager.updateConfig(config.discovery);
    localnetManager.updateConfig(config.localnet);
  }

  /**
   * Publish log message for target device.
   */
  @Override
  public void publishLogMessage(Entry logEntry, String targetId) {
    if (getDeviceId().equals(targetId)) {
      systemManager.publishLogMessage(logEntry);
    } else {
      gatewayManager.publishLogMessage(logEntry, targetId);
    }
  }

  @Override
  public void cloudLog(String message, Level level, String detail) {
    systemManager.cloudLog(message, level, detail);
  }

  @Override
  public PointsetManagerProvider getPointsetManager() {
    return pointsetManager;
  }

  @Override
  public SystemManagerProvider getSystemManager() {
    return systemManager;
  }

  @Override
  public LocalnetManagerProvider getLocalnetManager() {
    return localnetManager;
  }

  @Override
  public GatewayManagerProvider getGatewayManager() {
    return gatewayManager;
  }

  @Override
  public DiscoveryManagerProvider getDiscoveryManager() {
    return discoveryManager;
  }

  /**
   * Shutdown everything, including sub-managers.
   */
  @Override
  public void shutdown() {
    getSystemManager().shutdown();
    getPointsetManager().shutdown();
    getLocalnetManager().shutdown();
    getGatewayManager().shutdown();
  }


  /**
   * Stop periodic senders.
   */
  @Override
  public void stop() {
    pointsetManager.stop();
    localnetManager.stop();
    gatewayManager.stop();
    systemManager.stop();
  }

  /**
   * Set the site model.
   */
  protected void setSiteModel(SiteModel siteModel) {
    discoveryManager.setSiteModel(siteModel);
    gatewayManager.setSiteModel(siteModel);
    localnetManager.setSiteModel(siteModel);
  }

}
