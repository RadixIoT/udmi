package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import daq.pubber.AbstractProxyDevice;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import udmi.schema.Config;
import udmi.schema.Entry;
import udmi.schema.GatewayState;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.PointPointsetConfig;
import udmi.schema.PointsetConfig;
import udmi.schema.PubberConfiguration;

public abstract class AbstractGatewayManager extends ManagerBase {

  protected static final String EXTRA_PROXY_DEVICE = "XXX-1";
  protected static final String EXTRA_PROXY_POINT = "xxx_conflagration";
  protected Metadata metadata;
  protected GatewayState gatewayState;
  protected Map<String, AbstractProxyDevice> proxyDevices;



  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractGatewayManager(ManagerHost host,
      PubberConfiguration configuration) {
    super(host, configuration);
  }

  public void activate() {
    throw new UnsupportedOperationException("Not supported yet.");
  }


  /**
   * Publish log message for target device.
   */
  public void publishLogMessage(Entry logEntry, String targetId) {
    ifNotNullThen(proxyDevices, p -> p.values().forEach(pd -> {
      if (pd.getDeviceId().equals(targetId)) {
        pd.getDeviceManager().publishLogMessage(logEntry, targetId);
      }
    }));
  }

  public void setMetadata(Metadata metadata) {
    this.metadata = metadata;
    proxyDevices = ifNotNullGet(metadata.gateway, g -> createProxyDevices(g.proxy_ids));
  }

  private Map<String, AbstractProxyDevice> createProxyDevices(List<String> proxyIds) {
    throw new UnsupportedOperationException("Not supported yet.");
  }


  protected void setGatewayStatus(String category, Level level, String message) {
    // TODO: Implement a map or tree or something to properly handle different error sources.
    gatewayState.status = new Entry();
    gatewayState.status.category = category;
    gatewayState.status.level = level.value();
    gatewayState.status.message = message;
  }

  protected void updateState() {
    updateState(ofNullable((Object) gatewayState).orElse(GatewayState.class));
  }

  protected String validateGatewayFamily(String family) {
    if (family == null) {
      return null;
    }
    debug("Validating gateway family " + family);
    Objects.requireNonNull(catchToNull(() -> metadata.localnet.families.get(family).addr),
        format("Address family %s addr is null or undefined", family));
    return family;
  }

  protected void configExtraDevice() {
    Config config = new Config();
    config.pointset = new PointsetConfig();
    config.pointset.points = new HashMap<>();
    PointPointsetConfig pointPointsetConfig = new PointPointsetConfig();
    config.pointset.points.put(EXTRA_PROXY_POINT, pointPointsetConfig);
    proxyDevices.get(EXTRA_PROXY_DEVICE).configHandler(config);
  }
}
