package daq.pubber.client;

import com.google.udmi.util.SiteModel;
import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import udmi.schema.DiscoveryConfig;
import udmi.schema.PubberConfiguration;

public abstract class AbstractDiscoveryManager extends ManagerBase {

  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractDiscoveryManager(ManagerHost host,
      PubberConfiguration configuration) {
    super(host, configuration);
  }


  public abstract void updateConfig(DiscoveryConfig discovery);

  public abstract void setSiteModel(SiteModel siteModel);
}
