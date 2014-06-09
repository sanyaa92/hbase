/**
 * Copyright 2009 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.WritableComparable;

import com.facebook.swift.codec.ThriftConstructor;
import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;
import com.google.common.collect.ImmutableSet;

/**
 * HTableDescriptor contains the name of an HTable, and its
 * column families.
 */
@ThriftStruct
public class HTableDescriptor implements WritableComparable<HTableDescriptor> {
  public static final Log LOG = LogFactory.getLog(HTableDescriptor.class);

  // Changes prior to version 3 were not recorded here.
  // Version 3 adds metadata as a map where keys and values are byte[].
  // Version 4 adds indexes
  // Version 5 removed transactional pollution -- e.g. indexes
  public static final byte TABLE_DESCRIPTOR_VERSION = 5;

  private byte [] name = HConstants.EMPTY_BYTE_ARRAY;
  private String nameAsString = "";

  // Table metadata
  protected Map<ImmutableBytesWritable, ImmutableBytesWritable> values =
    new HashMap<ImmutableBytesWritable, ImmutableBytesWritable>();

  public static final String FAMILIES = "FAMILIES";
  public static final ImmutableBytesWritable FAMILIES_KEY =
    new ImmutableBytesWritable(Bytes.toBytes(FAMILIES));
  public static final String MAX_FILESIZE = "MAX_FILESIZE";
  public static final ImmutableBytesWritable MAX_FILESIZE_KEY =
    new ImmutableBytesWritable(Bytes.toBytes(MAX_FILESIZE));
  public static final String READONLY = "READONLY";
  public static final ImmutableBytesWritable READONLY_KEY =
    new ImmutableBytesWritable(Bytes.toBytes(READONLY));
  public static final String MEMSTORE_FLUSHSIZE = "MEMSTORE_FLUSHSIZE";
  public static final ImmutableBytesWritable MEMSTORE_FLUSHSIZE_KEY =
    new ImmutableBytesWritable(Bytes.toBytes(MEMSTORE_FLUSHSIZE));
  public static final String IS_ROOT = "IS_ROOT";
  public static final ImmutableBytesWritable IS_ROOT_KEY =
    new ImmutableBytesWritable(Bytes.toBytes(IS_ROOT));
  public static final String IS_META = "IS_META";

  public static final ImmutableBytesWritable IS_META_KEY =
    new ImmutableBytesWritable(Bytes.toBytes(IS_META));

  public static final String DEFERRED_LOG_FLUSH = "DEFERRED_LOG_FLUSH";
  public static final ImmutableBytesWritable DEFERRED_LOG_FLUSH_KEY =
    new ImmutableBytesWritable(Bytes.toBytes(DEFERRED_LOG_FLUSH));

  public static final String DISABLE_WAL = "DISABLE_WAL";
  public static final ImmutableBytesWritable DISABLE_WAL_KEY =
    new ImmutableBytesWritable(Bytes.toBytes(DISABLE_WAL));

  public static final String SERVER_SET = "SERVER_SET";
  public static final ImmutableBytesWritable SERVER_SET_KEY =
      new ImmutableBytesWritable( Bytes.toBytes(SERVER_SET));


  // The below are ugly but better than creating them each time till we
  // replace booleans being saved as Strings with plain booleans.  Need a
  // migration script to do this.  TODO.
  private static final ImmutableBytesWritable FALSE =
    new ImmutableBytesWritable(Bytes.toBytes(Boolean.FALSE.toString()));
  private static final ImmutableBytesWritable TRUE =
    new ImmutableBytesWritable(Bytes.toBytes(Boolean.TRUE.toString()));

  public static final boolean DEFAULT_READONLY = false;

  public static final long DEFAULT_MEMSTORE_FLUSH_SIZE = 1024*1024*64L;

  public static final long DEFAULT_MEMSTORE_COLUMNFAMILY_FLUSH_SIZE =
    1024*1024*16L;

