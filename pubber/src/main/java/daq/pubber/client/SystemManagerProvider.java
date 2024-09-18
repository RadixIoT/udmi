package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.catchOrElse;
import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotTrueGet;
import static com.google.udmi.util.GeneralUtils.isTrue;
import static com.google.udmi.util.JsonUtil.isoConvert;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

import com.google.common.collect.ImmutableMap;
import com.google.udmi.util.CleanDateFormat;
import daq.pubber.ManagerLog;
import daq.pubber.Pubber;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import udmi.schema.DevicePersistent;
import udmi.schema.Entry;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Operation;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PubberOptions;
import udmi.schema.SystemConfig;
import udmi.schema.SystemEvents;
import udmi.schema.SystemState;

/**
 * System client.
 */
public interface SystemManagerProvider extends ManagerLog {

  static final String DEFAULT_MAKE = "bos";
  static final String DEFAULT_MODEL = "pubber";
  static final String DEFAULT_SOFTWARE_KEY = "firmware";
  static final String DEFAULT_SOFTWARE_VALUE = "v1";
  static final Map<SystemMode, Integer> EXIT_CODE_MAP = ImmutableMap.of(
      SystemMode.SHUTDOWN, 0, // Indicates expected clean shutdown (success).
      SystemMode.RESTART, 192, // Indicate process to be explicitly restarted.
      SystemMode.TERMINATE, 193); // Indicates expected shutdown (failure code).
  static final Integer UNKNOWN_MODE_EXIT_CODE = -1;

  static final Logger LOG = Pubber.LOG;
  public static final Map<Level, Consumer<String>> LOG_MAP =
      ImmutableMap.<Level, Consumer<String>>builder()
          .put(Level.TRACE, LOG::info) // TODO: Make debug/trace programmatically visible.
          .put(Level.DEBUG, LOG::info)
          .put(Level.INFO, LOG::info)
          .put(Level.NOTICE, LOG::info)
          .put(Level.WARNING, LOG::warn)
          .put(Level.ERROR, LOG::error)
          .build();
  List<Entry> getLogentries();

  boolean getPublishingLog();

  int getSystemEventCount();

  SystemConfig getSystemConfig();


  void systemLifecycle(SystemMode mode);

  void localLog(String message, Level trace, String timestamp, String detail);

  /**
   * Local log.
   *
   * @param entry Log entry.
   */
  default void localLog(Entry entry) {
    String message = format("Log %s%s %s %s %s%s", Level.fromValue(entry.level).name(),
        shouldLogLevel(entry.level) ? "" : "*",
        entry.category, entry.message, isoConvert(entry.timestamp), getTestingTag());
    localLog(message, Level.fromValue(entry.level), isoConvert(entry.timestamp), null);
  }

  /**
   * Retrieves the hardware and software from metadata.
   *
   * @param metadata the input metadata.
   */
  default void setHardwareSoftware(Metadata metadata) {

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

    if (getOptions().softwareFirmwareValue != null) {
      getSystemState().software.put("firmware", getOptions().softwareFirmwareValue);
    }
  }

  ExtraSystemState getSystemState();

  /**
   * Retrieves the system events.
   *
   * @return the system events.
   */
  default SystemEvents getSystemEvent() {
    SystemEvents systemEvent = new SystemEvents();
    systemEvent.last_config = getSystemState().last_config;
    return systemEvent;
  }

  /**
   * Checks the current system configuration and state to determine if a restart is necessary or
   * if the system should be terminated.
   */
  default void maybeRestartSystem() {
    SystemConfig system = ofNullable(getSystemConfig()).orElseGet(SystemConfig::new);
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
      } else if (isTrue(getOptions().smokeCheck)
          && CleanDateFormat.dateEquals(getDeviceStartTime(), configLastStart)) {
        error(format("Device start time %s matches, smoke check indicating success!",
            isoConvert(configLastStart)));
        systemLifecycle(SystemMode.SHUTDOWN);
      }
    }
  }


  Date getDeviceStartTime();

  void updateState();

  void sendSystemEvent();

  default void setMetadata(Metadata metadata) {
    setHardwareSoftware(metadata);
  }

  default void setPersistentData(DevicePersistent persistentData) {
    getSystemState().operation.restart_count = persistentData.restart_count;
  }

  void updateConfig(SystemConfig system, Date timestamp);

  /**
   * Publish log message.
   *
   * @param report report.
   */
  default void publishLogMessage(Entry report) {
    if (shouldLogLevel(report.level)) {
      getLogentries().add(report);
    }
  }

  boolean shouldLogLevel(int level);

  void cloudLog(String message, Level level, String detail);

  /**
   * Get a testing tag.

   * @return Tag string.
   */
  default String getTestingTag() {
    SystemConfig config = getSystemConfig();
    return config == null || config.testing == null
        || config.testing.sequence_name == null ? ""
        : format(" (%s)", config.testing.sequence_name);
  }

  /**
   * Log a message.
   *
   * @param logMessage Log message.
   * @param level level.
   * @param timestamp timestamp.
   * @param detail detail.
   */
  void pubberLogMessage(String logMessage, Level level, String timestamp, String detail);

  void stop();

  void shutdown();

  void error(String message);

  PubberOptions getOptions();


  /**
   * Extra system state with extra field.
   */
  class ExtraSystemState extends SystemState {

    public String extraField;
  }
}
