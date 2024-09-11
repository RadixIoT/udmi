package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.deepCopy;
import static com.google.udmi.util.GeneralUtils.fromJsonString;
import static com.google.udmi.util.GeneralUtils.getNow;
import static com.google.udmi.util.GeneralUtils.getTimestamp;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static com.google.udmi.util.GeneralUtils.ifTrueThen;
import static com.google.udmi.util.GeneralUtils.isGetTrue;
import static com.google.udmi.util.GeneralUtils.isTrue;
import static com.google.udmi.util.GeneralUtils.stackTraceString;
import static com.google.udmi.util.GeneralUtils.toJsonFile;
import static com.google.udmi.util.GeneralUtils.toJsonString;
import static com.google.udmi.util.JsonUtil.isoConvert;
import static com.google.udmi.util.JsonUtil.parseJson;
import static com.google.udmi.util.JsonUtil.safeSleep;
import static com.google.udmi.util.JsonUtil.stringify;
import static daq.pubber.ManagerBase.WAIT_TIME_SEC;
import static daq.pubber.MqttDevice.CONFIG_TOPIC;
import static daq.pubber.MqttDevice.ERRORS_TOPIC;
import static daq.pubber.MqttDevice.STATE_TOPIC;
import static daq.pubber.SystemManager.LOG_MAP;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static udmi.schema.BlobsetConfig.SystemBlobsets.IOT_ENDPOINT_CONFIG;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.udmi.util.GeneralUtils;
import com.google.udmi.util.MessageDowngrader;
import com.google.udmi.util.SchemaVersion;
import com.google.udmi.util.SiteModel;
import daq.pubber.DeviceManager;
import daq.pubber.GatewayError;
import daq.pubber.ManagerHost;
import daq.pubber.MqttDevice;
import daq.pubber.MqttPublisher.InjectedMessage;
import daq.pubber.MqttPublisher.InjectedState;
import daq.pubber.client.PointsetManagerProvider.ExtraPointsetEvent;
import daq.pubber.client.SystemManagerProvider.ExtraSystemState;
import java.io.File;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import udmi.schema.BlobBlobsetConfig;
import udmi.schema.BlobBlobsetConfig.BlobPhase;
import udmi.schema.BlobBlobsetState;
import udmi.schema.BlobsetConfig.SystemBlobsets;
import udmi.schema.BlobsetState;
import udmi.schema.Category;
import udmi.schema.Config;
import udmi.schema.DevicePersistent;
import udmi.schema.DiscoveryEvents;
import udmi.schema.DiscoveryState;
import udmi.schema.EndpointConfiguration;
import udmi.schema.Entry;
import udmi.schema.Envelope.SubType;
import udmi.schema.GatewayState;
import udmi.schema.Level;
import udmi.schema.LocalnetState;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PointsetEvents;
import udmi.schema.PointsetState;
import udmi.schema.PubberConfiguration;
import udmi.schema.PubberOptions;
import udmi.schema.State;
import udmi.schema.SystemEvents;
import udmi.schema.SystemState;

/**
 * Pubber client.
 */
public interface PubberHost extends ManagerHost {

  static final String DATA_URL_JSON_BASE64 = "data:application/json;base64,";

  static final String BROKEN_VERSION = "1.4.";
  static final String UDMI_VERSION = SchemaVersion.CURRENT.key();
  static final Duration SMOKE_CHECK_TIME = Duration.ofMinutes(5);
  static final ImmutableMap<Class<?>, String> MESSAGE_TOPIC_SUFFIX_MAP =
      new Builder<Class<?>, String>()
          .put(State.class, STATE_TOPIC)
          .put(ExtraSystemState.class, STATE_TOPIC) // Used for badState option
          .put(SystemEvents.class, PubberHost.getEventsSuffix("system"))
          .put(PointsetEvents.class, PubberHost.getEventsSuffix("pointset"))
          .put(ExtraPointsetEvent.class, PubberHost.getEventsSuffix("pointset"))
          .put(InjectedMessage.class, PubberHost.getEventsSuffix("racoon"))
          .put(InjectedState.class, STATE_TOPIC)
          .put(DiscoveryEvents.class, PubberHost.getEventsSuffix("discovery"))
          .build();