  public static final long DEFAULT_MAX_FILESIZE = 1024*1024*256L;

  public static final boolean DEFAULT_DEFERRED_LOG_FLUSH = true;

  private final static Map<String, String> DEFAULT_VALUES
    = new HashMap<String, String>();
  private final static Set<ImmutableBytesWritable> RESERVED_KEYWORDS
    = new HashSet<ImmutableBytesWritable>();
  static {
    DEFAULT_VALUES.put(MAX_FILESIZE, String.valueOf(DEFAULT_MAX_FILESIZE));
    DEFAULT_VALUES.put(READONLY, String.valueOf(DEFAULT_READONLY));
    DEFAULT_VALUES.put(MEMSTORE_FLUSHSIZE,
        String.valueOf(DEFAULT_MEMSTORE_FLUSH_SIZE));
    DEFAULT_VALUES.put(DEFERRED_LOG_FLUSH,
        String.valueOf(DEFAULT_DEFERRED_LOG_FLUSH));
    for (String s : DEFAULT_VALUES.keySet()) {
      RESERVED_KEYWORDS.add(new ImmutableBytesWritable(Bytes.toBytes(s)));
    }
    RESERVED_KEYWORDS.add(SERVER_SET_KEY);
    RESERVED_KEYWORDS.add(IS_ROOT_KEY);
    RESERVED_KEYWORDS.add(IS_META_KEY);
  }

  private volatile Boolean meta = null;
  private volatile Boolean root = null;
  private Boolean isDeferredLog = null;

 /** Master switch to enable column family in ROOT table */
 public static final String METAREGION_SEQID_RECORD_ENABLED =
   "metaregion.seqid.record.enabled";

  // Key is hash of the family name.
  public final Map<byte [], HColumnDescriptor> families =
    new TreeMap<byte [], HColumnDescriptor>(Bytes.BYTES_RAWCOMPARATOR);

  // Used to store what servers this can be assigned to.
  private ServerSet serverSet = null;

  /**
   * Private constructor used internally creating table descriptors for
   * catalog tables: e.g. .META. and -ROOT-.
   */
  protected HTableDescriptor(final byte [] name, HColumnDescriptor[] families) {
    this.name = name.clone();
    this.nameAsString = Bytes.toString(this.name);
    setMetaFlags(name);
    for(HColumnDescriptor descriptor : families) {
      this.families.put(descriptor.getName(), descriptor);
    }
  }

  /**
   * Private constructor used internally creating table descriptors for
   * catalog tables: e.g. .META. and -ROOT-.
   */
  protected HTableDescriptor(final byte [] name, HColumnDescriptor[] families,
      Map<ImmutableBytesWritable,ImmutableBytesWritable> values) {
    this.name = name.clone();
    this.nameAsString = Bytes.toString(this.name);
    setMetaFlags(name);
    for(HColumnDescriptor descriptor : families) {
      this.families.put(descriptor.getName(), descriptor);
    }
    this.values.putAll(values);
  }

  @ThriftConstructor
  public HTableDescriptor(
      @ThriftField(1) final byte[] name,
      @ThriftField(2) List<HColumnDescriptor> families,
      @ThriftField(3) Map<byte[], byte[]> values) {
    this.name = name;
    this.nameAsString = Bytes.toString(this.name);
    setMetaFlags(this.name);
    for (HColumnDescriptor descriptor : families) {
      this.families.put(descriptor.getName(), descriptor);
    }
    for (Entry<byte[], byte[]> entry : values.entrySet()) {
      this.values.put(new ImmutableBytesWritable(entry.getKey()), new ImmutableBytesWritable(entry.getValue()));
    }
  }

  @ThriftField(2)
  public List<HColumnDescriptor> getFamiliesForThrift() {
    List<HColumnDescriptor> listToReturn = new ArrayList<HColumnDescriptor>();
    listToReturn.addAll(this.families.values());
    return listToReturn;
  }

