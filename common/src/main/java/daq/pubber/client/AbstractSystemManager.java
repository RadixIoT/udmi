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

public abstract class AbstractSystemManager extends ManagerBase {

  public static final String PUBBER_LOG_CATEGORY = "device.log";

  protected static final long BYTES_PER_MEGABYTE = 1024 * 1024;
  protected static final String DEFAULT_MAKE = "bos";
  protected static final String DEFAULT_MODEL = "pubber";
  protected static final String DEFAULT_SOFTWARE_KEY = "firmware";
  protected static final String DEFAULT_SOFTWARE_VALUE = "v1";
  protected static final Map<SystemMode, Integer> EXIT_CODE_MAP = ImmutableMap.of(
              SystemMode.SHUTDOWN, 0, // Indicates expected clean shutdown (success).
              SystemMode.RESTART, 192, // Indicate process to be explicitly restarted.protected int systemEventCount;
              SystemMode.TERMINATE, 193); // Indicates expected shutdown (failure code).protected SystemConfig systemConfig;
  private final List<Entry> logentries = new ArrayList<>();
  protected static final Integer UNKNOWN_MODE_EXIT_CODE = -1;
  protected boolean publishingLog;
  private int systemEventCount;
  private SystemConfig systemConfig;
  protected static final Logger LOG = AbstractPubber.LOG;
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


  public void systemLifecycle(SystemMode mode) {
    throw new UnsupportedOperationException("Not supported yet.");
  }


  public void localLog(String message, Level trace, String timestamp, String detail) {
    throw new UnsupportedOperationException("Not supported yet.");
  }


  protected void setHardwareSoftware(Metadata metadata) {

    getSystemState().hardware.make = catchOrElse(
        () -> metadata.system.hardware.make, () -> DEFAULT_MAKE);

    getSystemState().hardware.model = catchOrElse(
        () -> metadata.system.hardware.model, () -> DEFAULT_MODEL);

    getSystemState().software = new HashMap<>();
    Map<String, String> metadataSoftware = catchToNull(() -> metadata.system.software);
    if (metadataSoftware == null) {
      getSystemState().software.put(DEFAULT_SOFTWARE_KEY, DEFAULT_SOFTWARE_VALUE);
    } else {
      getSystemState().software = metadataSoftware;
    }

    if (options.softwareFirmwareValue != null) {
      getSystemState().software.put("firmware", options.softwareFirmwareValue);
    }
  }

  protected abstract ExtraSystemState getSystemState();

  protected SystemEvents getSystemEvent() {
    SystemEvents systemEvent = new SystemEvents();
    systemEvent.last_config = getSystemState().last_config;
    return systemEvent;
  }

  void maybeRestartSystem() {
    SystemConfig system = ofNullable(systemConfig).orElseGet(SystemConfig::new);
    Operation operation = ofNullable(system.operation).orElseGet(Operation::new);
    SystemMode configMode = operation.mode;
    SystemMode stateMode = getSystemState().operation.mode;

    if (SystemMode.ACTIVE.equals(stateMode)
        && SystemMode.RESTART.equals(configMode)) {
      error("System mode requesting device restart");
      systemLifecycle(SystemMode.RESTART);
    }

    if (SystemMode.ACTIVE.equals(configMode)) {
      getSystemState().operation.mode = SystemMode.ACTIVE;
      updateState();
    }

    Date configLastStart = operation.last_start;
    if (configLastStart != null) {
      if (getDeviceStartTime().before(configLastStart)) {
        error(format("Device start time %s before last config start %s, terminating.",
            isoConvert(getDeviceStartTime()), isoConvert(configLastStart)));
        systemLifecycle(SystemMode.TERMINATE);
      } else if (isTrue(options.smokeCheck)
          && CleanDateFormat.dateEquals(getDeviceStartTime(), configLastStart)) {
        error(format("Device start time %s matches, smoke check indicating success!",
            isoConvert(configLastStart)));
        systemLifecycle(SystemMode.SHUTDOWN);
      }
    }
  }

