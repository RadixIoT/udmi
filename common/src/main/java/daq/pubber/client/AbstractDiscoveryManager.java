package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static com.google.udmi.util.GeneralUtils.ifNullElse;
import static com.google.udmi.util.GeneralUtils.ifNullThen;
import static com.google.udmi.util.GeneralUtils.ifTrueGet;
import static com.google.udmi.util.GeneralUtils.ifTrueThen;
import static com.google.udmi.util.JsonUtil.isoConvert;
import static java.lang.Math.floorMod;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;
import static udmi.schema.FamilyDiscoveryState.Phase.PENDING;
import static udmi.schema.FamilyDiscoveryState.Phase.STOPPED;

import com.google.common.collect.ImmutableMap;
import com.google.udmi.util.SiteModel;
import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import udmi.schema.Depths;
import udmi.schema.DiscoveryConfig;
import udmi.schema.DiscoveryState;
import udmi.schema.FamilyDiscoveryConfig;
import udmi.schema.FamilyDiscoveryState;
import udmi.schema.PubberConfiguration;

public abstract class AbstractDiscoveryManager extends ManagerBase {


  private static boolean shouldEnumerateTo(Depths.Depth depth) {
    return ifNullElse(depth, false, d -> switch (d) {
      default -> false;
      case ENTRIES, DETAILS -> true;
    });
  }
  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractDiscoveryManager(ManagerHost host,
      PubberConfiguration configuration) {
    super(host, configuration);
  }

  protected  <K, V> Map<K, V> maybeEnumerate(Depths.Depth depth, Supplier<Map<K, V>> supplier) {
    return ifTrueGet(shouldEnumerateTo(depth), supplier);
  }

  protected void updateDiscoveryScan(Map<String, FamilyDiscoveryConfig> raw) {
    Map<String, FamilyDiscoveryConfig> families = ofNullable(raw).orElse(Map.of());
    ifNullThen(getDiscoveryState().families, () -> getDiscoveryState().families = new HashMap<>());

    getDiscoveryState().families.keySet().stream().filter(not(families::containsKey))
        .forEach(this::removeDiscoveryScan);
    families.keySet().forEach(this::scheduleDiscoveryScan);

    if (raw == null) {
      getDiscoveryState().families = null;
    }
  }

  protected void scheduleDiscoveryScan(String family) {
    FamilyDiscoveryConfig familyDiscoveryConfig = getFamilyDiscoveryConfig(family);
    Date rawGeneration = familyDiscoveryConfig.generation;
    int interval = getScanInterval(family);
    if (rawGeneration == null && interval == 0) {
      cancelDiscoveryScan(family, null, null);
      return;
    }

    Date configGeneration = ofNullable(rawGeneration).orElse(getDeviceStartTime());
    FamilyDiscoveryState familyDiscoveryState = ensureFamilyDiscoveryState(family);
    Date baseGeneration = ofNullable(familyDiscoveryState.generation).orElse(getDeviceStartTime());

    final Date startGeneration;
    if (interval > 0) {
      Instant now = Instant.now();
      long deltaSec = floorMod(configGeneration.getTime() / 1000 - now.getEpochSecond(), interval);
      startGeneration = Date.from(now.plusSeconds(deltaSec));
    } else if (configGeneration.before(baseGeneration)) {
      cancelDiscoveryScan(family, configGeneration, STOPPED);
      return;
    } else {
      startGeneration = configGeneration;
    }

    if (startGeneration.equals(baseGeneration)) {
      return;
    }

    info("Discovery scan generation " + family + " pending at " + isoConvert(startGeneration));
    familyDiscoveryState.generation = startGeneration;
    familyDiscoveryState.phase = PENDING;
    updateState();

    scheduleFuture(startGeneration, () -> checkDiscoveryScan(family, startGeneration));
  }

  protected FamilyDiscoveryConfig getFamilyDiscoveryConfig(String family) {
    return getDiscoveryConfig().families.get(family);
  }

  protected void removeDiscoveryScan(String family) {
    FamilyDiscoveryState removed = getDiscoveryState().families.remove(family);
    ifNotNullThen(removed, was -> cancelDiscoveryScan(family, was.generation, STOPPED));
  }

  protected void cancelDiscoveryScan(String family, Date configGeneration, FamilyDiscoveryState.Phase phase) {
    FamilyDiscoveryState familyDiscoveryState = getFamilyDiscoveryState(family);
    info(format("Discovery scan %s phase %s as %s", family, phase, isoConvert(configGeneration)));
    familyDiscoveryState.phase = phase;
    familyDiscoveryState.generation = configGeneration;
    updateState();
  }

  private FamilyDiscoveryState getFamilyDiscoveryState(String family) {
    return getDiscoveryState().families.get(family);
  }

  protected FamilyDiscoveryState ensureFamilyDiscoveryState(String family) {
    if (getDiscoveryState().families == null) {
      // If there is no need for family state, then return a floating bucket for results.
      return new FamilyDiscoveryState();
    }
    return getDiscoveryState().families.computeIfAbsent(
        family, key -> new FamilyDiscoveryState());
  }

  protected void checkDiscoveryScan(String family, Date scanGeneration) {
    try {
      FamilyDiscoveryState familyDiscoveryState = ensureFamilyDiscoveryState(family);
      ifTrueThen(familyDiscoveryState.phase == PENDING,
          () -> startDiscoveryScan(family, scanGeneration));
    } catch (Exception e) {
      throw new RuntimeException("While checking for discovery scan start", e);
    }
  }

  protected void updateState() {
    updateState(ofNullable((Object) getDiscoveryState()).orElse(DiscoveryState.class));
  }

  /**
   * Update the discovery config.
   */
  public void updateConfig(DiscoveryConfig discovery) {
    setDiscoveryConfig(discovery);
    if (discovery == null) {
      setDiscoveryState(null);
      updateState();
      return;
    }
    if (getDiscoveryState() == null) {
      setDiscoveryState(new DiscoveryState());
    }
    updateDiscoveryEnumeration(discovery);
    updateDiscoveryScan(discovery.families);
    updateState();
  }

  protected int getScanInterval(String family) {
    return ofNullable(
        catchToNull(() -> getFamilyDiscoveryConfig(family).scan_interval_sec)).orElse(0);
  }

  protected abstract Date getDeviceStartTime();

  public abstract void setSiteModel(SiteModel siteModel);

  protected abstract DiscoveryState getDiscoveryState();

  protected abstract void setDiscoveryState(DiscoveryState discoveryState);

  protected abstract DiscoveryConfig getDiscoveryConfig();

  protected abstract void setDiscoveryConfig(DiscoveryConfig discoveryConfig);

  protected abstract void startDiscoveryScan(String family, Date scanGeneration);

  protected abstract void updateDiscoveryEnumeration(DiscoveryConfig config);

}