  @ThriftField(3)
  public Map<byte[], byte[]> getValuesForThrift() {
    Map<byte[], byte[]> mapToReturn = new HashMap<byte[], byte[]>();
    for (Entry<ImmutableBytesWritable, ImmutableBytesWritable> entry : this.values.entrySet()) {
      mapToReturn.put(entry.getKey().get(), entry.getValue().get());
    }
    return mapToReturn;
  }

  /**
   * Constructs an empty object.
   * For deserializing an HTableDescriptor instance only.
   * @see #HTableDescriptor(byte[])
   */
  public HTableDescriptor() {
    super();
  }

  /**
   * Constructor.
   * @param name Table name.
   * @throws IllegalArgumentException if passed a table name
   * that is made of other than 'word' characters, underscore or period: i.e.
   * <code>[a-zA-Z_0-9.].
   * @see <a href="HADOOP-1581">HADOOP-1581 HBASE: Un-openable tablename bug</a>
   */
  public HTableDescriptor(final String name) {
    this(Bytes.toBytes(name));
  }

  /**
   * Constructor.
   * @param name Table name.
   * @throws IllegalArgumentException if passed a table name
   * that is made of other than 'word' characters, underscore or period: i.e.
   * <code>[a-zA-Z_0-9-.].
   * @see <a href="HADOOP-1581">HADOOP-1581 HBASE: Un-openable tablename bug</a>
   */
  public HTableDescriptor(final byte[] name) {
    super();
    setMetaFlags(this.name);
    this.name = this.isMetaRegion()? name: isLegalTableName(name);
    this.nameAsString = Bytes.toString(this.name);
  }

  /**
   * Constructor.
   * <p>
   * Makes a deep copy of the supplied descriptor.
   * Can make a modifiable descriptor from an UnmodifyableHTableDescriptor.
   * @param desc The descriptor.
   */
  public HTableDescriptor(final HTableDescriptor desc) {
    super();
    this.name = desc.name.clone();
    this.nameAsString = Bytes.toString(this.name);
    setMetaFlags(this.name);
    for (HColumnDescriptor c: desc.families.values()) {
      this.families.put(c.getName(), new HColumnDescriptor(c));
    }
    this.values.putAll(desc.values);
  }

  /*
   * Set meta flags on this table.
   * Called by constructors.
   * @param name
   */
  private void setMetaFlags(final byte [] name) {
    setRootRegion(Bytes.equals(name, HConstants.ROOT_TABLE_NAME));
    setMetaRegion(isRootRegion() ||
      Bytes.equals(name, HConstants.META_TABLE_NAME));
  }

  /** @return true if this is the root region */
  public boolean isRootRegion() {
    if (this.root == null) {
      this.root = isSomething(IS_ROOT_KEY, false)? Boolean.TRUE: Boolean.FALSE;
    }
    return this.root.booleanValue();
  }

  /** @param isRoot true if this is the root region */
  protected void setRootRegion(boolean isRoot) {
    // TODO: Make the value a boolean rather than String of boolean.
    values.put(IS_ROOT_KEY, isRoot? TRUE: FALSE);
  }

  /** @return true if this is a meta region (part of the root or meta tables) */
  public boolean isMetaRegion() {
    if (this.meta == null) {
      this.meta = calculateIsMetaRegion();
    }
    return this.meta.booleanValue();
  }

  private synchronized Boolean calculateIsMetaRegion() {
    byte [] value = getValue(IS_META_KEY);
    return (value != null)? Boolean.valueOf(Bytes.toString(value)): Boolean.FALSE;
  }

  private boolean isSomething(final ImmutableBytesWritable key,
      final boolean valueIfNull) {
    byte [] value = getValue(key);
    if (value != null) {
      // TODO: Make value be a boolean rather than String of boolean.
      return Boolean.valueOf(Bytes.toString(value)).booleanValue();
    }
    return valueIfNull;
  }

  /**
   * @param isMeta true if this is a meta region (part of the root or meta
   * tables) */
  protected void setMetaRegion(boolean isMeta) {
    values.put(IS_META_KEY, isMeta? TRUE: FALSE);
  }

