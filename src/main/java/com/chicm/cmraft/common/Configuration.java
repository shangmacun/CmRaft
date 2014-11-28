package com.chicm.cmraft.common;

public interface Configuration {
  
  String getString(String key);
  
  String getString(String key, String defaultValue);
  
  int getInt(String key);
  
  int getInt(String key, int defaultValue);
  
  void set(String key, String value);
  
  void clear();
  
  Iterable<String> getKeys();
  
  Iterable<String> getKeys(String prefix);
}
