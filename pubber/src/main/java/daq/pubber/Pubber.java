package daq.pubber;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.udmi.util.GeneralUtils.catchToFalse;
import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.deepCopy;
import static com.google.udmi.util.GeneralUtils.friendlyStackTrace;
import static com.google.udmi.util.GeneralUtils.fromJsonFile;
import static com.google.udmi.util.GeneralUtils.fromJsonString;
import static com.google.udmi.util.GeneralUtils.getFileBytes;
import static com.google.udmi.util.GeneralUtils.getNow;
import static com.google.udmi.util.GeneralUtils.getTimestamp;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static com.google.udmi.util.GeneralUtils.ifTrueThen;
import static com.google.udmi.util.GeneralUtils.isGetTrue;
import static com.google.udmi.util.GeneralUtils.isTrue;
import static com.google.udmi.util.GeneralUtils.optionsString;
import static com.google.udmi.util.GeneralUtils.setClockSkew;
import static com.google.udmi.util.GeneralUtils.sha256;
import static com.google.udmi.util.GeneralUtils.stackTraceString;
import static com.google.udmi.util.GeneralUtils.toJsonFile;
import static com.google.udmi.util.GeneralUtils.toJsonString;
import static com.google.udmi.util.JsonUtil.isoConvert;
import static com.google.udmi.util.JsonUtil.parseJson;
import static com.google.udmi.util.JsonUtil.safeSleep;
import static com.google.udmi.util.JsonUtil.stringify;
import static daq.pubber.MqttDevice.CONFIG_TOPIC;
import static daq.pubber.MqttDevice.ERRORS_TOPIC;
import static daq.pubber.MqttDevice.STATE_TOPIC;
import static daq.pubber.MqttPublisher.DEFAULT_CONFIG_WAIT_SEC;
import static daq.pubber.SystemManager.LOG_MAP;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;
import static udmi.schema.BlobsetConfig.SystemBlobsets.IOT_ENDPOINT_CONFIG;
import static udmi.schema.EndpointConfiguration.Protocol.MQTT;

import com.google.common.collect.ImmutableMap;
import com.google.daq.mqtt.util.CatchingScheduledThreadPoolExecutor;
import com.google.udmi.util.CertManager;
import com.google.udmi.util.SchemaVersion;
import com.google.udmi.util.SiteModel;
import com.google.udmi.util.SiteModel.MetadataException;
import daq.pubber.MqttPublisher.InjectedMessage;
import daq.pubber.MqttPublisher.InjectedState;
import daq.pubber.MqttPublisher.PublisherException;
import daq.pubber.PubSubClient.Bundle;
import daq.pubber.client.PubberHost;
import java.io.File;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.apache.http.ConnectionClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udmi.schema.CloudModel.Auth_type;
import udmi.schema.Config;
import udmi.schema.DevicePersistent;
import udmi.schema.EndpointConfiguration;
import udmi.schema.EndpointConfiguration.Protocol;
import udmi.schema.Envelope;
import udmi.schema.Envelope.SubFolder;
import udmi.schema.Level;
import udmi.schema.Metadata;
import udmi.schema.Operation.SystemMode;
import udmi.schema.PubberConfiguration;
import udmi.schema.PubberOptions;
import udmi.schema.State;

/**
 * IoT Core UDMI Device Emulator.
 */
public class Pubber extends ManagerBase implements PubberHost {

  public static final String PUBBER_OUT = "pubber/out";
  public static final String PERSISTENT_STORE_FILE = "persistent_data.json";
  public static final String PERSISTENT_TMP_FORMAT = "/tmp/pubber_%s_" + PERSISTENT_STORE_FILE;
  public static final String CA_CRT = "ca.crt";
  static final Logger LOG = LoggerFactory.getLogger(Pubber.class);
  static final Date DEVICE_START_TIME = PubberHost.getRoundedStartTime();
  static final int MESSAGE_REPORT_INTERVAL = 10;
  private static final String HOSTNAME = System.getenv("HOSTNAME");
  private static final String PUBSUB_SITE = "PubSub";

