package daq.pubber.client;

import static com.google.udmi.util.GeneralUtils.getNow;
import static com.google.udmi.util.GeneralUtils.ifNotNullThen;
import static udmi.schema.Category.POINTSET_POINT_INVALID;
import static udmi.schema.Category.POINTSET_POINT_INVALID_VALUE;

import daq.pubber.AbstractPoint;
import java.util.Map;
import udmi.schema.Entry;
import udmi.schema.PointPointsetState;
import udmi.schema.PointsetConfig;
import udmi.schema.PointsetEvents;
import udmi.schema.PointsetModel;
import udmi.schema.PointsetState;

/**
 * Pointset client.
 */
public interface PointsetManagerProvider {

  ExtraPointsetEvent getPointsetEvent();

  Map<String, AbstractPoint> getManagedPoints();

  int getPointsetUpdateCount();

  PointsetState getPointsetState();

  default void setPointsetModel(PointsetModel pointset) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  default void setExtraField(String extraField) {
    ifNotNullThen(extraField, field -> getPointsetEvent().extraField = field);
  }


  default void addPoint(AbstractPoint point) {
    getManagedPoints().put(point.getName(), point);
  }

  void restorePoint(String pointName);

  PointPointsetState getTweakedPointState(AbstractPoint point);

  default void suspendPoint(String pointName) {
    getPointsetState().points.remove(pointName);
    getPointsetEvent().points.remove(pointName);
  }


  void updateState();

  void updateState(AbstractPoint point);

  /**
   * marks point as invalid.
   *
   * @param pointName point name.
   * @return PointPointsetState.
   */
  default PointPointsetState invalidPoint(String pointName) {
    PointPointsetState pointPointsetState = new PointPointsetState();
    pointPointsetState.status = new Entry();
    pointPointsetState.status.category = POINTSET_POINT_INVALID;
    pointPointsetState.status.level = POINTSET_POINT_INVALID_VALUE;
    pointPointsetState.status.message = "Unknown configured point " + pointName;
    pointPointsetState.status.timestamp = getNow();
    return pointPointsetState;
  }


  void updateConfig(PointsetConfig config);

  void sendDevicePoints();

  void stop();

  void shutdown();

  /**
   * PointsetEvents with extraField.
   */
  class ExtraPointsetEvent extends PointsetEvents {

    // This extraField exists only to trigger schema parsing errors.
    public Object extraField;
  }

  void updatePoints();

  void updatePointsetPointsConfig(PointsetConfig config);


}
