package daq.pubber;

import com.google.udmi.util.SiteModel;
import daq.pubber.client.DeviceClient;
import daq.pubber.client.DiscoveryClient;
import daq.pubber.client.GatewayClient;
import daq.pubber.client.LocalnetClient;
import daq.pubber.client.PointsetClient;
import daq.pubber.client.SystemClient;
import java.util.Map;
import udmi.schema.Config;
import udmi.schema.DevicePersistent;
import udmi.schema.Entry;
import udmi.schema.FamilyDiscovery;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PubberConfiguration;

/**
 * Uber-manager for a complete device.
 */
public class DeviceManager extends ManagerBase implements DeviceClient {

  private final PointsetClient pointsetManager;
  private final SystemClient systemManager;
  private final LocalnetClient localnetManager;
  private final GatewayClient gatewayManager;
  private final DiscoveryClient discoveryManager;


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

  @Override
  public PointsetClient getPointsetManager() {
    return pointsetManager;
  }

  @Override
  public SystemClient getSystemManager() {
    return systemManager;
  }

  @Override
  public LocalnetClient getLocalnetManager() {
    return localnetManager;
  }

  @Override
  public GatewayClient getGatewayManager() {
    return gatewayManager;
  }

  @Override
  public DiscoveryClient getDiscoveryManager() {
    return discoveryManager;
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

  public Map<String, FamilyDiscovery> enumerateFamilies() {
    return localnetManager.enumerateFamilies();
  }

  /**
   * Set the site model.
   */
  protected void setSiteModel(SiteModel siteModel) {
    discoveryManager.setSiteModel(siteModel);
    gatewayManager.setSiteModel(siteModel);
    localnetManager.setSiteModel(siteModel);
  }

  public FamilyProvider getLocalnetProvider(String family) {
    return localnetManager.getLocalnetProvider(family);
  }
}
