package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

import com.google.udmi.util.SiteModel;
import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import udmi.schema.Config;
import udmi.schema.Entry;
import udmi.schema.GatewayConfig;
import udmi.schema.GatewayState;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.PointPointsetConfig;
import udmi.schema.PointsetConfig;
import udmi.schema.PubberConfiguration;

public interface AbstractGatewayManager {
  
  Metadata getMetadata();
  void setMetadata(Metadata metadata);
  GatewayState getGatewayState();
  Map<String, AbstractProxyDevice> getProxyDevices();

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

  Map<String, AbstractProxyDevice> createProxyDevices(List<String> proxyIds);

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