  /** @return true if table is the META table */
  public boolean isMetaTable() {
    return isMetaRegion() && !isRootRegion();
  }

  /**
   * Check passed buffer is legal user-space table name.
   * @param b Table name.
   * @return Returns passed <code>b</code> param
   * @throws NullPointerException If passed <code>b</code> is null
   * @throws IllegalArgumentException if passed a table name
   * that is made of other than 'word' characters or underscores: i.e.
   * <code>[a-zA-Z_0-9].
   */
  public static byte [] isLegalTableName(final byte [] b) {
    if (b == null || b.length <= 0) {
      throw new IllegalArgumentException("Name is null or empty");
    }
    if (b[0] == '.' || b[0] == '-') {
      throw new IllegalArgumentException("Illegal first character <" + b[0] +
          "> at 0. User-space table names can only start with 'word " +
          "characters': i.e. [a-zA-Z_0-9]: " + Bytes.toStringBinary(b));
    }
    for (int i = 0; i < b.length; i++) {
      if (Character.isLetterOrDigit(b[i]) || b[i] == '_' || b[i] == '-' ||
          b[i] == '.') {
        continue;
      }
      throw new IllegalArgumentException("Illegal character <" + b[i] +
        "> at " + i + ". User-space table names can only contain " +
        "'word characters': i.e. [a-zA-Z_0-9-.]: " + Bytes.toStringBinary(b));
    }
    return b;
  }

  /**
   * @param key The key.
   * @return The value.
   */
  public byte[] getValue(byte[] key) {
    return getValue(new ImmutableBytesWritable(key));
  }

  private byte[] getValue(final ImmutableBytesWritable key) {
    ImmutableBytesWritable ibw = values.get(key);
    if (ibw == null)
      return null;
    return ibw.get();
  }

  /**
   * @param key The key.
   * @return The value as a string.
   */
  public String getValue(String key) {
    byte[] value = getValue(Bytes.toBytes(key));
    if (value == null)
      return null;
    return Bytes.toString(value);
  }

  /**
   * @return All values.
   */
  public Map<ImmutableBytesWritable,ImmutableBytesWritable> getValues() {
    // shallow pointer copy
    return Collections.unmodifiableMap(values);
  }

  /**
   * @param key The key.
   * @param value The value.
   */
  public void setValue(byte[] key, byte[] value) {
    setValue(new ImmutableBytesWritable(key), value);
  }

  /*
   * @param key The key.
   * @param value The value.
   */
  private void setValue(final ImmutableBytesWritable key,
      final byte[] value) {
    values.put(key, new ImmutableBytesWritable(value));
  }

  /*
   * @param key The key.
   * @param value The value.
   */
  private void setValue(final ImmutableBytesWritable key,
      final ImmutableBytesWritable value) {
    values.put(key, value);
  }

  /**
   * @param key The key.
   * @param value The value.
   */
  public void setValue(String key, String value) {
    if (value == null) {
      remove(Bytes.toBytes(key));
    } else {
      setValue(Bytes.toBytes(key), Bytes.toBytes(value));
    }
  }

  /**
   * @param key Key whose key and value we're to remove from HTD parameters.
   */
  public void remove(final byte [] key) {
    values.remove(new ImmutableBytesWritable(key));
  }

  /**
   * @return true if all columns in the table should be read only
   */
  public boolean isReadOnly() {
    return isSomething(READONLY_KEY, DEFAULT_READONLY);
  }

  /**
   * @param readOnly True if all of the columns in the table should be read
   * only.
   */
  public void setReadOnly(final boolean readOnly) {
    setValue(READONLY_KEY, readOnly? TRUE: FALSE);
  }

