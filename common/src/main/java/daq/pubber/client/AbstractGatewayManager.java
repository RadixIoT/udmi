package daq.pubber.client;

import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import udmi.schema.Entry;
import udmi.schema.Metadata;
import udmi.schema.PubberConfiguration;

public abstract class AbstractGatewayManager extends ManagerBase {

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

  public void setMetadata(Metadata metadata) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void activate() {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void publishLogMessage(Entry logEntry, String targetId) {
    throw new UnsupportedOperationException("Not supported yet.");
  }
}
