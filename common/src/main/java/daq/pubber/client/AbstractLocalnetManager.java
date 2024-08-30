package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static java.util.stream.Collectors.toMap;

import com.google.udmi.util.SiteModel;
import daq.pubber.FamilyProvider;
import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import java.util.Map;
import udmi.schema.FamilyDiscovery;
import udmi.schema.LocalnetConfig;
import udmi.schema.LocalnetState;
import udmi.schema.PubberConfiguration;

public abstract class AbstractLocalnetManager extends ManagerBase implements ManagerHost {

  private LocalnetConfig localnetConfig;

  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractLocalnetManager(ManagerHost host, PubberConfiguration configuration) {
    super(host, configuration);
  }

  public Map<String, FamilyDiscovery> enumerateFamilies() {
    return getLocalnetState().families.keySet().stream()
        .collect(toMap(key -> key, this::makeFamilyDiscovery));
  }

  protected FamilyDiscovery makeFamilyDiscovery(String key) {
    FamilyDiscovery familyDiscovery = new FamilyDiscovery();
    familyDiscovery.addr = getLocalnetState().families.get(key).addr;
    return familyDiscovery;
  }

  public void update(Object update) {
    throw new RuntimeException("Not yet implemented");
  }



  private void updateState() {
    updateState(ifNotNullGet(localnetConfig, c -> getLocalnetState(), LocalnetState.class));
  }

  @Override
  public void publish(Object message) {
    host.publish(message);
  }


  public void updateConfig(LocalnetConfig localnet) {
    localnetConfig = localnet;
    updateState();
  }

  public abstract FamilyProvider getLocalnetProvider(String family);

  public abstract LocalnetState getLocalnetState();

  public abstract void setSiteModel(SiteModel siteModel);
}