  private static final Map<String, String> INVALID_REPLACEMENTS = ImmutableMap.of(
      "events/blobset", "\"\"",
      "events/discovery", "{}",
      "events/mapping", "{ NOT VALID JSON!"
  );
  public static final List<String> INVALID_KEYS = new ArrayList<>(INVALID_REPLACEMENTS.keySet());
  private static final Map<String, AtomicInteger> MESSAGE_COUNTS = new HashMap<>();
  private static final int CONNECT_RETRIES = 10;
  private static final AtomicInteger retriesRemaining = new AtomicInteger(CONNECT_RETRIES);
  private static final long RESTART_DELAY_MS = 1000;
  private static final String CORRUPT_STATE_MESSAGE = "!&*@(!*&@!";
  private static final long INJECT_MESSAGE_DELAY_MS = 2000; // Delay to make sure testing is stable.
  private static final Duration CLOCK_SKEW = Duration.ofMinutes(30);
  private static final int STATE_SPAM_SEC = 5; // Expected config-state response time.
  final State deviceState = new State();
  final Config deviceConfig = new Config();
  private final File outDir;
  private final ScheduledExecutorService executor = new CatchingScheduledThreadPoolExecutor(1);
  private final AtomicBoolean stateDirty = new AtomicBoolean();
  private final ReentrantLock stateLock = new ReentrantLock();
  public PrintStream logPrintWriter;
  protected DevicePersistent persistentData;
  private CountDownLatch configLatch;
  private MqttDevice deviceTarget;
  private long lastStateTimeMs;
  private PubSubClient pubSubClient;
  private Function<String, Boolean> connectionDone;
  private String workingEndpoint;
  private String attemptedEndpoint;
  private EndpointConfiguration extractedEndpoint;
  private SiteModel siteModel;
  private SchemaVersion targetSchema;
  private int deviceUpdateCount = -1;
  private DeviceManager deviceManager;
  private boolean isConnected;
  private boolean isGatewayDevice;

  /**
   * Start an instance from a configuration file.
   *
   * @param configPath Path to configuration file.
   */
  public Pubber(String configPath) {
    super(null, loadConfiguration(configPath));
    setClockSkew(isTrue(options.skewClock) ? CLOCK_SKEW : Duration.ZERO);
    Protocol protocol = requireNonNullElse(
        ifNotNullGet(config.endpoint, endpoint -> endpoint.protocol), MQTT);
    checkArgument(MQTT.equals(protocol), "protocol mismatch");
    outDir = new File(PUBBER_OUT);
    ifTrueThen(options.spamState, () -> schedulePeriodic(STATE_SPAM_SEC, this::markStateDirty));
  }

  /**
   * Start an instance from explicit args.
   *
   * @param iotProject GCP project
   * @param sitePath   Path to site_model
   * @param deviceId   Device ID to emulate
   * @param serialNo   Serial number of the device
   */
  public Pubber(String iotProject, String sitePath, String deviceId, String serialNo) {
    super(null, makeExplicitConfiguration(iotProject, sitePath, deviceId, serialNo));
    outDir = new File(PUBBER_OUT + "/" + serialNo);
    if (!outDir.exists()) {
      checkState(outDir.mkdirs(), "could not make out dir " + outDir.getAbsolutePath());
    }
    if (PUBSUB_SITE.equals(sitePath)) {
      pubSubClient = new PubSubClient(iotProject, deviceId);
    }
  }

  private static PubberConfiguration loadConfiguration(String configPath) {
    File configFile = new File(configPath);
    try {
      return sanitizeConfiguration(fromJsonFile(configFile, PubberConfiguration.class));
    } catch (Exception e) {
      throw new RuntimeException("While configuring from " + configFile.getAbsolutePath(), e);
    }
  }

  private static PubberConfiguration makeExplicitConfiguration(String iotProject, String sitePath,
      String deviceId, String serialNo) {
    PubberConfiguration configuration = new PubberConfiguration();
    configuration.iotProject = iotProject;
    configuration.sitePath = sitePath;
    configuration.deviceId = deviceId;
    configuration.serialNo = serialNo;
    configuration.options = new PubberOptions();
    return configuration;
  }

  /**
   * Start a pubber instance with command line args.
   *
   * @param args The usual
   * @throws Exception When something is wrong...
   */
  public static void main(String[] args) throws Exception {
    try {
      boolean swarm = args.length > 1 && PUBSUB_SITE.equals(args[1]);
      if (swarm) {
        swarmPubber(args);
      } else {
        singularPubber(args);
      }
      LOG.info("Done with main");
    } catch (Exception e) {
      LOG.error("Exception starting pubber: " + friendlyStackTrace(e));
      e.printStackTrace();
      System.exit(-1);
    }
  }

