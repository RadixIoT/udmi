package daq.pubber.client;

import udmi.schema.Config;
import udmi.schema.DevicePersistent;
import udmi.schema.Entry;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Operation.SystemMode;

/**
 * Device client.
 */
public interface DeviceClient {
  
  PointsetClient getPointsetManager();

  SystemClient getSystemManager();

  LocalnetClient getLocalnetManager();

  GatewayClient getGatewayManager();

  DiscoveryClient getDiscoveryManager();

  /**
   * Shutdown everything, including sub-managers.
   */
  default void shutdown() {
    getSystemManager().shutdown();
    getPointsetManager().shutdown();
    getLocalnetManager().shutdown();
    getGatewayManager().shutdown();
  }

  /**
   * Stop periodic senders.
   */
  
  default void stop() {
    getPointsetManager().stop();
    getLocalnetManager().stop();
    getGatewayManager().stop();
    getSystemManager().stop();
  }


  default void setPersistentData(DevicePersistent persistentData) {
    getSystemManager().setPersistentData(persistentData);
  }

  /**
   * Set the metadata for this device.
   */
  default void setMetadata(Metadata metadata) {
    getPointsetManager().setPointsetModel(metadata.pointset);
    getSystemManager().setMetadata(metadata);
    getGatewayManager().setMetadata(metadata);
  }

  default void activate() {
    getGatewayManager().activate();
  }

  default void systemLifecycle(SystemMode mode) {
    getSystemManager().systemLifecycle(mode);
  }

  default void maybeRestartSystem() {
    getSystemManager().maybeRestartSystem();
  }

  default void localLog(Entry report) {
    getSystemManager().localLog(report);
  }

  default void localLog(String message, Level trace, String timestamp, String detail) {
    getSystemManager().localLog(message, trace, timestamp, detail);
  }

  default String getTestingTag() {
    return getSystemManager().getTestingTag();
  }

  /**
   * Update the config of this device.
   */
  default void updateConfig(Config config) {
    getPointsetManager().updateConfig(config.pointset);
    getSystemManager().updateConfig(config.system, config.timestamp);
    getGatewayManager().updateConfig(config.gateway);
    getDiscoveryManager().updateConfig(config.discovery);
    getLocalnetManager().updateConfig(config.localnet);
  }

  /**
   * Publish log message for target device.
   */
  default void publishLogMessage(Entry logEntry, String targetId) {
    if (getDeviceId().equals(targetId)) {
      getSystemManager().publishLogMessage(logEntry);
    } else {
      getGatewayManager().publishLogMessage(logEntry, targetId);
    }
  }

  default void cloudLog(String message, Level level, String detail) {
    getSystemManager().cloudLog(message, level, detail);
  }


  String getDeviceId();

}
