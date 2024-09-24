package daq.pubber;

import static com.google.udmi.util.GeneralUtils.catchOrElse;
import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.getTimestamp;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
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
import daq.pubber.client.SystemManagerProvider;
import java.io.File;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import udmi.schema.Entry;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Metrics;
import udmi.schema.Operation;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PubberConfiguration;
import udmi.schema.StateSystemHardware;
import udmi.schema.StateSystemOperation;
import udmi.schema.SystemConfig;
import udmi.schema.SystemEvents;

/**
 * Support manager for system stuff.
 */
public class SystemManager extends ManagerBase implements SystemManagerProvider {

  public static final String PUBBER_LOG = "pubber.log";
  private static final Date DEVICE_START_TIME = Pubber.DEVICE_START_TIME;

  private final List<Entry> logentries = new ArrayList<>();
  private final ExtraSystemState systemState;
  private int systemEventCount;
  private SystemConfig systemConfig;
  private boolean publishingLog;

  /**
   * New instance.
   */
  public SystemManager(ManagerHost host, PubberConfiguration configuration) {
    super(host, configuration);

    if (host instanceof Pubber pubberHost) {
      initializeLogger(pubberHost);
    }

    info("Device start time is " + isoConvert(DEVICE_START_TIME));

    systemState = new ExtraSystemState();
    systemState.operation = new StateSystemOperation();

    if (!isTrue(options.noLastStart)) {
      systemState.operation.last_start = DEVICE_START_TIME;
    }

    systemState.hardware = isTrue(options.noHardware) ? null : new StateSystemHardware();

    systemState.operation.operational = true;
    systemState.operation.mode = SystemMode.INITIAL;
    if (host instanceof Pubber) {
      systemState.serial_no = configuration.serialNo;
    }
    systemState.last_config = new Date(0);

    ifNotNullThen(options.extraField, value -> systemState.extraField = value);

    updateState();
  }

  private void initializeLogger(Pubber host) {
    File outDir = new File(Pubber.PUBBER_OUT);
    try {
      outDir.mkdirs();
      host.logPrintWriter = new PrintStream(new File(outDir, PUBBER_LOG));
      host.logPrintWriter.println("Pubber log started at " + getTimestamp());
    } catch (Exception e) {
      throw new RuntimeException("While initializing out dir " + outDir.getAbsolutePath(), e);
    }
  }

  @Override
  public void shutdown() {
    super.shutdown();
    if (host instanceof Pubber pubberHost && pubberHost.logPrintWriter != null) {
      pubberHost.logPrintWriter.close();
    }
  }

  /**
   * Retrieves the hardware and software from metadata.
   *
   * @param metadata the input metadata.
   */
  @Override
  public void setHardwareSoftware(Metadata metadata) {

    systemState.hardware.make = catchOrElse(
        () -> metadata.system.hardware.make, () -> DEFAULT_MAKE);

    systemState.hardware.model = catchOrElse(
        () -> metadata.system.hardware.model, () -> DEFAULT_MODEL);

    systemState.software = new HashMap<>();
    Map<String, String> metadataSoftware = catchToNull(() -> metadata.system.software);
    if (metadataSoftware == null) {
      systemState.software.put(DEFAULT_SOFTWARE_KEY, DEFAULT_SOFTWARE_VALUE);
    } else {
      systemState.software = metadataSoftware;
    }

    if (options.softwareFirmwareValue != null) {
      systemState.software.put("firmware", options.softwareFirmwareValue);
    }
  }

  @Override
  public SystemManagerProvider.ExtraSystemState getSystemState() {
    return this.systemState;
  }


  @Override
  public Date getDeviceStartTime() {
    return DEVICE_START_TIME;
  }

  @Override
  public void periodicUpdate() {
    sendSystemEvent();
  }

  @Override
  public void systemLifecycle(SystemMode mode) {
    systemState.operation.mode = mode;
    try {
      host.update(host);
    } catch (Exception e) {
      error("Squashing error publishing state while shutting down", e);
    }
    int exitCode = EXIT_CODE_MAP.getOrDefault(mode, UNKNOWN_MODE_EXIT_CODE);
    error("Stopping system with extreme prejudice, restart " + mode + " with code " + exitCode);
    System.exit(exitCode);
  }


  /**
   * Check if we should log at the level provided.
   *
   * @param level the level.
   * @return true if we can log at the level provided.
   */
  @Override
  public boolean shouldLogLevel(int level) {
    if (options.fixedLogLevel != null) {
      return level >= options.fixedLogLevel;
    }

    Integer minLoglevel = ifNotNullGet(systemConfig, config -> systemConfig.min_loglevel);
    return level >= requireNonNullElse(minLoglevel, Level.INFO.value());
  }

  @Override
  public void localLog(String message, Level level, String timestamp, String detail) {
    String detailPostfix = detail == null ? "" : ":\n" + detail;
    String logMessage = format("%s %s%s", timestamp, message, detailPostfix);
    LOG_MAP.get(level).accept(logMessage);
    try {
      PrintStream stream;
      if (host instanceof Pubber pubberHost) {
        stream = pubberHost.logPrintWriter;
      } else if (host instanceof ProxyDevice proxyHost) {
        stream = proxyHost.pubberHost.logPrintWriter;
      } else {
        throw new RuntimeException("While writing log output file: Unknown host");
      }
      stream.println(logMessage);
      stream.flush();
    } catch (Exception e) {
      throw new RuntimeException("While writing log output file", e);
    }
  }



  @Override
  public List<Entry> getLogentries() {
    return logentries;
  }

  @Override
  public boolean getPublishingLog() {
    return publishingLog;
  }

  @Override
  public int getSystemEventCount() {
    return systemEventCount;
  }

  @Override
  public SystemConfig getSystemConfig() {
    return systemConfig;
  }

  @Override
  public void setSystemConfig(SystemConfig systemConfig) {
    this.systemConfig = systemConfig;
  }

  @Override
  public int incrementSystemEventCount() {
    return systemEventCount++;
  }

  @Override
  public void setPublishingLog(boolean publishingLog) {
    this.publishingLog = publishingLog;
  }
}
