package daq.pubber.client;

import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
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
}
