package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.ifNotNullThen;

import com.google.udmi.util.SiteModel;
import daq.pubber.ProxyDevice;
import java.util.List;
import java.util.Map;
import udmi.schema.Entry;
import udmi.schema.GatewayConfig;
import udmi.schema.GatewayState;
import udmi.schema.Level;
import udmi.schema.Metadata;

/**
 * Gateway client.
 */
public interface GatewayClient {
  
  Metadata getMetadata();

  void setMetadata(Metadata metadata);

  GatewayState getGatewayState();

  Map<String, ProxyDevice> getProxyDevices();

  default void activate() {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  /**
   * Publish log message for target device.
   */
  default void publishLogMessage(Entry logEntry, String targetId) {
    ifNotNullThen(getProxyDevices(), p -> p.values().forEach(pd -> {
      if (pd.getDeviceId().equals(targetId)) {
        pd.getDeviceManager().publishLogMessage(logEntry, targetId);
      }
    }));
  }

  Map<String, ProxyDevice> createProxyDevices(List<String> proxyIds);

  /**
   * Sets gateway status.
   *
   * @param category Category.
   * @param level Level.
   * @param message Message.
   */
  default void setGatewayStatus(String category, Level level, String message) {
    // TODO: Implement a map or tree or something to properly handle different error sources.
    getGatewayState().status = new Entry();
    getGatewayState().status.category = category;
    getGatewayState().status.level = level.value();
    getGatewayState().status.message = message;
  }

  void stop();

  void updateState();

  void shutdown();

  String validateGatewayFamily(String family);

  void configExtraDevice();

  void updateConfig(GatewayConfig gateway);

  void setSiteModel(SiteModel siteModel);
}
