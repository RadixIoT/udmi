package daq.pubber.client;

import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import udmi.schema.Config;
import udmi.schema.DevicePersistent;
import udmi.schema.Entry;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PubberConfiguration;

public abstract class AbstractDeviceManager extends ManagerBase {

  private final AbstractPointsetManager pointsetManager;
  private final AbstractSystemManager systemManager;
  private final AbstractLocalnetManager localnetManager;
  private final AbstractGatewayManager gatewayManager;
  private final AbstractDiscoveryManager discoveryManager;

  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractDeviceManager(ManagerHost host, PubberConfiguration configuration,
      AbstractPointsetManager pointsetManager, AbstractSystemManager systemManager,
      AbstractLocalnetManager localnetManager, AbstractGatewayManager gatewayManager,
      AbstractDiscoveryManager discoveryManager) {
    super(host, configuration);
    this.pointsetManager = pointsetManager;
    this.systemManager = systemManager;
    this.localnetManager = localnetManager;
    this.gatewayManager = gatewayManager;
    this.discoveryManager = discoveryManager;
  }

  public abstract AbstractDeviceManager buildDeviceManager();

  public AbstractPointsetManager getPointsetManager() {
    return pointsetManager;
  }

  public AbstractSystemManager getSystemManager() {
    return systemManager;
  }

  public AbstractLocalnetManager getLocalnetManager() {
    return localnetManager;
  }

  public AbstractGatewayManager getGatewayManager() {
    return gatewayManager;
  }

  public AbstractDiscoveryManager getDiscoveryManager() {
    return discoveryManager;
  }


  /**
   * Shutdown everything, including sub-managers.
   */
  @Override
  public void shutdown() {
    systemManager.shutdown();
    pointsetManager.shutdown();
    localnetManager.shutdown();
    gatewayManager.shutdown();
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


  public void setPersistentData(DevicePersistent persistentData) {
    systemManager.setPersistentData(persistentData);
  }

  /**
   * Set the metadata for this device.
   */
  public void setMetadata(Metadata metadata) {
    pointsetManager.setPointsetModel(metadata.pointset);
    systemManager.setMetadata(metadata);
    gatewayManager.setMetadata(metadata);
  }

  public void activate() {
    gatewayManager.activate();
  }

  public void systemLifecycle(SystemMode mode) {
    systemManager.systemLifecycle(mode);
  }

  public void maybeRestartSystem() {
    systemManager.maybeRestartSystem();
  }

  public void localLog(Entry report) {
    systemManager.localLog(report);
  }

  public void localLog(String message, Level trace, String timestamp, String detail) {
    systemManager.localLog(message, trace, timestamp, detail);
  }

  public String getTestingTag() {
    return systemManager.getTestingTag();
  }

  /**
   * Update the config of this device.
   */
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
  public void publishLogMessage(Entry logEntry, String targetId) {
    if (getDeviceId().equals(targetId)) {
      systemManager.publishLogMessage(logEntry);
    } else {
      gatewayManager.publishLogMessage(logEntry, targetId);
    }
  }

  public void cloudLog(String message, Level level, String detail) {
    systemManager.cloudLog(message, level, detail);
  }
}