  static final String SYSTEM_CATEGORY_FORMAT = "system.%s.%s";
  static final int STATE_THROTTLE_MS = 2000;
  static final int FORCED_STATE_TIME_MS = 10000;
  static final int DEFAULT_REPORT_SEC = 10;

  State getDeviceState();
  Config getDeviceConfig();

  DeviceManager getDeviceManager();
  MqttDevice getDeviceTarget();
  void setDeviceTarget(MqttDevice deviceTarget);

  boolean isGatewayDevice();
  
  static String getEventsSuffix(String suffixSuffix) {
    return MqttDevice.EVENTS_TOPIC + "/" + suffixSuffix;
  }

  static Date getRoundedStartTime() {
    long timestamp = getNow().getTime();
    // Remove ms so that rounded conversions preserve equality.
    return new Date(timestamp - (timestamp % 1000));
  }

  static String acquireBlobData(String url, String sha256) {
    if (!url.startsWith(DATA_URL_JSON_BASE64)) {
      throw new RuntimeException("URL encoding not supported: " + url);
    }
    byte[] dataBytes = Base64.getDecoder().decode(url.substring(DATA_URL_JSON_BASE64.length()));
    String dataSha256 = GeneralUtils.sha256(dataBytes);
    if (!dataSha256.equals(sha256)) {
      throw new RuntimeException("Blob data hash mismatch");
    }
    return new String(dataBytes);
  }

  static void augmentDeviceMessage(Object message, Date now, boolean useBadVersion) {
    try {
      Field version = message.getClass().getField("version");
      version.set(message, useBadVersion ? BROKEN_VERSION : UDMI_VERSION);
      Field timestamp = message.getClass().getField("timestamp");
      timestamp.set(message, now);
    } catch (Throwable e) {
      throw new RuntimeException("While augmenting device message", e);
    }
  }

  static String getGatewayId(String targetId, PubberConfiguration configuration) {
    return ofNullable(configuration.gatewayId).orElse(
        targetId.equals(configuration.deviceId) ? null : configuration.deviceId);
  }

  default DevicePersistent newDevicePersistent() {
    return new DevicePersistent();
  }

  default void markStateDirty() {
    markStateDirty(0);
  }

  @Override
  default void update(Object update) {
    requireNonNull(update, "null update message");
    boolean markerClass = update instanceof Class<?>;
    final Object checkValue = markerClass ? null : update;
    final Object checkTarget;
    try {
      checkTarget = markerClass ? ((Class<?>) update).getConstructor().newInstance() : update;
    } catch (Exception e) {
      throw new RuntimeException("Could not create marker instance of class " + update.getClass());
    }
    if (checkTarget == this) {
      publishSynchronousState();
    } else if (checkTarget instanceof SystemState) {
      getDeviceState().system = (SystemState) checkValue;
      ifTrueThen(getOptions().dupeState, this::sendDupeState);
    } else if (checkTarget instanceof PointsetState) {
      getDeviceState().pointset = (PointsetState) checkValue;
    } else if (checkTarget instanceof LocalnetState) {
      getDeviceState().localnet = (LocalnetState) checkValue;
    } else if (checkTarget instanceof GatewayState) {
      getDeviceState().gateway = (GatewayState) checkValue;
    } else if (checkTarget instanceof DiscoveryState) {
      getDeviceState().discovery = (DiscoveryState) checkValue;
    } else {
      throw new RuntimeException(
          "Unrecognized update type " + checkTarget.getClass().getSimpleName());
    }
    markStateDirty();
  }

