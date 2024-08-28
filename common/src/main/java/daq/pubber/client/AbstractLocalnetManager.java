package daq.pubber.client;

import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import udmi.schema.PubberConfiguration;

public abstract class AbstractLocalnetManager extends ManagerBase implements ManagerHost {

  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractLocalnetManager(ManagerHost host, PubberConfiguration configuration) {
    super(host, configuration);
  }
}