  /**
   * @return true if that table's log is hflush by other means
   */
  public synchronized boolean isDeferredLogFlush() {
    if(this.isDeferredLog == null) {
      this.isDeferredLog =
          isSomething(DEFERRED_LOG_FLUSH_KEY, DEFAULT_DEFERRED_LOG_FLUSH);
    }
    return this.isDeferredLog;
  }

  /**
   * @param isDeferredLogFlush true if that table's log is hlfush by oter means
   * only.
   */
  public void setDeferredLogFlush(final boolean isDeferredLogFlush) {
    setValue(DEFERRED_LOG_FLUSH_KEY, isDeferredLogFlush? TRUE: FALSE);
  }

  /** @return name of table */
  @ThriftField(1)
  public byte[] getName() {
    return name;
  }

  /** @return name of table */
  public String getNameAsString() {
    return this.nameAsString;
  }

  /** @return max hregion size for table */
  public long getMaxFileSize() {
    byte [] value = getValue(MAX_FILESIZE_KEY);
    if (value != null) {
      return Long.parseLong(Bytes.toString(value));
    }
    return HConstants.DEFAULT_MAX_FILE_SIZE;
  }

  /** @param name name of table */
  public void setName(byte[] name) {
    this.name = name;
  }

  /**
   * @param maxFileSize The maximum file size that a store file can grow to
   * before a split is triggered.
   */
  public void setMaxFileSize(long maxFileSize) {
    setValue(MAX_FILESIZE_KEY, Bytes.toBytes(Long.toString(maxFileSize)));
  }

  /**
   * @return memory cache flush size for each hregion
   */
  public long getMemStoreFlushSize() {
    byte [] value = getValue(MEMSTORE_FLUSHSIZE_KEY);
    if (value != null) {
      return Long.parseLong(Bytes.toString(value));
    }
    return DEFAULT_MEMSTORE_FLUSH_SIZE;
  }

  /**
   * @param memstoreFlushSize memory cache flush size for each hregion
   */
  public void setMemStoreFlushSize(long memstoreFlushSize) {
    setValue(MEMSTORE_FLUSHSIZE_KEY,
      Bytes.toBytes(Long.toString(memstoreFlushSize)));
  }

  /**
   * Adds a column family.
   *
   * @param family HColumnDescriptor of family to add.
   */
  public void addFamily(final HColumnDescriptor family) {
    if (family.getName() == null || family.getName().length <= 0) {
      throw new NullPointerException("Family name cannot be null or empty");
    }
    this.families.put(family.getName(), family);
  }

  /**
   * Checks to see if this table contains the given column family
   * @param c Family name or column name.
   * @return true if the table contains the specified family name
   */
  public boolean hasFamily(final byte [] c) {
    return families.containsKey(c);
  }

  /**
   * @return Name of this table and then a map of all of the column family
   * descriptors.
   * @see #getNameAsString()
   */
  @Override
  public String toString() {
    StringBuilder s = new StringBuilder();
    s.append('\'').append(Bytes.toString(name)).append('\'');
    s.append(getValues(true));
    for (HColumnDescriptor f : families.values()) {
      s.append(", ").append(f);
    }
    return s.toString();
  }

  public String toStringCustomizedValues() {
    StringBuilder s = new StringBuilder();
    s.append('\'').append(Bytes.toString(name)).append('\'');
    s.append(getValues(false));
    for(HColumnDescriptor hcd : families.values()) {
      s.append(", ").append(hcd.toStringCustomizedValues());
    }
    return s.toString();
  }

