package daq.pubber;

public interface ManagerLog {

  void debug(String message);

  void info(String message);

  void warn(String message);

  void error(String message, Throwable e);
}
