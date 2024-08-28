package daq.pubber.client;

import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import udmi.schema.DevicePersistent;
import udmi.schema.Entry;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Operation;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PubberConfiguration;

public abstract class AbstractSystemManager extends ManagerBase {

  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractSystemManager(ManagerHost host,
      PubberConfiguration configuration) {
    super(host, configuration);
  }

  public void setPersistentData(DevicePersistent persistentData) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void setMetadata(Metadata metadata) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void systemLifecycle(SystemMode mode) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void maybeRestartSystem() {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void localLog(Entry report) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void localLog(String message, Level trace, String timestamp, String detail) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public String getTestingTag() {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void publishLogMessage(Entry logEntry) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void cloudLog(String message, Level level, String detail) {
    throw new UnsupportedOperationException("Not supported yet.");
  }
}