  private StringBuilder getValues(boolean printDefaults) {
    StringBuilder s = new StringBuilder();

    // step 1: set partitioning and pruning
    Set<ImmutableBytesWritable> reservedKeys = new TreeSet<ImmutableBytesWritable>();
    Set<ImmutableBytesWritable> configKeys = new TreeSet<ImmutableBytesWritable>();
    for (ImmutableBytesWritable k : values.keySet()) {
      if (!RESERVED_KEYWORDS.contains(k)) {
        configKeys.add(k);
        continue;
      }
      // only print out IS_ROOT/IS_META if true
      String key = Bytes.toString(k.get());
      String value = Bytes.toString(values.get(k).get());
      if (key.equalsIgnoreCase(IS_ROOT) || key.equalsIgnoreCase(IS_META)) {
        if (Boolean.valueOf(value) == false) continue;
      }
      if (printDefaults
          || !DEFAULT_VALUES.containsKey(key)
          || !DEFAULT_VALUES.get(key).equalsIgnoreCase(value)) {
        reservedKeys.add(k);
      }
    }

    // early exit optimization
    if (reservedKeys.isEmpty() && configKeys.isEmpty()) return s;

    // step 2: printing
    s.append(", {METHOD => 'table_att'");

    // print all reserved keys first
    for (ImmutableBytesWritable k : reservedKeys) {
      String key = Bytes.toString(k.get());
      String value = null;
      boolean needsQuotes = true;
      if (key.equals(SERVER_SET)) {
        needsQuotes = false;
        value = "[ ";
        boolean printComma = false;

        for (HServerAddress serverAddress : this.getServers()) {
          if (printComma) {
            value += ", ";
          }
          printComma = true;
          value += "'" + serverAddress.getHostNameWithPort() + "'";
        }
        value += " ]";
      } else {
        value = Bytes.toString(values.get(k).get());
      }
      s.append(", ");
      s.append(key);
      s.append(" => ");
      if (needsQuotes) {
        s.append('\'');
      }
      s.append(value);
      if (needsQuotes) {
        s.append('\'');
      }
    }

    if (!configKeys.isEmpty()) {
      // print all non-reserved, advanced config keys as a separate subset
      s.append(", ");
      s.append(HConstants.CONFIG).append(" => ");
      s.append("{");
      boolean printComma = false;
      for (ImmutableBytesWritable k : configKeys) {
        String key = Bytes.toString(k.get());
        String value = Bytes.toString(values.get(k).get());
        if (printComma) s.append(", ");
        printComma = true;
        s.append('\'').append(key).append('\'');
        s.append(" => ");
        s.append('\'').append(value).append('\'');
      }
      s.append('}');
    }

    s.append('}'); // end METHOD

    return s;
  }

  public static Map<String, String> getDefaultValues() {
    return Collections.unmodifiableMap(DEFAULT_VALUES);
  }

