package daq.pubber.client;

import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import udmi.schema.PointsetModel;
import udmi.schema.PubberConfiguration;

public abstract class AbstractPointsetManager extends ManagerBase {

  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractPointsetManager(ManagerHost host,
      PubberConfiguration configuration) {
    super(host, configuration);
  }

  public void setPointsetModel(PointsetModel pointset) {
    throw new UnsupportedOperationException("Not supported yet.");
  }
}