  private void sendDupeState() {
    State dupeState = new State();
    dupeState.system = getDeviceState().system;
    dupeState.timestamp = getDeviceState().timestamp;
    dupeState.version = getDeviceState().version;
    publishStateMessage(dupeState);
  }

  @Override
  default void publish(Object message) {
    publishDeviceMessage(message);
  }

  default void publish(String targetId, Object message) {
    publishDeviceMessage(targetId, message);
  }


  default void captureExceptions(String action, Runnable runnable) {
    try {
      runnable.run();
    } catch (Exception e) {
      error(action, e);
    }
  }

  default void disconnectMqtt() {
    if (getDeviceTarget() != null) {
      captureExceptions("closing mqtt publisher", () -> getDeviceTarget().close());
      captureExceptions("shutting down mqtt publisher executor", () -> getDeviceTarget().shutdown());
      setDeviceTarget(null);
    }
  }

  default void registerMessageHandlers() {
    getDeviceTarget().registerHandler(CONFIG_TOPIC, this::configHandler, Config.class);
    String gatewayId = getGatewayId(getDeviceId(), getConfig());
    if (isGatewayDevice()) {
      // In this case, this is the gateway so register the appropriate error handler directly.
      getDeviceTarget().registerHandler(ERRORS_TOPIC, this::errorHandler, GatewayError.class);
    } else if (gatewayId != null) {
      // In this case, this is a proxy device with a gateway, so register handlers accordingly.
      MqttDevice gatewayTarget = new MqttDevice(gatewayId, getDeviceTarget());
      gatewayTarget.registerHandler(CONFIG_TOPIC, this::gatewayHandler, Config.class);
      gatewayTarget.registerHandler(ERRORS_TOPIC, this::errorHandler, GatewayError.class);
    }
  }


  default MqttDevice getMqttDevice(String proxyId) {
    return new MqttDevice(proxyId, getDeviceTarget());
  }


  default void connect() {
    try {
      warn("Creating new config latch for " + getDeviceId());
      setConfigLatch(new CountDownLatch(1));
      getDeviceTarget().connect();
      info("Connection complete.");
      setWorkingEndpoint(toJsonString(getConfig().endpoint));
    } catch (Exception e) {
      throw new RuntimeException("Connection error", e);
    }
  }

  void setWorkingEndpoint(String jsonString);

  void setConfigLatch(CountDownLatch countDownLatch);

  default void publisherConfigLog(String phase, Exception e, String targetId) {
    publisherHandler("config", phase, e, targetId);
  }

  default void publisherHandler(String type, String phase, Throwable cause, String targetId) {
    if (cause != null) {
      error("Error receiving message " + type, cause);
      if (isTrue(getConfig().options.barfConfig)) {
        error("Restarting system because of restart-on-error configuration setting");
        getDeviceManager().systemLifecycle(SystemMode.RESTART);
      }
    }
    String usePhase = isTrue(getOptions().badCategory) ? "apply" : phase;
    String category = format(SYSTEM_CATEGORY_FORMAT, type, usePhase);
    Entry report = entryFromException(category, cause);
    getDeviceManager().localLog(report);
    publishLogMessage(report, targetId);
    ifTrueThen(getDeviceId().equals(targetId), () -> registerSystemStatus(report));
  }

  void error(String s);

  default void registerSystemStatus(Entry report) {
    getDeviceState().system.status = report;
    markStateDirty();
  }

  /**
   * Issue a state update in response to a received config message. This will optionally add a
   * synthetic delay in so that testing infrastructure can test that related sequence tests handle
   * this case appropriately.
   */
  default void publishConfigStateUpdate() {
    if (isTrue(getConfig().options.configStateDelay)) {
      delayNextStateUpdate();
    }
    publishAsynchronousState();
  }

  default void delayNextStateUpdate() {
    // Calculate a synthetic last state time that factors in the optional delay.
    long syntheticType = System.currentTimeMillis() - STATE_THROTTLE_MS + FORCED_STATE_TIME_MS;
    // And use the synthetic time iff it's later than the actual last state time.
    setLastStateTimeMs(Math.max(getLastStateTimeMs(), syntheticType));
  }