  /**
   * @see java.lang.Object#equals(java.lang.Object)
   */
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (!(obj instanceof HTableDescriptor)) {
      return false;
    }
    return compareTo((HTableDescriptor)obj) == 0;
  }

  /**
   * @see java.lang.Object#hashCode()
   */
  @Override
  public int hashCode() {
    int result = Bytes.hashCode(this.name);
    result ^= Byte.valueOf(TABLE_DESCRIPTOR_VERSION).hashCode();
    if (this.families != null && this.families.size() > 0) {
      for (HColumnDescriptor e: this.families.values()) {
        result ^= e.hashCode();
      }
    }
    result ^= values.hashCode();
    return result;
  }

  // Writable

  @Override
  public void readFields(DataInput in) throws IOException {
    int version = in.readInt();
    if (version < 3)
      throw new IOException("versions < 3 are not supported (and never existed!?)");
    // version 3+
    name = Bytes.readByteArray(in);
    nameAsString = Bytes.toString(this.name);
    setRootRegion(in.readBoolean());
    setMetaRegion(in.readBoolean());
    values.clear();
    int numVals = in.readInt();
    for (int i = 0; i < numVals; i++) {
      ImmutableBytesWritable key = new ImmutableBytesWritable();
      ImmutableBytesWritable value = new ImmutableBytesWritable();
      key.readFields(in);
      value.readFields(in);
      values.put(key, value);
    }
    families.clear();
    int numFamilies = in.readInt();
    for (int i = 0; i < numFamilies; i++) {
      HColumnDescriptor c = new HColumnDescriptor();
      c.readFields(in);
      families.put(c.getName(), c);
    }
    if (version < 4) {
      return;
    }
  }

  @Override
  public void write(DataOutput out) throws IOException {
    out.writeInt(TABLE_DESCRIPTOR_VERSION);
    Bytes.writeByteArray(out, name);
    out.writeBoolean(isRootRegion());
    out.writeBoolean(isMetaRegion());
    out.writeInt(values.size());
    for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> e:
        values.entrySet()) {
      e.getKey().write(out);
      e.getValue().write(out);
    }
    out.writeInt(families.size());
    for(Iterator<HColumnDescriptor> it = families.values().iterator();
        it.hasNext(); ) {
      HColumnDescriptor family = it.next();
      family.write(out);
    }
  }

  // Comparable

  @Override
  public int compareTo(final HTableDescriptor other) {
    int result = Bytes.compareTo(this.name, other.name);
    if (result == 0) {
      result = families.size() - other.families.size();
    }
    if (result == 0 && families.size() != other.families.size()) {
      result = Integer.valueOf(families.size()).compareTo(
          Integer.valueOf(other.families.size()));
    }
    if (result == 0) {
      for (Iterator<HColumnDescriptor> it = families.values().iterator(),
          it2 = other.families.values().iterator(); it.hasNext(); ) {
        result = it.next().compareTo(it2.next());
        if (result != 0) {
          break;
        }
      }
    }
    if (result == 0) {
      // punt on comparison for ordering, just calculate difference
      result = this.values.hashCode() - other.values.hashCode();
      if (result < 0)
        result = -1;
      else if (result > 0)
        result = 1;
    }
    return result;
  }

  /**
   * @return Immutable sorted map of families.
   */
  public Collection<HColumnDescriptor> getFamilies() {
    return Collections.unmodifiableCollection(this.families.values());
  }

  /**
   * @return Immutable sorted set of the keys of the families.
   */
  public Set<byte[]> getFamiliesKeys() {
    return Collections.unmodifiableSet(this.families.keySet());
  }

  public HColumnDescriptor[] getColumnFamilies() {
    return getFamilies().toArray(new HColumnDescriptor[0]);
  }

  /**
   * @param column
   * @return Column descriptor for the passed family name or the family on
   * passed in column.
   */
  public HColumnDescriptor getFamily(final byte [] column) {
    return this.families.get(column);
  }

  /**
   * @param column
   * @return Column descriptor for the passed family name or the family on
   * passed in column.
   */
  public HColumnDescriptor removeFamily(final byte [] column) {
    return this.families.remove(column);
  }

  /**
   * @param rootdir qualified path of HBase root directory
   * @param tableName name of table
   * @return path for table
   */
  public static Path getTableDir(Path rootdir, final byte [] tableName) {
    return new Path(rootdir, Bytes.toString(tableName));
  }

  /** Table descriptor for <core>-ROOT-</code> catalog table */
  public static final HTableDescriptor ROOT_TABLEDESC = new HTableDescriptor(
    HConstants.ROOT_TABLE_NAME,
    new HColumnDescriptor[] {
      new HColumnDescriptor(HConstants.CATALOG_FAMILY)
        // Ten is arbitrary number.  Keep versions to help debugging.
        .setMaxVersions(10)
        .setInMemory(true)
        .setBlocksize(8 * 1024)
        .setTimeToLive(HConstants.FOREVER)
        .setScope(HConstants.REPLICATION_SCOPE_LOCAL),
    });

  /** Table descriptor for <core>-ROOT-</code> catalog table with historian column
   * introduced to record sequenceid transition of meta table.*/
  public static final HTableDescriptor ROOT_TABLEDESC_WITH_HISTORIAN_COLUMN = new HTableDescriptor(
      HConstants.ROOT_TABLE_NAME,
      new HColumnDescriptor[] {
          new HColumnDescriptor(HConstants.CATALOG_FAMILY)
              // Ten is arbitrary number.  Keep versions to help debugging.
              .setMaxVersions(10)
              .setInMemory(true)
              .setBlocksize(8 * 1024)
              .setTimeToLive(HConstants.FOREVER)
              .setScope(HConstants.REPLICATION_SCOPE_LOCAL),
          new HColumnDescriptor(HConstants.CATALOG_HISTORIAN_FAMILY)
              .setMaxVersions(HConstants.ALL_VERSIONS)
              .setBlocksize(8 * 1024)
              // 13 weeks = 3 months TTL
              .setTimeToLive(13 * HConstants.WEEK_IN_SECONDS)
              .setScope(HConstants.REPLICATION_SCOPE_LOCAL)
      });

  /** Table descriptor for <code>.META.</code> catalog table */
  public static final HTableDescriptor META_TABLEDESC = new HTableDescriptor(
      HConstants.META_TABLE_NAME, new HColumnDescriptor[] {
          new HColumnDescriptor(HConstants.CATALOG_FAMILY)
              // Ten is arbitrary number.  Keep versions to help debugging.
              .setMaxVersions(10)
              .setInMemory(true)
              .setBlocksize(8 * 1024)
              .setScope(HConstants.REPLICATION_SCOPE_LOCAL),
          new HColumnDescriptor(HConstants.CATALOG_HISTORIAN_FAMILY)
              .setMaxVersions(HConstants.ALL_VERSIONS)
              .setBlocksize(8 * 1024)
              // 13 weeks = 3 months TTL
              .setTimeToLive(13 * HConstants.WEEK_IN_SECONDS)
              .setScope(HConstants.REPLICATION_SCOPE_LOCAL)
      });

  /**
   * @return true if all columns in the table should be read only
   */
  public boolean isWALDisabled() {
    return isSomething(DISABLE_WAL_KEY, false);
  }

  /**
   * @param disable should the wal be disabled for this table.
   * only.
   */
  public void setWALDisabled(final boolean disable) {
    setValue(DISABLE_WAL_KEY, disable? TRUE: FALSE);
  }

  /**
   * This is the class serialized into SERVER_SET_KEY to hold the list of servers.
   */
  @ThriftStruct
  public static class ServerSet {

    private Set<HServerAddress> servers = null;

    @ThriftConstructor
    public ServerSet(Set<HServerAddress> servers) {
      this.servers = servers;
    }

    @ThriftField(1)
    public Set<HServerAddress> getServers() {
      return servers;
    }

    public void setServers(Set<HServerAddress> servers) {
      this.servers = servers;
    }
  }

  public synchronized void setServers(Collection<HServerAddress> servers) {
    if (this.serverSet == null) {
      this.serverSet = new ServerSet(null);
    }

    if ( servers == null) {
      this.serverSet.servers = null;
      return;
    }

    if (servers.size() < 3 ) {
      throw new IllegalArgumentException("Must provide at least three servers.");
    }

    this.serverSet.setServers(ImmutableSet.copyOf(servers));
    try {
      this.setValue(SERVER_SET_KEY, Bytes.writeThriftBytes(serverSet, ServerSet.class));
    } catch (Exception e) {
      LOG.error("Error serializing server set from HTableDescriptor", e);
    }
  }

  public synchronized Set<HServerAddress> getServers() {
    if (serverSet == null) {
      byte[] serverSetBytes = getValue(SERVER_SET_KEY);
      if (serverSetBytes != null) {
        try {
          serverSet = Bytes.readThriftBytes(serverSetBytes, ServerSet.class);
        } catch (Exception e) {
          LOG.error("Error de-serializing server set into HTableDescriptor", e);
        }
      }
      if (serverSet == null) {
        serverSet = new ServerSet(null);
      }
    }
    return this.serverSet.getServers();
  }

  /**
   * @param conf
   * @return true if the meta region seqid recording is enabled
   */
  public static boolean isMetaregionSeqidRecordEnabled(Configuration conf) {
    return conf != null ? conf.getBoolean(METAREGION_SEQID_RECORD_ENABLED, false) : false;
  }
}
