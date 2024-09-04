package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.catchOrElse;
import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.getTimestamp;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotTrueGet;
import static com.google.udmi.util.GeneralUtils.ifNotTrueThen;
import static com.google.udmi.util.GeneralUtils.isTrue;
import static com.google.udmi.util.JsonUtil.isoConvert;
import static com.google.udmi.util.JsonUtil.stringify;
import static java.lang.String.format;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.udmi.util.CleanDateFormat;
import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import udmi.schema.DevicePersistent;
import udmi.schema.Entry;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Metrics;
import udmi.schema.Operation;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PubberConfiguration;
import udmi.schema.SystemConfig;
import udmi.schema.SystemEvents;
import udmi.schema.SystemModel;
import udmi.schema.SystemState;

public interface AbstractSystemManager  {

  List<Entry> getLogentries();
  boolean getPublishingLog();
  int getSystemEventCount();
  SystemConfig getSystemConfig();


  void systemLifecycle(SystemMode mode);

  void localLog(String message, Level trace, String timestamp, String detail);


  void setHardwareSoftware(Metadata metadata);

  ExtraSystemState getSystemState();

  SystemEvents getSystemEvent();

  void maybeRestartSystem();

  Date getDeviceStartTime();

  void updateState();

  void sendSystemEvent();


  default void periodicUpdate() {
    sendSystemEvent();
  }

  default void setMetadata(Metadata metadata) {
    setHardwareSoftware(metadata);
  }

  default void setPersistentData(DevicePersistent persistentData) {
    getSystemState().operation.restart_count = persistentData.restart_count;
  }

  void updateConfig(SystemConfig system, Date timestamp);

  default void publishLogMessage(Entry report) {
    if (shouldLogLevel(report.level)) {
      getLogentries().add(report);
    }
  }

  boolean shouldLogLevel(int level);

  void cloudLog(String message, Level level, String detail);

  default String getTestingTag() {
    SystemConfig config = getSystemConfig();
    return config == null || config.testing == null
        || config.testing.sequence_name == null ? ""
        : format(" (%s)", config.testing.sequence_name);
  }

  default void localLog(Entry entry) {
    String message = format("Log %s%s %s %s %s%s", Level.fromValue(entry.level).name(),
        shouldLogLevel(entry.level) ? "" : "*",
        entry.category, entry.message, isoConvert(entry.timestamp), getTestingTag());
    localLog(message, Level.fromValue(entry.level), isoConvert(entry.timestamp), null);
  }

  /**
   * Log a message.
   */
  void pubberLogMessage(String logMessage, Level level, String timestamp, String detail);

  void stop();

  void shutdown();

  class ExtraSystemState extends SystemState {

    public String extraField;
  }

}
