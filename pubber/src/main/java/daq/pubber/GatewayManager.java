package daq.pubber;

import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.getNow;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static com.google.udmi.util.GeneralUtils.ifNullThen;
import static com.google.udmi.util.GeneralUtils.ifTrueGet;
import static com.google.udmi.util.GeneralUtils.ifTrueThen;
import static com.google.udmi.util.GeneralUtils.isTrue;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;
import static udmi.schema.Category.GATEWAY_PROXY_TARGET;

import com.google.udmi.util.SiteModel;
import daq.pubber.client.GatewayManagerProvider;
import daq.pubber.client.ProxyDeviceHostProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  private Map<String, ProxyDeviceHostProvider> proxyDevices;
  private SiteModel siteModel;
  private Metadata metadata;
  private GatewayState gatewayState;

  public GatewayManager(ManagerHost host, PubberConfiguration configuration) {
    super(host, configuration);
  }

  /**
   * Publish log message for target device.
   */
  @Override
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


  @Override
  public ProxyDeviceHostProvider makeExtraDevice() {
    return new ProxyDevice(getHost(), EXTRA_PROXY_DEVICE, getConfig());
  }

  @Override
  public void activate() {
    ifNotNullThen(proxyDevices, p -> p.values().forEach(ProxyDeviceHostProvider::activate));
  }

  /**
   * Update gateway operation based off of a gateway configuration block. This happens in two
   * slightly different forms, one for the gateway proper (primarily indicating what devices
   * should be proxy targets), and the other for the proxy devices themselves.
   */
  @Override
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
        String addr = catchToNull(() -> gateway.target.addr);
        String family = ofNullable(catchToNull(() -> gateway.target.family))
            .orElse(ProtocolFamily.VENDOR);
        validateGatewayFamily(family, addr);
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
  @Override
  public void setGatewayStatus(String category, Level level, String message) {
    // TODO: Implement a map or tree or something to properly handle different error sources.
    gatewayState.status = new Entry();
    gatewayState.status.category = category;
    gatewayState.status.level = level.value();
    gatewayState.status.message = message;
    gatewayState.status.timestamp = getNow();
  }

  private void validateGatewayFamily(String family, String addr) {
    if (!ProtocolFamily.FAMILIES.contains(family)) {
      throw new IllegalArgumentException("Unrecognized address family " + family);
    }

    String expectedAddr = catchToNull(() -> metadata.localnet.families.get(family).addr);

    if (expectedAddr != null && !expectedAddr.equals(addr)) {
      throw new IllegalStateException(
          format("Family address was %s, expected %s", addr, expectedAddr));
    }
  }

  @Override
  public void shutdown() {
    super.shutdown();
    ifNotNullThen(proxyDevices, p -> p.values().forEach(ProxyDeviceHostProvider::shutdown));
  }

  @Override
  public void stop() {
    super.stop();
    ifNotNullThen(proxyDevices, p -> p.values().forEach(ProxyDeviceHostProvider::stop));
  }

  public void setSiteModel(SiteModel siteModel) {
    this.siteModel = siteModel;
    processMetadata();
  }

  void processMetadata() {
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
  public Map<String, ProxyDeviceHostProvider> getProxyDevices() {
    return proxyDevices;
  }

  @Override
  public ProxyDeviceHostProvider createProxyDevice(ManagerHost host, String id,
      PubberConfiguration config) {
    return new ProxyDevice(host, id, config);
  }
}