  protected abstract Date getDeviceStartTime();

  private void updateState() {
    host.update(getSystemState());
  }

  private void sendSystemEvent() {
    SystemEvents systemEvent = getSystemEvent();
    systemEvent.metrics = new Metrics();
    Runtime runtime = Runtime.getRuntime();
    systemEvent.metrics.mem_free_mb = (double) runtime.freeMemory() / BYTES_PER_MEGABYTE;
    systemEvent.metrics.mem_total_mb = (double) runtime.totalMemory() / BYTES_PER_MEGABYTE;
    systemEvent.metrics.store_total_mb = Double.NaN;
    systemEvent.event_count = systemEventCount++;
    ifNotTrueThen(options.noLog, () -> systemEvent.logentries = ImmutableList.copyOf(logentries));
    logentries.clear();
    host.publish(systemEvent);
  }

  @Override
  protected void periodicUpdate() {
    sendSystemEvent();
  }



  public void setMetadata(Metadata metadata) {
    setHardwareSoftware(metadata);
  }

  public void setPersistentData(DevicePersistent persistentData) {
    getSystemState().operation.restart_count = persistentData.restart_count;
  }

  void updateConfig(SystemConfig system, Date timestamp) {
    Integer oldBase = catchToNull(() -> systemConfig.testing.config_base);
    Integer newBase = catchToNull(() -> system.testing.config_base);
    if (oldBase != null && oldBase.equals(newBase)
        && !stringify(systemConfig).equals(stringify(system))) {
      error("Panic! Duplicate config_base detected: " + oldBase);
//            System.exit(-22);
    }
    systemConfig = system;
    getSystemState().last_config = ifNotTrueGet(options.noLastConfig, () -> timestamp);
    updateInterval(ifNotNullGet(system, config -> config.metrics_rate_sec));
    updateState();
  }

  void publishLogMessage(Entry report) {
    if (shouldLogLevel(report.level)) {
      logentries.add(report);
    }
  }

  private boolean shouldLogLevel(int level) {
    if (options.fixedLogLevel != null) {
      return level >= options.fixedLogLevel;
    }

    Integer minLoglevel = ifNotNullGet(systemConfig, config -> systemConfig.min_loglevel);
    return level >= requireNonNullElse(minLoglevel, Level.INFO.value());
  }

  void cloudLog(String message, Level level, String detail) {
    String timestamp = getTimestamp();
    localLog(message, level, timestamp, detail);

    if (publishingLog) {
      return;
    }

    try {
      publishingLog = true;
      pubberLogMessage(message, level, timestamp, detail);
    } catch (Exception e) {
      localLog("Error publishing log message: " + e, Level.ERROR, timestamp, null);
    } finally {
      publishingLog = false;
    }
  }

  String getTestingTag() {
    SystemConfig config = systemConfig;
    return config == null || config.testing == null
        || config.testing.sequence_name == null ? ""
        : format(" (%s)", config.testing.sequence_name);
  }

  void localLog(Entry entry) {
    String message = format("Log %s%s %s %s %s%s", Level.fromValue(entry.level).name(),
        shouldLogLevel(entry.level) ? "" : "*",
        entry.category, entry.message, isoConvert(entry.timestamp), getTestingTag());
    localLog(message, Level.fromValue(entry.level), isoConvert(entry.timestamp), null);
  }

  /**
   * Log a message.
   */
  public void pubberLogMessage(String logMessage, Level level, String timestamp, String detail) {
    Entry logEntry = new Entry();
    logEntry.category = PUBBER_LOG_CATEGORY;
    logEntry.level = level.value();
    logEntry.timestamp = Date.from(Instant.parse(timestamp));
    logEntry.message = logMessage;
    logEntry.detail = detail;
    publishLogMessage(logEntry);
  }

  public static class ExtraSystemState extends SystemState {

    public String extraField;
  }

  protected abstract void initializeLogger();

  protected abstract void systemLifecycle();

  protected abstract void localLog();
}