  static Pubber singularPubber(String[] args) {
    Pubber pubber = null;
    try {
      if (args.length == 1) {
        pubber = new Pubber(args[0]);
      } else if (args.length == 4) {
        pubber = new Pubber(args[0], args[1], args[2], args[3]);
      } else {
        throw new IllegalArgumentException(
            "Usage: config_file or { project_id site_path/ device_id serial_no }");
      }
      pubber.initialize();
      pubber.startConnection(deviceId -> {
        LOG.info(format("Connection closed/finished for %s", deviceId));
        return true;
      });
    } catch (Exception e) {
      if (pubber != null) {
        pubber.shutdown();
      }
      throw new RuntimeException("While starting singular pubber", e);
    }
    return pubber;
  }

  private static void swarmPubber(String[] args) throws InterruptedException {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "Usage: { project_id PubSub pubsub_subscription instance_count }");
    }
    String projectId = args[0];
    String siteName = args[1];
    String feedName = args[2];
    int instances = Integer.parseInt(args[3]);
    LOG.info(format("Starting %d pubber instances", instances));
    for (int instance = 0; instance < instances; instance++) {
      String serialNo = format("%s-%d", HOSTNAME, (instance + 1));
      startFeedListener(projectId, siteName, feedName, serialNo);
    }
    LOG.info(format("Started all %d pubber instances", instances));
  }

  private static void startFeedListener(String projectId, String siteName, String feedName,
      String serialNo) {
    Pubber pubber = new Pubber(projectId, siteName, feedName, serialNo);
    try {
      LOG.info("Starting feed listener " + serialNo);
      pubber.initialize();
      pubber.startConnection(deviceId -> {
        LOG.error("Connection terminated, restarting listener");
        startFeedListener(projectId, siteName, feedName, serialNo);
        return false;
      });
      pubber.shutdown();
    } catch (Exception e) {
      LOG.error("Exception starting instance " + serialNo, e);
      pubber.shutdown();
      startFeedListener(projectId, siteName, feedName, serialNo);
    }
  }

  private static PubberConfiguration sanitizeConfiguration(PubberConfiguration configuration) {
    if (configuration.options == null) {
      configuration.options = new PubberOptions();
    }
    return configuration;
  }

  @Override
  public FamilyProvider getLocalnetProvider(String family) {
    return deviceManager.getLocalnetProvider(family);
  }

  @Override
  public void initializeDevice() {
    deviceManager = new DeviceManager(this, config);

    if (config.sitePath != null) {
      SupportedFeatures.writeFeatureFile(config.sitePath, deviceManager);
      siteModel = new SiteModel(config.sitePath);
      siteModel.initialize();
      if (config.endpoint == null) {
        config.endpoint = siteModel.makeEndpointConfig(config.iotProject, deviceId);
      }
      if (!siteModel.allDeviceIds().contains(config.deviceId)) {
        throw new IllegalArgumentException(
            "Device ID " + config.deviceId + " not found in site model");
      }
      Metadata metadata = siteModel.getMetadata(config.deviceId);
      processDeviceMetadata(metadata);
      deviceManager.setSiteModel(siteModel);
    } else if (pubSubClient != null) {
      pullDeviceMessage();
    }

    SupportedFeatures.setFeatureSwap(config.options.featureEnableSwap);
    initializePersistentStore();

    info(format("Starting pubber %s, serial %s, mac %s, gateway %s, options %s",
        config.deviceId, config.serialNo, config.macAddr,
        config.gatewayId, optionsString(config.options)));

    markStateDirty();
  }

  @Override
  public void initializePersistentStore() {
    checkState(persistentData == null, "persistent data already loaded");
    File persistentStore = getPersistentStore();

    if (isTrue(config.options.noPersist)) {
      info("Resetting persistent store " + persistentStore.getAbsolutePath());
      persistentData = newDevicePersistent();
    } else {
      info("Initializing from persistent store " + persistentStore.getAbsolutePath());
      persistentData =
          persistentStore.exists() ? fromJsonFile(persistentStore, DevicePersistent.class)
              : newDevicePersistent();
    }

    persistentData.restart_count = requireNonNullElse(persistentData.restart_count, 0) + 1;

    // If the persistentData contains endpoint configuration, prioritize using that.
    // Otherwise, use the endpoint configuration that came from the Pubber config file on start.
    if (persistentData.endpoint != null) {
      info("Loading endpoint from persistent data");
      config.endpoint = persistentData.endpoint;
    } else if (config.endpoint != null) {
      info("Loading endpoint into persistent data from configuration");
      persistentData.endpoint = config.endpoint;
    } else {
      error(
          "Neither configuration nor persistent data supplies endpoint configuration");
    }

    writePersistentStore();
  }

  @Override
  public void writePersistentStore() {
    checkState(persistentData != null, "persistent data not defined");
    toJsonFile(getPersistentStore(), persistentData);
    warn("Updating persistent store:\n" + stringify(persistentData));
    deviceManager.setPersistentData(persistentData);
  }

  private File getPersistentStore() {
    return siteModel == null ? new File(format(PERSISTENT_TMP_FORMAT, deviceId)) :
        new File(siteModel.getDeviceWorkingDir(deviceId), PERSISTENT_STORE_FILE);
  }

  private void markStateDirty(Runnable action) {
    action.run();
    markStateDirty();
  }


  @Override
  public void markStateDirty(long delayMs) {
    stateDirty.set(true);
    if (delayMs >= 0) {
      try {
        executor.schedule(this::flushDirtyState, delayMs, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        System.err.println("Rejecting state publish after " + delayMs + " " + e);
      }
    }
  }

  private void publishDirtyState() {
    if (stateDirty.get()) {
      debug("Publishing dirty state block");
      markStateDirty(0);
    }
  }

  private void pullDeviceMessage() {
    while (true) {
      try {
        info("Waiting for swarm configuration");
        Envelope attributes = new Envelope();
        Bundle pull = pubSubClient.pull();
        attributes.subFolder = SubFolder.valueOf(pull.attributes.get("subFolder"));
        if (!SubFolder.SWARM.equals(attributes.subFolder)) {
          error("Ignoring message with subFolder " + attributes.subFolder);
          continue;
        }
        attributes.deviceId = pull.attributes.get("deviceId");
        attributes.deviceRegistryId = pull.attributes.get("deviceRegistryId");
        attributes.deviceRegistryLocation = pull.attributes.get("deviceRegistryLocation");
        SwarmMessage swarm = fromJsonString(pull.body, SwarmMessage.class);
        processSwarmConfig(swarm, attributes);
        return;
      } catch (Exception e) {
        error("Error pulling swarm message", e);
        safeSleep(WAIT_TIME_SEC);
      }
    }
  }

  private void processSwarmConfig(SwarmMessage swarm, Envelope attributes) {
    config.deviceId = checkNotNull(attributes.deviceId, "deviceId");
    config.keyBytes = Base64.getDecoder()
        .decode(checkNotNull(swarm.key_base64, "key_base64"));
    config.endpoint = SiteModel.makeEndpointConfig(attributes);
    processDeviceMetadata(
        checkNotNull(swarm.device_metadata, "device_metadata"));
  }

  private void processDeviceMetadata(Metadata metadata) {
    if (metadata instanceof MetadataException metadataException) {
      throw new RuntimeException("While processing metadata file " + metadataException.file,
          metadataException.exception);
    }
    targetSchema = ifNotNullGet(metadata.device_version, SchemaVersion::fromKey);
    ifNotNullThen(targetSchema, version -> warn("Emulating UDMI version " + version.key()));

    if (metadata.cloud != null) {
      config.algorithm = catchToNull(() -> metadata.cloud.auth_type.value());
    }

    if (metadata.gateway != null) {
      config.gatewayId = metadata.gateway.gateway_id;
      if (config.gatewayId != null) {
        Auth_type authType = siteModel.getAuthType(config.gatewayId);
        if (authType != null) {
          config.algorithm = authType.value();
        }
      }
    }

    info("Configured with auth_type " + config.algorithm);

    isGatewayDevice = catchToFalse(() -> metadata.gateway.proxy_ids != null);

    deviceManager.setMetadata(metadata);
  }

  @Override
  public void periodicUpdate() {
    try {
      deviceUpdateCount++;
      checkSmokyFailure();
      deferredConfigActions();
      sendEmptyMissingBadEvents();
      maybeTweakState();
      flushDirtyState();
    } catch (Exception e) {
      error("Fatal error during execution", e);
    }
  }

  private void checkSmokyFailure() {
    if (isTrue(config.options.smokeCheck)
        && Instant.now().minus(SMOKE_CHECK_TIME).isAfter(DEVICE_START_TIME.toInstant())) {
      error(format("Smoke check failed after %sm, terminating run.",
          SMOKE_CHECK_TIME.getSeconds() / 60));
      deviceManager.systemLifecycle(SystemMode.TERMINATE);
    }
  }

  /**
   * For testing, if configured, send a slate of bad messages for testing by the message handling
   * infrastructure. Uses the sekrit REPLACE_MESSAGE_WITH field to sneak bad output into the pipe.
   * E.g., Will send a message with "{ INVALID JSON!" as a message payload. Inserts a delay before
   * each message sent to stabilize the output order for testing purposes.
   */
  private void sendEmptyMissingBadEvents() {
    int phase = deviceUpdateCount % MESSAGE_REPORT_INTERVAL;
    if (!isTrue(config.options.emptyMissing)
        || (phase >= INVALID_REPLACEMENTS.size() + 2)) {
      return;
    }

    safeSleep(INJECT_MESSAGE_DELAY_MS);

    if (phase == 0) {
      flushDirtyState();
      InjectedState invalidState = new InjectedState();
      invalidState.REPLACE_MESSAGE_WITH = CORRUPT_STATE_MESSAGE;
      warn("Sending badly formatted state as per configuration");
      publishStateMessage(invalidState);
    } else if (phase == 1) {
      InjectedMessage invalidEvent = new InjectedMessage();
      invalidEvent.field = "bunny";
      warn("Sending badly formatted message type");
      publishDeviceMessage(invalidEvent);
    } else {
      String key = INVALID_KEYS.get(phase - 2);
      InjectedMessage replacedEvent = new InjectedMessage();
      replacedEvent.REPLACE_TOPIC_WITH = key;
      replacedEvent.REPLACE_MESSAGE_WITH = INVALID_REPLACEMENTS.get(key);
      warn("Sending badly formatted message of type " + key);
      publishDeviceMessage(replacedEvent);
    }
    safeSleep(INJECT_MESSAGE_DELAY_MS);
  }

  private void maybeTweakState() {
    if (!isTrue(options.tweakState)) {
      return;
    }
    int phase = deviceUpdateCount % 2;
    String randomValue = format("%04x", System.currentTimeMillis() % 0xffff);
    if (phase == 0) {
      catchToNull(() -> deviceState.system.software.put("random", randomValue));
    } else if (phase == 1) {
      ifNotNullThen(deviceState.pointset, state -> state.state_etag = randomValue);
    }
  }

  private void deferredConfigActions() {
    if (!isConnected) {
      return;
    }

    deviceManager.maybeRestartSystem();

    // Do redirect after restart system check, since this might take a long time.
    maybeRedirectEndpoint();
  }

  private void flushDirtyState() {
    if (stateDirty.get()) {
      publishAsynchronousState();
    }
  }

  @Override
  public void startConnection(Function<String, Boolean> connectionDone) {
    String nonce = String.valueOf(System.currentTimeMillis());
    warn(format("Starting connection %s with %d", nonce, retriesRemaining.get()));
    try {
      this.connectionDone = connectionDone;
      while (retriesRemaining.getAndDecrement() > 0) {
        if (attemptConnection()) {
          return;
        }
      }
      throw new RuntimeException("Failed connection attempt after retries");
    } catch (Exception e) {
      throw new RuntimeException("While attempting to start connection", e);
    } finally {
      warn(format("Ending connection %s with %d", nonce, retriesRemaining.get()));
    }
  }

  private boolean attemptConnection() {
    try {
      isConnected = false;
      deviceManager.stop();
      if (deviceTarget == null) {
        throw new RuntimeException("Mqtt publisher not initialized");
      }
      connect();
      configLatchWait();
      isConnected = true;
      deviceManager.activate();
      return true;
    } catch (Exception e) {
      error("While waiting for connection start", e);
    }
    error("Attempt failed, retries remaining: " + retriesRemaining.get());
    safeSleep(RESTART_DELAY_MS);
    return false;
  }

  private void configLatchWait() {
    try {
      int waitTimeSec = ofNullable(config.endpoint.config_sync_sec)
          .orElse(DEFAULT_CONFIG_WAIT_SEC);
      int useWaitTime = waitTimeSec == 0 ? DEFAULT_CONFIG_WAIT_SEC : waitTimeSec;
      warn(format("Start waiting %ds for config latch for %s", useWaitTime, deviceId));
      if (useWaitTime > 0 && !configLatch.await(useWaitTime, TimeUnit.SECONDS)) {
        throw new RuntimeException("Config latch timeout");
      }
    } catch (Exception e) {
      throw new RuntimeException(format("While waiting for %s config latch", deviceId), e);
    }
  }

  protected void initialize() {
    try {
      initializeDevice();
      initializeMqtt();
    } catch (Exception e) {
      shutdown();
      throw new RuntimeException("While initializing main pubber class", e);
    }
  }

  @Override
  public void shutdown() {
    warn("Initiating device shutdown");

    if (deviceState.system != null && deviceState.system.operation != null) {
      deviceState.system.operation.mode = SystemMode.SHUTDOWN;
    }

    super.shutdown();
    ifNotNullThen(deviceManager, dm -> captureExceptions("device manager shutdown", dm::shutdown));
    captureExceptions("publishing shutdown state", this::publishSynchronousState);
    captureExceptions("disconnecting mqtt", this::disconnectMqtt);
  }

  @Override
  public void initializeMqtt() {
    checkNotNull(config.deviceId, "configuration deviceId not defined");
    if (siteModel != null && config.keyFile != null) {
      config.keyFile = siteModel.getDeviceKeyFile(config.deviceId);
    }
    ensureKeyBytes();
    checkState(deviceTarget == null, "mqttPublisher already defined");
    String keyPassword = siteModel.getDevicePassword(config.deviceId);
    String targetDeviceId = getTargetDeviceId(siteModel, config.deviceId);
    CertManager certManager = new CertManager(new File(siteModel.getReflectorDir(), CA_CRT),
        siteModel.getDeviceDir(targetDeviceId), config.endpoint.transport, keyPassword,
        this::info);
    deviceTarget = new MqttDevice(config, this::publisherException, certManager);
    registerMessageHandlers();
    publishDirtyState();
  }

  private String getTargetDeviceId(SiteModel siteModel, String deviceId) {
    Metadata metadata = siteModel.getMetadata(deviceId);
    return ofNullable(catchToNull(() -> metadata.gateway.gateway_id)).orElse(deviceId);
  }

  @Override
  public byte[] ensureKeyBytes() {
    if (config.keyBytes == null) {
      checkNotNull(config.keyFile, "configuration keyFile not defined");
      info("Loading device key bytes from " + config.keyFile);
      config.keyBytes = getFileBytes(config.keyFile);
      config.keyFile = null;
    }
    return (byte[]) config.keyBytes;
  }


  @Override
  public void publisherException(Exception toReport) {
    if (toReport instanceof PublisherException report) {
      publisherHandler(report.type, report.phase, report.getCause(), report.deviceId);
    } else if (toReport instanceof ConnectionClosedException) {
      error("Connection closed, attempting reconnect...");
      while (retriesRemaining.getAndDecrement() > 0) {
        if (attemptConnection()) {
          return;
        }
      }
      error("Connection retry failed, giving up.");
      deviceManager.systemLifecycle(SystemMode.TERMINATE);
    } else {
      error("Unknown exception type " + toReport.getClass(), toReport);
    }
  }


  @Override
  public void persistEndpoint(EndpointConfiguration endpoint) {
    notice("Persisting connection endpoint");
    persistentData.endpoint = endpoint;
    writePersistentStore();
  }

  private String redirectedEndpoint(String redirectRegistry) {
    try {
      EndpointConfiguration endpoint = deepCopy(config.endpoint);
      endpoint.client_id = getClientId(redirectRegistry);
      return toJsonString(endpoint);
    } catch (Exception e) {
      throw new RuntimeException("While getting redirected endpoint", e);
    }
  }

  @Override
  public AtomicBoolean getStateDirty() {
    return null;
  }

  @Override
  public SchemaVersion getTargetSchema() {
    return null;
  }

  @Override
  public void resetConnection(String targetEndpoint) {
    try {
      config.endpoint = fromJsonString(targetEndpoint,
          EndpointConfiguration.class);
      disconnectMqtt();
      initializeMqtt();
      retriesRemaining.set(CONNECT_RETRIES);
      startConnection(connectionDone);
    } catch (Exception e) {
      throw new RuntimeException("While resetting connection", e);
    }
  }

  @Override
  public String traceTimestamp(String messageBase) {
    int serial = MESSAGE_COUNTS.computeIfAbsent(messageBase, key -> new AtomicInteger())
        .incrementAndGet();
    String timestamp = getTimestamp().replace("Z", format(".%03dZ", serial));
    return messageBase + (isTrue(config.options.messageTrace) ? ("_" + timestamp) : "");
  }

  private void cloudLog(String message, Level level) {
    cloudLog(message, level, null);
  }

  private void cloudLog(String message, Level level, String detail) {
    if (deviceManager != null) {
      deviceManager.cloudLog(message, level, detail);
    } else {
      String detailPostfix = detail == null ? "" : ":\n" + detail;
      String logMessage = format("%s%s", message, detailPostfix);
      LOG_MAP.get(level).accept(logMessage);
    }
  }

  private void trace(String message) {
    cloudLog(message, Level.TRACE);
  }

  @Override
  public void debug(String message) {
    cloudLog(message, Level.DEBUG);
  }

  @Override
  public void info(String message) {
    cloudLog(message, Level.INFO);
  }

  public void notice(String message) {
    cloudLog(message, Level.NOTICE);
  }

  @Override
  public void warn(String message) {
    cloudLog(message, Level.WARNING);
  }

  @Override
  public void error(String message) {
    cloudLog(message, Level.ERROR);
  }

  @Override
  public void error(String message, Throwable e) {
    if (e == null) {
      error(message);
      return;
    }
    String longMessage = message + ": " + e.getMessage();
    cloudLog(longMessage, Level.ERROR);
    deviceManager.localLog(message, Level.TRACE, getTimestamp(), stackTraceString(e));
  }

  @Override
  public void setLastStateTimeMs(long lastStateTimeMs) {
    this.lastStateTimeMs = lastStateTimeMs;
  }

  @Override
  public long getLastStateTimeMs() {
    return this.lastStateTimeMs;
  }

  @Override
  public CountDownLatch getConfigLatch() {
    return this.configLatch;
  }

  @Override
  public File getOutDir() {
    return this.outDir;
  }

  @Override
  public Lock getStateLock() {
    return stateLock;
  }

  @Override
  public EndpointConfiguration getExtractedEndpoint() {
    return this.extractedEndpoint;
  }

  @Override
  public void setExtractedEndpoint(EndpointConfiguration endpointConfiguration) {
    this.extractedEndpoint = endpointConfiguration;
  }

  @Override
  public String getWorkingEndpoint() {
    return workingEndpoint;
  }

  @Override
  public void setAttemptedEndpoint(String attemptedEndpoint) {
    this.attemptedEndpoint = attemptedEndpoint;
  }

  @Override
  public String getAttemptedEndpoint() {
    return this.attemptedEndpoint;
  }

  @Override
  public State getDeviceState() {
    return deviceState;
  }

  @Override
  public Config getDeviceConfig() {
    return deviceConfig;
  }

  @Override
  public DeviceManager getDeviceManager() {
    return deviceManager;
  }

  @Override
  public MqttDevice getDeviceTarget() {
    return deviceTarget;
  }

  @Override
  public void setDeviceTarget(MqttDevice deviceTarget) {
    this.deviceTarget = deviceTarget;
  }

  @Override
  public boolean isGatewayDevice() {
    return isGatewayDevice;
  }

  @Override
  public void setWorkingEndpoint(String jsonString) {
    this.workingEndpoint = jsonString;
  }

  @Override
  public void setConfigLatch(CountDownLatch countDownLatch) {
    this.configLatch = countDownLatch;
  }
}