  void setLastStateTimeMs(long lastStateTimeMs);

  long getLastStateTimeMs();

  default Entry entryFromException(String category, Throwable e) {
    boolean success = e == null;
    Entry entry = new Entry();
    entry.category = category;
    entry.timestamp = getNow();
    entry.message = success ? "success"
        : e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    entry.detail = success ? null : exceptionDetail(e);
    Level successLevel = Category.LEVEL.computeIfAbsent(category, key -> Level.INFO);
    entry.level = (success ? successLevel : Level.ERROR).value();
    return entry;
  }

  private String exceptionDetail(Throwable e) {
    StringBuilder buffer = new StringBuilder();
    while (e != null) {
      buffer.append(e).append(';');
      e = e.getCause();
    }
    return buffer.toString();
  }

  private void configHandler(Config config) {
    try {
      configPreprocess(getDeviceId(), config);
      debug(format("Config update %s%s", getDeviceId(), getDeviceManager().getTestingTag()),
          toJsonString(config));
      processConfigUpdate(config);
      if (getConfigLatch().getCount() > 0) {
        warn("Received config for config latch " + getDeviceId());
        getConfigLatch().countDown();
      }
      publisherConfigLog("apply", null, getDeviceId());
    } catch (Exception e) {
      publisherConfigLog("apply", e, getDeviceId());
    }
    publishConfigStateUpdate();
  }

  CountDownLatch getConfigLatch();

  private void gatewayHandler(Config gatewayConfig) {
    warn("Ignoring configuration for gateway " + getGatewayId(getDeviceId(), getConfig()));
  }

  private void errorHandler(GatewayError error) {
    warn(format("%s for %s: %s", error.error_type, error.device_id, error.description));
  }

  default void configPreprocess(String targetId, Config configMsg) {
    String gatewayId = getGatewayId(targetId, getConfig());
    String suffix = ifNotNullGet(gatewayId, x -> "_" + targetId, "");
    String deviceType = ifNotNullGet(gatewayId, x -> "Proxy", "Device");
    info(format("%s %s config handler", deviceType, targetId));
    File configOut = new File(getOutDir(), format("%s.json", traceTimestamp("config" + suffix)));
    toJsonFile(configOut, configMsg);
  }

  File getOutDir();

  private void processConfigUpdate(Config configMsg) {
    try {
      // Grab this to make state-after-config updates monolithic.
      getStateLock().lock();
    } catch (Exception e) {
      throw new RuntimeException("While acquiring state lock", e);
    }

    try {
      if (configMsg != null) {
        if (configMsg.system == null && isTrue(getConfig().options.barfConfig)) {
          error("Empty config system block and configured to restart on bad config!");
          getDeviceManager().systemLifecycle(SystemMode.RESTART);
        }
        GeneralUtils.copyFields(configMsg, getDeviceConfig(), true);
        info(format("%s received config %s", getTimestamp(), isoConvert(configMsg.timestamp)));
        getDeviceManager().updateConfig(configMsg);
        extractEndpointBlobConfig();
      } else {
        info(getTimestamp() + " defaulting empty config");
      }
      updateInterval(DEFAULT_REPORT_SEC);
    } finally {
      getStateLock().unlock();
    }
  }

  void updateInterval(Integer defaultReportSec);
  Lock getStateLock();

