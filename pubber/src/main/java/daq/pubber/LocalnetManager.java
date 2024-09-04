package daq.pubber;

import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static java.util.stream.Collectors.toMap;

import com.google.common.collect.ImmutableMap;
import com.google.udmi.util.SiteModel;
import daq.pubber.client.AbstractDeviceManager;
import daq.pubber.client.AbstractLocalnetManager;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import udmi.schema.FamilyDiscovery;
import udmi.schema.FamilyLocalnetState;
import udmi.schema.LocalnetConfig;
import udmi.schema.LocalnetState;
import udmi.schema.PubberConfiguration;

/**
 * Container class for dealing with the localnet subblock of UDMI.
 */
public class LocalnetManager extends ManagerBase implements AbstractLocalnetManager, ManagerHost {

  private static final Map<String, Class<? extends FamilyProvider>> LOCALNET_PROVIDERS =
      ImmutableMap.of(
          ProtocolFamily.VENDOR, VendorProvider.class,
          ProtocolFamily.IPV_4, IpProvider.class,
          ProtocolFamily.IPV_6, IpProvider.class,
          ProtocolFamily.ETHER, IpProvider.class);
  private final LocalnetState localnetState;
  private final Map<String, FamilyProvider> localnetProviders;
  private LocalnetConfig localnetConfig;

  /**
   * Create a new container with the given host.
   */
  public LocalnetManager(ManagerHost host, PubberConfiguration configuration) {
    super(host, configuration);
    localnetState = new LocalnetState();
    localnetState.families = new HashMap<>();
    localnetProviders = LOCALNET_PROVIDERS
        .keySet().stream().collect(Collectors.toMap(family -> family, this::instantiateProvider));
  }

  private FamilyProvider instantiateProvider(String family) {
    try {
      return LOCALNET_PROVIDERS.get(family).getDeclaredConstructor(
              ManagerHost.class, String.class, PubberConfiguration.class)
          .newInstance(this, family, config);
    } catch (Exception e) {
      throw new RuntimeException("While creating instance of " + LOCALNET_PROVIDERS.get(family), e);
    }
  }

  public FamilyProvider getLocalnetProvider(String family) {
    return localnetProviders.get(family);
  }

  @Override
  public LocalnetState getLocalnetState() {
    return this.localnetState;
  }

  @Override
  public void update(Object update) {
    throw new RuntimeException("Not yet implemented");
  }

  protected void update(String family, FamilyLocalnetState stateEntry) {
    localnetState.families.put(family, stateEntry);
    updateState();
  }

  public void updateState() {
    updateState(ifNotNullGet(localnetConfig, c -> localnetState, LocalnetState.class));
  }

  @Override
  public void publish(Object message) {
    host.publish(message);
  }

  public void setSiteModel(SiteModel siteModel) {
    ((VendorProvider) localnetProviders.get(ProtocolFamily.VENDOR)).setSiteModel(siteModel);
  }

  @Override
  public LocalnetConfig getLocalnetConfig() {
    return localnetConfig;
  }

  @Override
  public void setLocalnetConfig(LocalnetConfig localnetConfig) {
    this.localnetConfig = localnetConfig;
  }
}
