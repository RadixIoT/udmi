package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.getNow;
import static com.google.udmi.util.GeneralUtils.getTimestamp;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static com.google.udmi.util.GeneralUtils.ifTrueGet;
import static com.google.udmi.util.GeneralUtils.ifTrueThen;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static udmi.schema.Category.POINTSET_POINT_INVALID;
import static udmi.schema.Category.POINTSET_POINT_INVALID_VALUE;

import daq.pubber.AbstractPoint;
import daq.pubber.ManagerBase;
import daq.pubber.ManagerHost;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import udmi.schema.Entry;
import udmi.schema.PointPointsetEvents;
import udmi.schema.PointPointsetState;
import udmi.schema.PointsetConfig;
import udmi.schema.PointsetEvents;
import udmi.schema.PointsetModel;
import udmi.schema.PointsetState;
import udmi.schema.PubberConfiguration;

public abstract class AbstractPointsetManager extends ManagerBase {

  private final ExtraPointsetEvent pointsetEvent = new ExtraPointsetEvent();
  private final Map<String, AbstractPoint> managedPoints = new HashMap<>();
  private int pointsetUpdateCount = -1;
  private PointsetState pointsetState;

  private static PointPointsetEvents extraPointsetEvent() {
    PointPointsetEvents pointPointsetEvent = new PointPointsetEvents();
    pointPointsetEvent.present_value = 100;
    return pointPointsetEvent;
  }
  /**
   * New instance.
   *
   * @param host
   * @param configuration
   */
  public AbstractPointsetManager(ManagerHost host,
      PubberConfiguration configuration) {
    super(host, configuration);
  }

  public void setPointsetModel(PointsetModel pointset) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  public void setExtraField(String extraField) {
    ifNotNullThen(extraField, field -> pointsetEvent.extraField = field);
  }


  protected void addPoint(AbstractPoint point) {
    managedPoints.put(point.getName(), point);
  }

  protected void restorePoint(String pointName) {
    if (pointsetState == null || pointName.equals(options.missingPoint)) {
      return;
    }

    pointsetState.points.put(pointName, ifNotNullGet(managedPoints.get(pointName),
        this::getTweakedPointState, invalidPoint(pointName)));
    pointsetEvent.points.put(pointName, ifNotNullGet(managedPoints.get(pointName),
        AbstractPoint::getData, new PointPointsetEvents()));
  }

  protected PointPointsetState getTweakedPointState(AbstractPoint point) {
    PointPointsetState state = point.getState();
    // Tweak for testing: erroneously apply an applied state here.
    ifTrueThen(point.getName().equals(options.extraPoint),
        () -> state.value_state = ofNullable(state.value_state).orElse(PointPointsetState.Value_state.APPLIED));
    return state;
  }

  protected void suspendPoint(String pointName) {
    pointsetState.points.remove(pointName);
    pointsetEvent.points.remove(pointName);
  }


  protected void updateState() {
    updateState(ofNullable((Object) pointsetState).orElse(PointsetState.class));
  }

  protected void updateState(AbstractPoint point) {
    String pointName = point.getName();

    if (!pointsetState.points.containsKey(pointName)) {
      return;
    }

    if (point.isDirty()) {
      PointPointsetState state = getTweakedPointState(point); // Always call to clear the dirty bit
      PointPointsetState useState = ifTrueGet(options.noPointState, PointPointsetState::new, state);
      pointsetState.points.put(pointName, useState);
      updateState();
    }
  }

  protected PointPointsetState invalidPoint(String pointName) {
    PointPointsetState pointPointsetState = new PointPointsetState();
    pointPointsetState.status = new Entry();
    pointPointsetState.status.category = POINTSET_POINT_INVALID;
    pointPointsetState.status.level = POINTSET_POINT_INVALID_VALUE;
    pointPointsetState.status.message = "Unknown configured point " + pointName;
    pointPointsetState.status.timestamp = getNow();
    return pointPointsetState;
  }


  public void updateConfig(PointsetConfig config) {
    Integer rate = ifNotNullGet(config, c -> c.sample_rate_sec);
    Integer limit = ifNotNullGet(config, c -> c.sample_limit_sec);
    Integer max = Stream.of(rate, limit).filter(Objects::nonNull).reduce(Math::max).orElse(null);
    updateInterval(max);
    updatePointsetPointsConfig(config);
  }

  @Override
  protected void periodicUpdate() {
    try {
      if (pointsetState != null) {
        pointsetUpdateCount++;
        updatePoints();
        sendDevicePoints();
      }
    } catch (Exception e) {
      error("Fatal error during execution", e);
    }
  }

  protected void sendDevicePoints() {
    if (pointsetUpdateCount % AbstractPubber.MESSAGE_REPORT_INTERVAL == 0) {
      info(format("%s sending %s message #%d with %d points",
          getTimestamp(), getDeviceId(), pointsetUpdateCount, pointsetEvent.points.size()));
    }
    host.publish(pointsetEvent);
  }

  static class ExtraPointsetEvent extends PointsetEvents {

    // This extraField exists only to trigger schema parsing errors.
    public Object extraField;
  }

  protected abstract void updatePoints();

  protected abstract void updatePointsetPointsConfig(PointsetConfig config);
}
