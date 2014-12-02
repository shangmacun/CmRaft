package com.chicm.cmraft.common;

import java.util.Iterator;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class CmRaftConfiguration implements Configuration {
  static final Log LOG = LogFactory.getLog(CmRaftConfiguration.class);
  private PropertiesConfiguration conf;
  
  public CmRaftConfiguration() throws ConfigurationException {
    conf = new PropertiesConfiguration("cmraft.properties");
    
  }
  
  public static Configuration create() {
    try {
      CmRaftConfiguration raftConfig = new CmRaftConfiguration();
      return raftConfig;
    } catch(ConfigurationException e) {
      return null;
    }
  }
  
  @Override
  public void useResource(String resourceName) {
    try {
      conf = new PropertiesConfiguration(resourceName);
    } catch(ConfigurationException e) {
      LOG.error("load resource exception", e);
    }
  }
  
  @Override
  public Object clone() {
    Configuration obj = CmRaftConfiguration.create();
    obj.clear();
    for(String key: this.getKeys()) {
      obj.set(key, this.getString(key));
    }
    return (Object)obj;
  }
  
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for(String key: this.getKeys()) {
      sb.append(key + ":" + conf.getString(key)+"\n");
    }
    return sb.toString();
  }
  
  @Override
  public String getString(String key) {
    return conf.getString(key);
  }
  
  @Override
  public String getString(String key, String defaultValue) {
    return conf.getString(key, defaultValue);
  }

  @Override
  public int getInt(String key) {
    return conf.getInt(key);
  }
  
  @Override
  public int getInt(String key, int defaultValue) {
    return conf.getInt(key, defaultValue);
  }
  @Override
  public Iterable<String> getKeys() {
    return this.new ConfigurationIterableImpl();
  }
  
  @Override
  public Iterable<String> getKeys(String prefix) {
    return this.new ConfigurationIterableImplForPrefix(prefix);
  }
  
  @Override
  public void set(String key, String value) {
    conf.setProperty(key, value);
  }
  
  @Override
  public void clear() {
    conf.clear();
  }
  
  @Override
  public void remove(String key) {
    conf.clearProperty(key);
  }
  
  private class ConfigurationIterableImpl implements Iterable<String> {
    public Iterator<String> iterator() {
      return conf.getKeys();
    }
  }
  
  private class ConfigurationIterableImplForPrefix implements Iterable<String> {
    private String prefix ;
    ConfigurationIterableImplForPrefix(String prefix) {
      this.prefix = prefix;
    }
    public Iterator<String> iterator() {
      return conf.getKeys(prefix);
    }
  }
}