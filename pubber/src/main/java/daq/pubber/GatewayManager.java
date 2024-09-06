package daq.pubber;

import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static com.google.udmi.util.GeneralUtils.ifNullThen;
import static com.google.udmi.util.GeneralUtils.ifTrueGet;
import static com.google.udmi.util.GeneralUtils.ifTrueThen;
import static com.google.udmi.util.GeneralUtils.isTrue;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static udmi.schema.Category.GATEWAY_PROXY_TARGET;

import com.google.udmi.util.SiteModel;
import daq.pubber.client.GatewayManagerProvider;
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

/**
 * Manager for UDMI gateway functionality.
 */
public class GatewayManager extends ManagerBase implements GatewayManagerProvider {

  private static final String EXTRA_PROXY_DEVICE = "XXX-1";
  private static final String EXTRA_PROXY_POINT = "xxx_conflagration";
  private Map<String, ProxyDevice> proxyDevices;
  private SiteModel siteModel;
  private Metadata metadata;
  private GatewayState gatewayState;

  public GatewayManager(ManagerHost host, PubberConfiguration configuration) {
    super(host, configuration);
  }

  /**
   * Creates a map of proxy devices.
   *
   * @param proxyIds A list of device IDs to create proxies for.
   * @return A map where each key-value pair represents a device ID and its corresponding proxy
   * @throws NoSuchElementException if no first element exists in the stream
   */
  public Map<String, ProxyDevice> createProxyDevices(List<String> proxyIds) {
    if (proxyIds == null) {
      return Map.of();
    }

    Map<String, ProxyDevice> devices = new HashMap<>();
    if (!proxyIds.isEmpty()) {
      String firstId = proxyIds.stream().sorted().findFirst().orElseThrow();
      String noProxyId = ifTrueGet(isTrue(options.noProxy), () -> firstId);
      ifNotNullThen(noProxyId, id -> warn(format("Not proxying device %s", noProxyId)));
      proxyIds.forEach(id -> {
        if (!id.equals(noProxyId)) {
          devices.put(id, new ProxyDevice(host, id, config));
        }
      });
    }

    ifTrueThen(options.extraDevice, () -> devices.put(EXTRA_PROXY_DEVICE, makeExtraDevice()));

    return devices;
  }

  /**
   * Publish log message for target device.
   */
  public void publishLogMessage(Entry logEntry, String targetId) {
    ifNotNullThen(proxyDevices, p -> p.values().forEach(pd -> {
      if (pd.getDeviceId().equals(targetId)) {
        pd.deviceManager.publishLogMessage(logEntry, targetId);
      }
    }));
  }

  public void setMetadata(Metadata metadata) {
    this.metadata = metadata;
    proxyDevices = ifNotNullGet(metadata.gateway, g -> createProxyDevices(g.proxy_ids));
  }

  public void activate() {
    ifNotNullThen(proxyDevices, p -> p.values().forEach(ProxyDevice::activate));
  }

  ProxyDevice makeExtraDevice() {
    return new ProxyDevice(host, EXTRA_PROXY_DEVICE, config);
  }

  /**
   * Update gateway operation based off of a gateway configuration block.
   */
  public void updateConfig(GatewayConfig gateway) {
    if (gateway == null) {
      gatewayState = null;
      updateState();
      return;
    }
    ifNullThen(gatewayState, () -> gatewayState = new GatewayState());

    ifNotNullThen(proxyDevices,
        p -> ifTrueThen(p.containsKey(EXTRA_PROXY_DEVICE), this::configExtraDevice));

    if (gateway.proxy_ids == null || gateway.target != null) {
      try {
        String family = validateGatewayFamily(catchToNull(() -> gateway.target.family));
        setGatewayStatus(GATEWAY_PROXY_TARGET, Level.DEBUG, "gateway target family " + family);
      } catch (Exception e) {
        setGatewayStatus(GATEWAY_PROXY_TARGET, Level.ERROR, e.getMessage());
      }
    }
    updateState();
  }

  /**
   * Sets the status of the gateway.
   *
   * @param category The category of the error or warning. This could be a specific module, service,
   *                etc., that is causing the issue.
   * @param level The severity level of the message. This can be used to determine how severe the
   *              issue is and what action should be taken.
   * @param message A detailed description of the status. This provides more information about the
   *                current state of the gateway or any issues it may have encountered.
   */
  public void setGatewayStatus(String category, Level level, String message) {
    // TODO: Implement a map or tree or something to properly handle different error sources.
    gatewayState.status = new Entry();
    gatewayState.status.category = category;
    gatewayState.status.level = level.value();
    gatewayState.status.message = message;
  }

  /**
   * Updates the state of the gateway.
   */
  public void updateState() {
    updateState(ofNullable((Object) gatewayState).orElse(GatewayState.class));
  }

  /**
   * Validates the given gateway family.
   *
   * @param family The gateway family to validate.
   * @return The validated family if successful; otherwise, null.
   * @throws NullPointerException If the address for the specified family is null or undefined.
   */
  public String validateGatewayFamily(String family) {
    if (family == null) {
      return null;
    }
    debug("Validating gateway family " + family);
    Objects.requireNonNull(catchToNull(() -> metadata.localnet.families.get(family).addr),
        format("Address family %s addr is null or undefined", family));
    return family;
  }

  /**
   * Configures the extra device with default settings.
   *
   */
  public void configExtraDevice() {
    Config config = new Config();
    config.pointset = new PointsetConfig();
    config.pointset.points = new HashMap<>();
    PointPointsetConfig pointPointsetConfig = new PointPointsetConfig();
    config.pointset.points.put(EXTRA_PROXY_POINT, pointPointsetConfig);
    proxyDevices.get(EXTRA_PROXY_DEVICE).configHandler(config);
  }

  @Override
  public void shutdown() {
    super.shutdown();
    ifNotNullThen(proxyDevices, p -> p.values().forEach(ProxyDevice::shutdown));
  }

  @Override
  public void stop() {
    super.stop();
    ifNotNullThen(proxyDevices, p -> p.values().forEach(ProxyDevice::stop));
  }

  public void setSiteModel(SiteModel siteModel) {
    this.siteModel = siteModel;
    processMetadata();
  }

  private void processMetadata() {
    ifNotNullThen(proxyDevices, p -> p.values().forEach(proxy -> {
      Metadata localMetadata = ifNotNullGet(siteModel, s -> s.getMetadata(proxy.getDeviceId()));
      localMetadata = ofNullable(localMetadata).orElse(new Metadata());
      proxy.setMetadata(localMetadata);
    }));
  }

  @Override
  public Metadata getMetadata() {
    return metadata;
  }

  @Override
  public GatewayState getGatewayState() {
    return gatewayState;
  }

  @Override
  public Map<String, ProxyDevice> getProxyDevices() {
    return proxyDevices;
  }
}