  // TODO: Consider refactoring this to either return or change an instance variable, not both.
  default EndpointConfiguration extractEndpointBlobConfig() {
    setExtractedEndpoint(null);
    if (getDeviceConfig().blobset == null) {
      return null;
    }
    try {
      String iotConfig = extractConfigBlob(IOT_ENDPOINT_CONFIG.value());
      setExtractedEndpoint(fromJsonString(iotConfig, EndpointConfiguration.class));
      if (getExtractedEndpoint() != null) {
        if (getDeviceConfig().blobset.blobs.containsKey(IOT_ENDPOINT_CONFIG.value())) {
          BlobBlobsetConfig config = getDeviceConfig().blobset.blobs.get(IOT_ENDPOINT_CONFIG.value());
          getExtractedEndpoint().generation = config.generation;
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("While extracting endpoint blob config", e);
    }
    return getExtractedEndpoint();
  }

  EndpointConfiguration getExtractedEndpoint();

  void setExtractedEndpoint(EndpointConfiguration endpointConfiguration);

  private void removeBlobsetBlobState(SystemBlobsets blobId) {
    if (getDeviceState().blobset == null) {
      return;
    }

    if (getDeviceState().blobset.blobs.remove(blobId.value()) == null) {
      return;
    }

    if (getDeviceState().blobset.blobs.isEmpty()) {
      getDeviceState().blobset = null;
    }

    markStateDirty();
  }

  default void maybeRedirectEndpoint() {
    String redirectRegistry = getConfig().options.redirectRegistry;
    String currentSignature = toJsonString(getConfig().endpoint);
    String extractedSignature =
        redirectRegistry == null ? toJsonString(getExtractedEndpoint())
            : redirectedEndpoint(redirectRegistry);

    if (extractedSignature == null) {
      setAttemptedEndpoint(null);
      removeBlobsetBlobState(IOT_ENDPOINT_CONFIG);
      return;
    }

    BlobBlobsetState endpointState = ensureBlobsetState(IOT_ENDPOINT_CONFIG);

    if (extractedSignature.equals(currentSignature)
        || extractedSignature.equals(getAttemptedEndpoint())) {
      return; // No need to redirect anything!
    }

    if (getExtractedEndpoint() != null) {
      if (!Objects.equals(endpointState.generation, getExtractedEndpoint().generation)) {
        notice("Starting new endpoint generation");
        endpointState.phase = null;
        endpointState.status = null;
        endpointState.generation = getExtractedEndpoint().generation;
      }

      if (getExtractedEndpoint().error != null) {
        setAttemptedEndpoint(extractedSignature);
        endpointState.phase = BlobPhase.FINAL;
        Exception applyError = new RuntimeException(getExtractedEndpoint().error);
        endpointState.status = exceptionStatus(applyError, Category.BLOBSET_BLOB_APPLY);
        publishSynchronousState();
        return;
      }
    }

    info("New config blob endpoint detected:\n" + stringify(parseJson(extractedSignature)));

    try {
      setAttemptedEndpoint(extractedSignature);
      endpointState.phase = BlobPhase.APPLY;
      publishSynchronousState();
      resetConnection(extractedSignature);
      persistEndpoint(getExtractedEndpoint());
      endpointState.phase = BlobPhase.FINAL;
      markStateDirty();
    } catch (Exception e) {
      try {
        error("Reconfigure failed, attempting connection to last working endpoint", e);
        endpointState.phase = BlobPhase.FINAL;
        endpointState.status = exceptionStatus(e, Category.BLOBSET_BLOB_APPLY);
        resetConnection(getWorkingEndpoint());
        publishAsynchronousState();
        notice("Endpoint connection restored to last working endpoint");
      } catch (Exception e2) {
        throw new RuntimeException("While restoring working endpoint", e2);
      }
      error("While redirecting connection endpoint", e);
    }
  }

  String getWorkingEndpoint();

  void setAttemptedEndpoint(String s);

  String getAttemptedEndpoint();

  void notice(String startingNewEndpointGeneration);


  private String redirectedEndpoint(String redirectRegistry) {
    try {
      EndpointConfiguration endpoint = deepCopy(getConfig().endpoint);
      endpoint.client_id = getClientId(redirectRegistry);
      return toJsonString(endpoint);
    } catch (Exception e) {
      throw new RuntimeException("While getting redirected endpoint", e);
    }
  }


  default Entry exceptionStatus(Exception e, String category) {
    Entry entry = new Entry();
    entry.message = e.getMessage();
    entry.detail = stackTraceString(e);
    entry.category = category;
    entry.level = Level.ERROR.value();
    entry.timestamp = getNow();
    return entry;
  }

  default BlobBlobsetState ensureBlobsetState(SystemBlobsets iotEndpointConfig) {
    getDeviceState().blobset = ofNullable(getDeviceState().blobset).orElseGet(BlobsetState::new);
    getDeviceState().blobset.blobs = ofNullable(getDeviceState().blobset.blobs).orElseGet(HashMap::new);
    return getDeviceState().blobset.blobs.computeIfAbsent(iotEndpointConfig.value(),
        key -> new BlobBlobsetState());
  }

  default String getClientId(String forRegistry) {
    String cloudRegion = SiteModel.parseClientId(getConfig().endpoint.client_id).cloudRegion;
    return SiteModel.getClientId(getConfig().iotProject, cloudRegion, forRegistry, getDeviceId());
  }

  default String extractConfigBlob(String blobName) {
    // TODO: Refactor to get any blob meta parameters.
    try {
      if (getDeviceConfig() == null || getDeviceConfig().blobset == null
          || getDeviceConfig().blobset.blobs == null) {
        return null;
      }
      BlobBlobsetConfig blobBlobsetConfig = getDeviceConfig().blobset.blobs.get(blobName);
      if (blobBlobsetConfig != null && BlobPhase.FINAL.equals(blobBlobsetConfig.phase)) {
        return acquireBlobData(blobBlobsetConfig.url, blobBlobsetConfig.sha256);
      }
      return null;
    } catch (Exception e) {
      EndpointConfiguration endpointConfiguration = new EndpointConfiguration();
      endpointConfiguration.error = e.toString();
      return stringify(endpointConfiguration);
    }
  }

  default void publishLogMessage(Entry logEntry, String targetId) {
    getDeviceManager().publishLogMessage(logEntry, targetId);
  }

  default void publishAsynchronousState() {
    if (getStateLock().tryLock()) {
      try {
        long soonestAllowedStateUpdate = getLastStateTimeMs() + STATE_THROTTLE_MS;
        long delay = soonestAllowedStateUpdate - System.currentTimeMillis();
        debug(format("State update defer %dms", delay));
        if (delay > 0) {
          markStateDirty(delay);
        } else {
          publishStateMessage();
        }
      } finally {
        getStateLock().unlock();
      }
    } else {
      markStateDirty(-1);
    }
  }

  default void publishSynchronousState() {
    try {
      getStateLock().lock();
      publishStateMessage();
    } catch (Exception e) {
      throw new RuntimeException("While sending synchronous state", e);
    } finally {
      getStateLock().unlock();
    }
  }

  default boolean publisherActive() {
    return getDeviceTarget() != null && getDeviceTarget().isActive();
  }

  default void publishStateMessage() {
    if (!publisherActive()) {
      markStateDirty(-1);
      return;
    }
    getStateDirty().set(false);
    getDeviceState().timestamp = getNow();
    info(format("Update state %s last_config %s", isoConvert(getDeviceState().timestamp),
        isoConvert(getDeviceState().system.last_config)));
    publishStateMessage(isTrue(getOptions().badState) ? getDeviceState().system : getDeviceState());
  }

  AtomicBoolean getStateDirty();

  default void publishStateMessage(Object stateToSend) {
    try {
      getStateLock().lock();
      publishStateMessageRaw(stateToSend);
    } finally {
      getStateLock().unlock();
    }
  }

  default void publishStateMessageRaw(Object stateToSend) {
    if (getConfigLatch() == null || getConfigLatch().getCount() > 0) {
      warn("Dropping state update until config received...");
      return;
    }

    long delay = getLastStateTimeMs() + STATE_THROTTLE_MS - System.currentTimeMillis();
    if (delay > 0) {
      warn(format("State update delay %dms", delay));
      safeSleep(delay);
    }

    setLastStateTimeMs(System.currentTimeMillis());
    CountDownLatch latch = new CountDownLatch(1);

    try {
      debug(format("State update %s%s", getDeviceId(), getDeviceManager().getTestingTag()),
          toJsonString(stateToSend));
    } catch (Exception e) {
      throw new RuntimeException("While converting new device state", e);
    }

    publishDeviceMessage(getDeviceId(), stateToSend, () -> {
      setLastStateTimeMs(System.currentTimeMillis());
      latch.countDown();
    });
    try {
      if (shouldSendState() && !latch.await(WAIT_TIME_SEC, TimeUnit.SECONDS)) {
        throw new RuntimeException("Timeout waiting for state send");
      }
    } catch (Exception e) {
      throw new RuntimeException(format("While waiting for %s state send latch", getDeviceId()), e);
    }
  }

  default boolean shouldSendState() {
    return !isGetTrue(() -> getConfig().options.noState);
  }

  default void publishDeviceMessage(Object message) {
    publishDeviceMessage(getDeviceId(), message);
  }

  private void publishDeviceMessage(String targetId, Object message) {
    publishDeviceMessage(targetId, message, null);
  }

  default void publishDeviceMessage(String targetId, Object message, Runnable callback) {
    String topicSuffix = MESSAGE_TOPIC_SUFFIX_MAP.get(message.getClass());
    if (topicSuffix == null) {
      error("Unknown message class " + message.getClass());
      return;
    }

    if (!shouldSendState() && topicSuffix.equals(STATE_TOPIC)) {
      warn("Squelching state update as per configuration");
      return;
    }

    if (getDeviceTarget() == null) {
      error("publisher not active");
      return;
    }

    augmentDeviceMessage(message, getNow(), isTrue(getOptions().badVersion));
    Object downgraded = downgradeMessage(message);
    getDeviceTarget().publish(targetId, topicSuffix, downgraded, callback);
    String messageBase = topicSuffix.replace("/", "_");
    String gatewayId = getGatewayId(targetId, getConfig());
    String suffix = ifNotNullGet(gatewayId, x -> "_" + targetId, "");
    File messageOut = new File(getOutDir(), format("%s.json", traceTimestamp(messageBase + suffix)));
    try {
      toJsonFile(messageOut, downgraded);
    } catch (Exception e) {
      throw new RuntimeException("While writing " + messageOut.getAbsolutePath(), e);
    }
  }

  private Object downgradeMessage(Object message) {
    MessageDowngrader messageDowngrader = new MessageDowngrader(SubType.STATE.value(), message);
    return ifNotNullGet(getTargetSchema(), messageDowngrader::downgrade, message);
  }

  SchemaVersion getTargetSchema();

  private void cloudLog(String message, Level level) {
    cloudLog(message, level, null);
  }

  private void cloudLog(String message, Level level, String detail) {
    if (getDeviceManager() != null) {
      getDeviceManager().cloudLog(message, level, detail);
    } else {
      String detailPostfix = detail == null ? "" : ":\n" + detail;
      String logMessage = format("%s%s", message, detailPostfix);
      LOG_MAP.get(level).accept(logMessage);
    }
  }

  default void debug(String message, String detail) {
    cloudLog(message, Level.DEBUG, detail);
  }

  void initializeDevice();

  void initializeMqtt();

  void initializePersistentStore();

  void writePersistentStore();

  void markStateDirty(long delayMs);

  void startConnection(Function<String, Boolean> connectionDone);

  byte[] ensureKeyBytes();

  void publisherException(Exception toReport);

  void persistEndpoint(EndpointConfiguration endpoint);

  void resetConnection(String targetEndpoint);

  String traceTimestamp(String messageBase);

  void shutdown();
  
  
  PubberOptions getOptions();
  PubberConfiguration getConfig();
  String getDeviceId();
}
