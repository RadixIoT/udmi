package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.getNow;
import static java.util.Optional.ofNullable;

import com.google.udmi.util.GeneralUtils;
import com.google.udmi.util.SchemaVersion;
import daq.pubber.ManagerHost;
import daq.pubber.MqttDevice;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.Date;
import java.util.function.Function;
import udmi.schema.DevicePersistent;
import udmi.schema.EndpointConfiguration;
import udmi.schema.PubberConfiguration;

/**
 * Pubber client.
 */
public interface PubberHost extends ManagerHost {

  static final String DATA_URL_JSON_BASE64 = "data:application/json;base64,";

  static final String BROKEN_VERSION = "1.4.";
  static final String UDMI_VERSION = SchemaVersion.CURRENT.key();

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

}
