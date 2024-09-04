package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static java.util.stream.Collectors.toMap;

import com.google.udmi.util.SiteModel;
import daq.pubber.FamilyProvider;
import java.util.Map;
import udmi.schema.FamilyDiscovery;
import udmi.schema.LocalnetConfig;
import udmi.schema.LocalnetState;

public interface LocalnetClient {

  LocalnetConfig getLocalnetConfig();
  void setLocalnetConfig(LocalnetConfig localnetConfig);

  default Map<String, FamilyDiscovery> enumerateFamilies() {
    return getLocalnetState().families.keySet().stream()
        .collect(toMap(key -> key, this::makeFamilyDiscovery));
  }

  default FamilyDiscovery makeFamilyDiscovery(String key) {
    FamilyDiscovery familyDiscovery = new FamilyDiscovery();
    familyDiscovery.addr = getLocalnetState().families.get(key).addr;
    return familyDiscovery;
  }

  default void update(Object update) {
    throw new RuntimeException("Not yet implemented");
  }

  void updateState();

  default void updateConfig(LocalnetConfig localnet) {
    setLocalnetConfig(localnet);
    updateState();
  }

  FamilyProvider getLocalnetProvider(String family);

  LocalnetState getLocalnetState();

  void setSiteModel(SiteModel siteModel);

  void stop();

  void shutdown();
}
