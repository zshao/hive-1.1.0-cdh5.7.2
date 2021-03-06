/**
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

package org.apache.hadoop.hive.ql.exec;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.common.ValidReadTxnList;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.ql.io.AcidInputFormat;
import org.apache.hadoop.hive.ql.io.AcidOutputFormat;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.io.RecordIdentifier;
import org.apache.hadoop.hive.ql.io.RecordUpdater;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.DynamicPartitionCtx;
import org.apache.hadoop.hive.ql.plan.ExprNodeColumnDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.FileSinkDesc;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.apache.hadoop.hive.ql.stats.StatsAggregator;
import org.apache.hadoop.hive.ql.stats.StatsPublisher;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.SerDeStats;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.Progressable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Tests for {@link org.apache.hadoop.hive.ql.exec.FileSinkOperator}
 */
public class TestFileSinkOperator {
  private static String PARTCOL_NAME = "partval";
  static final private Log LOG = LogFactory.getLog(TestFileSinkOperator.class.getName());

  private static File tmpdir;
  private static TableDesc nonAcidTableDescriptor;
  private static TableDesc acidTableDescriptor;
  private static ObjectInspector inspector;
  private static List<TFSORow> rows;
  private static ValidTxnList txnList;

  private Path basePath;
  private JobConf jc;

  @BeforeClass
  public static void classSetup() {
    Properties properties = new Properties();
    properties.setProperty(serdeConstants.SERIALIZATION_LIB, TFSOSerDe.class.getName());
    nonAcidTableDescriptor = new TableDesc(TFSOInputFormat.class, TFSOOutputFormat.class, properties);
    properties = new Properties(properties);
    properties.setProperty(hive_metastoreConstants.BUCKET_COUNT, "1");
    acidTableDescriptor = new TableDesc(TFSOInputFormat.class, TFSOOutputFormat.class, properties);

    tmpdir = new File(System.getProperty("java.io.tmpdir") + System.getProperty("file.separator") +
        "testFileSinkOperator");
    tmpdir.mkdir();
    tmpdir.deleteOnExit();
    txnList = new ValidReadTxnList(new long[]{}, 2);
  }

  @Test
  public void testNonAcidWrite() throws Exception {
    setBasePath("write");
    setupData(DataFormat.SIMPLE);
    FileSinkOperator op = getFileSink(AcidUtils.Operation.NOT_ACID, false, 0);
    processRows(op);
    confirmOutput();
  }

  @Test
  public void testInsert() throws Exception {
    setBasePath("insert");
    setupData(DataFormat.SIMPLE);
    FileSinkOperator op = getFileSink(AcidUtils.Operation.INSERT, false, 1);
    processRows(op);
    Assert.assertEquals("10", TFSOStatsPublisher.stats.get(StatsSetupConst.ROW_COUNT));
    confirmOutput();
  }

  @Test
  public void testUpdate() throws Exception {
    setBasePath("update");
    setupData(DataFormat.WITH_RECORD_ID);
    FileSinkOperator op = getFileSink(AcidUtils.Operation.UPDATE, false, 2);
    processRows(op);
    Assert.assertEquals("0", TFSOStatsPublisher.stats.get(StatsSetupConst.ROW_COUNT));
    confirmOutput();
  }

  @Test
  public void testDelete() throws Exception {
    setBasePath("delete");
    setupData(DataFormat.WITH_RECORD_ID);
    FileSinkOperator op = getFileSink(AcidUtils.Operation.DELETE, false, 2);
    processRows(op);
    Assert.assertEquals("-10", TFSOStatsPublisher.stats.get(StatsSetupConst.ROW_COUNT));
    confirmOutput();
  }

  @Test
  public void testNonAcidDynamicPartitioning() throws Exception {
    setBasePath("writeDP");
    setupData(DataFormat.WITH_PARTITION_VALUE);
    FileSinkOperator op = getFileSink(AcidUtils.Operation.NOT_ACID, true, 0);
    processRows(op);
    confirmOutput();
  }


  @Test
  public void testInsertDynamicPartitioning() throws Exception {
    setBasePath("insertDP");
    setupData(DataFormat.WITH_PARTITION_VALUE);
    FileSinkOperator op = getFileSink(AcidUtils.Operation.INSERT, true, 1);
    processRows(op);
    // We only expect 5 here because we'll get whichever of the partitions published its stats
    // last.
    Assert.assertEquals("5", TFSOStatsPublisher.stats.get(StatsSetupConst.ROW_COUNT));
    confirmOutput();
  }

  @Test
  public void testUpdateDynamicPartitioning() throws Exception {
    setBasePath("updateDP");
    setupData(DataFormat.WITH_RECORD_ID_AND_PARTITION_VALUE);
    FileSinkOperator op = getFileSink(AcidUtils.Operation.UPDATE, true, 2);
    processRows(op);
    Assert.assertEquals("0", TFSOStatsPublisher.stats.get(StatsSetupConst.ROW_COUNT));
    confirmOutput();
  }

  @Test
  public void testDeleteDynamicPartitioning() throws Exception {
    setBasePath("deleteDP");
    setupData(DataFormat.WITH_RECORD_ID_AND_PARTITION_VALUE);
    FileSinkOperator op = getFileSink(AcidUtils.Operation.DELETE, true, 2);
    processRows(op);
    // We only expect -5 here because we'll get whichever of the partitions published its stats
    // last.
    Assert.assertEquals("-5", TFSOStatsPublisher.stats.get(StatsSetupConst.ROW_COUNT));
    confirmOutput();
  }


  @Before
  public void setup() throws Exception {
    jc = new JobConf();
    jc.set(StatsSetupConst.STATS_TMP_LOC, File.createTempFile("TestFileSinkOperator",
        "stats").getPath());
    jc.set(HiveConf.ConfVars.HIVE_STATS_DEFAULT_PUBLISHER.varname,
        TFSOStatsPublisher.class.getName());
    jc.set(HiveConf.ConfVars.HIVE_STATS_DEFAULT_AGGREGATOR.varname,
        TFSOStatsAggregator.class.getName());
    jc.set(HiveConf.ConfVars.HIVESTATSDBCLASS.varname, "custom");
  }

  private void setBasePath(String testName) {
    basePath = new Path(new File(tmpdir, testName).getPath());

  }

  private enum DataFormat {SIMPLE, WITH_RECORD_ID, WITH_PARTITION_VALUE,
    WITH_RECORD_ID_AND_PARTITION_VALUE};

  private void setupData(DataFormat format) {

    // Build object inspector
    inspector = ObjectInspectorFactory.getReflectionObjectInspector
        (TFSORow.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
    rows = new ArrayList<TFSORow>();

    switch (format) {
      case SIMPLE:
        // Build rows
        for (int i = 0; i < 10; i++) {
          rows.add(
              new TFSORow(
                  new Text("mary had a little lamb")
              )
          );
        }
        break;

      case WITH_RECORD_ID:
        for (int i = 0; i < 10; i++) {
          rows.add(
              new TFSORow(
                  new Text("its fleect was white as snow"),
                  new RecordIdentifier(1, 1, i)
              )
          );
        }
        break;

      case WITH_PARTITION_VALUE:
        for (int i = 0; i < 10; i++) {
          rows.add(
              new TFSORow(
                  new Text("its fleect was white as snow"),
                  (i < 5) ? new Text("Monday") : new Text("Tuesday")
              )
          );
        }
        break;

      case WITH_RECORD_ID_AND_PARTITION_VALUE:
        for (int i = 0; i < 10; i++) {
          rows.add(
              new TFSORow(
                  new Text("its fleect was white as snow"),
                  (i < 5) ? new Text("Monday") : new Text("Tuesday"),
                  new RecordIdentifier(1, 1, i)
              )
          );
        }
        break;

      default:
        throw new RuntimeException("Unknown option!");
    }
  }

  private FileSinkOperator getFileSink(AcidUtils.Operation writeType,
                                       boolean dynamic,
                                       long txnId) throws IOException, HiveException {
    TableDesc tableDesc = null;
    switch (writeType) {
      case DELETE:
      case UPDATE:
      case INSERT:
        tableDesc = acidTableDescriptor;
        break;

      case NOT_ACID:
        tableDesc = nonAcidTableDescriptor;
        break;
    }
    FileSinkDesc desc = null;
    if (dynamic) {
      ArrayList<ExprNodeDesc> partCols = new ArrayList<ExprNodeDesc>(1);
      partCols.add(new ExprNodeColumnDesc(TypeInfoFactory.stringTypeInfo, PARTCOL_NAME, "a", true));
      Map<String, String> partColMap= new LinkedHashMap<String, String>(1);
      partColMap.put(PARTCOL_NAME, null);
      DynamicPartitionCtx dpCtx = new DynamicPartitionCtx(null, partColMap, "Sunday", 100);
      Map<String, String> partColNames = new HashMap<String, String>(1);
      partColNames.put(PARTCOL_NAME, PARTCOL_NAME);
      dpCtx.setInputToDPCols(partColNames);
      desc = new FileSinkDesc(basePath, tableDesc, false, 1, false, false, 1, 1, partCols, dpCtx);
    } else {
      desc = new FileSinkDesc(basePath, tableDesc, false);
    }
    desc.setWriteType(writeType);
    desc.setGatherStats(true);
    if (txnId > 0) desc.setTransactionId(txnId);
    if (writeType != AcidUtils.Operation.NOT_ACID) desc.setTransactionId(1L);

    FileSinkOperator op = (FileSinkOperator)OperatorFactory.get(FileSinkDesc.class);
    op.setConf(desc);
    op.initialize(jc, new ObjectInspector[]{inspector});
    return op;
  }

  private void processRows(FileSinkOperator op) throws HiveException {
    for (TFSORow r : rows) op.processOp(r, 0);
    op.jobCloseOp(jc, true);
    op.close(false);
  }

  private void confirmOutput() throws IOException, SerDeException {
    Path[] paths = findFilesInBasePath();
    TFSOInputFormat input = new TFSOInputFormat();
    FileInputFormat.setInputPaths(jc, paths);

    InputSplit[] splits = input.getSplits(jc, 1);
    RecordReader<NullWritable, TFSORow> reader = input.getRecordReader(splits[0], jc,
        Mockito.mock(Reporter.class));
    NullWritable key = reader.createKey();
    TFSORow value = reader.createValue();
    List<TFSORow> results = new ArrayList<TFSORow>(rows.size());
    List<TFSORow> sortedRows = new ArrayList<TFSORow>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      Assert.assertTrue(reader.next(key, value));
      results.add(new TFSORow(value));
      sortedRows.add(new TFSORow(rows.get(i)));
    }
    Assert.assertFalse(reader.next(key, value));
    Collections.sort(results);
    Collections.sort(sortedRows);
    for (int i = 0; i < rows.size(); i++) {
      Assert.assertTrue(sortedRows.get(i).equals(results.get(i)));
    }

  }

  private Path[] findFilesInBasePath() throws IOException {
    Path parent = basePath.getParent();
    String last = basePath.getName();
    Path tmpPath = new Path(parent, "_tmp." + last);
    FileSystem fs = basePath.getFileSystem(jc);
    List<Path> paths = new ArrayList<Path>();
    recurseOnPath(tmpPath, fs, paths);
    return paths.toArray(new Path[paths.size()]);
  }

  private void recurseOnPath(Path p, FileSystem fs, List<Path> paths) throws IOException {
    if (fs.getFileStatus(p).isDir()) {
      FileStatus[] stats = fs.listStatus(p);
      for (FileStatus stat : stats) recurseOnPath(stat.getPath(), fs, paths);
    } else {
      paths.add(p);
    }
  }

  private static class TFSORow implements WritableComparable<TFSORow> {
    private RecordIdentifier recId;
    private Text data;
    private Text partVal;

    TFSORow() {
      this(null, null, null);
    }

    TFSORow(Text t) {
      this(t, null, null);
    }

    TFSORow(Text t, Text pv) {
      this(t, pv, null);
    }

    TFSORow(Text t, RecordIdentifier ri) {
      this(t, null, ri);
    }

    TFSORow(Text t, Text pv, RecordIdentifier ri) {
      data = t;
      partVal = pv;
      recId = ri;

    }

    TFSORow(TFSORow other) {
      this(other.data, other.partVal, other.recId);
    }

    @Override
    public void write(DataOutput dataOutput) throws IOException {
      data.write(dataOutput);
      if (partVal == null) {
        dataOutput.writeBoolean(false);
      } else {
        dataOutput.writeBoolean(true);
        partVal.write(dataOutput);
      }
      if (recId == null) {
        dataOutput.writeBoolean(false);
      } else {
        dataOutput.writeBoolean(true);
        recId.write(dataOutput);
      }
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
      data = new Text();
      data.readFields(dataInput);
      boolean notNull = dataInput.readBoolean();
      if (notNull) {
        partVal = new Text();
        partVal.readFields(dataInput);
      }
      notNull = dataInput.readBoolean();
      if (notNull) {
        recId = new RecordIdentifier();
        recId.readFields(dataInput);
      }
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof TFSORow) {
        TFSORow other = (TFSORow) obj;
        if (data == null && other.data == null) return checkPartVal(other);
        else if (data == null) return false;
        else if (data.equals(other.data)) return checkPartVal(other);
        else return false;
      } else {
        return false;
      }
    }

    private boolean checkPartVal(TFSORow other) {
      if (partVal == null && other.partVal == null) return checkRecId(other);
      else if (partVal == null) return false;
      else if (partVal.equals(other.partVal)) return checkRecId(other);
      else return false;
    }

    private boolean checkRecId(TFSORow other) {
      if (recId == null && other.recId == null) return true;
      else if (recId == null) return false;
      else return recId.equals(other.recId);
    }

    @Override
    public int compareTo(TFSORow other) {
      if (recId == null && other.recId == null) {
        return comparePartVal(other);
      } else if (recId == null) {
        return -1;
      } else {
        int rc = recId.compareTo(other.recId);
        if (rc == 0) return comparePartVal(other);
        else return rc;
      }
    }

    private int comparePartVal(TFSORow other) {
      if (partVal == null && other.partVal == null) {
        return compareData(other);
      } else if (partVal == null) {
        return -1;
      } else {
        int rc = partVal.compareTo(other.partVal);
        if (rc == 0) return compareData(other);
        else return rc;
      }
    }

    private int compareData(TFSORow other) {
      if (data == null && other.data == null) return 0;
      else if (data == null) return -1;
      else return data.compareTo(other.data);
    }
  }

  private static class TFSOInputFormat extends FileInputFormat<NullWritable, TFSORow>
                                       implements AcidInputFormat<NullWritable, TFSORow> {

    FSDataInputStream in[] = null;
    int readingFrom = -1;

    @Override
    public RecordReader<NullWritable, TFSORow> getRecordReader(
        InputSplit inputSplit, JobConf entries, Reporter reporter) throws IOException {
      if (in == null) {
        Path paths[] = FileInputFormat.getInputPaths(entries);
        in = new FSDataInputStream[paths.length];
        FileSystem fs = paths[0].getFileSystem(entries);
        for (int i = 0; i < paths.length; i++) {
          in[i] = fs.open(paths[i]);
        }
        readingFrom = 0;
      }
      return new RecordReader<NullWritable, TFSORow>() {

        @Override
        public boolean next(NullWritable nullWritable, TFSORow tfsoRecord) throws
            IOException {
          try {
            tfsoRecord.readFields(in[readingFrom]);
            return true;
          } catch (EOFException e) {
            in[readingFrom].close();
            if (++readingFrom >= in.length) return false;
            else return next(nullWritable, tfsoRecord);
          }
        }

        @Override
        public NullWritable createKey() {
          return NullWritable.get();
        }

        @Override
        public TFSORow createValue() {
          return new TFSORow();
        }

        @Override
        public long getPos() throws IOException {
          return 0L;
        }

        @Override
        public void close() throws IOException {

        }

        @Override
        public float getProgress() throws IOException {
          return 0.0f;
        }
      };
    }

    @Override
    public RowReader<TFSORow> getReader(InputSplit split,
                                           Options options) throws
        IOException {
      return null;
    }

    @Override
    public RawReader<TFSORow> getRawReader(Configuration conf,
                                              boolean collapseEvents,
                                              int bucket,
                                              ValidTxnList validTxnList,
                                              Path baseDirectory,
                                              Path[] deltaDirectory) throws
        IOException {
      return null;
    }

    @Override
    public boolean validateInput(FileSystem fs, HiveConf conf, ArrayList<FileStatus> files) throws
        IOException {
      return false;
    }
  }

  public static class TFSOOutputFormat extends FileOutputFormat<NullWritable, TFSORow>
      implements AcidOutputFormat<NullWritable, TFSORow> {
    List<TFSORow> records = new ArrayList<TFSORow>();
    long numRecordsAdded = 0;
    FSDataOutputStream out = null;

    @Override
    public RecordUpdater getRecordUpdater(final Path path, final Options options) throws
        IOException {

      final StructObjectInspector inspector = (StructObjectInspector)options.getInspector();
      return new RecordUpdater() {
        @Override
        public void insert(long currentTransaction, Object row) throws IOException {
          addRow(row);
          numRecordsAdded++;
        }

        @Override
        public void update(long currentTransaction, Object row) throws IOException {
          addRow(row);
        }

        @Override
        public void delete(long currentTransaction, Object row) throws IOException {
          addRow(row);
          numRecordsAdded--;
        }

        private void addRow(Object row) {
          assert row instanceof TFSORow : "Expected TFSORow but got " +
              row.getClass().getName();
          records.add((TFSORow)row);
        }

        @Override
        public void flush() throws IOException {
          if (out == null) {
            FileSystem fs = path.getFileSystem(options.getConfiguration());
            out = fs.create(path);
          }
          for (TFSORow r : records) r.write(out);
          records.clear();
          out.flush();
        }

        @Override
        public void close(boolean abort) throws IOException {
          flush();
          out.close();
        }

        @Override
        public SerDeStats getStats() {
          SerDeStats stats = new SerDeStats();
          stats.setRowCount(numRecordsAdded);
          return stats;
        }
      };
    }

    @Override
    public FileSinkOperator.RecordWriter getRawRecordWriter(Path path,
                                                            Options options) throws
        IOException {
      return null;
    }

    @Override
    public FileSinkOperator.RecordWriter getHiveRecordWriter(final JobConf jc,
                                                             final Path finalOutPath,
                                                             Class<? extends Writable> valueClass,
                                                             boolean isCompressed,
                                                             Properties tableProperties,
                                                             Progressable progress)
        throws IOException {
      return new FileSinkOperator.RecordWriter() {
        @Override
        public void write(Writable w) throws IOException {
          Assert.assertTrue(w instanceof TFSORow);
          records.add((TFSORow) w);
        }

        @Override
        public void close(boolean abort) throws IOException {
          if (out == null) {
            FileSystem fs = finalOutPath.getFileSystem(jc);
            out = fs.create(finalOutPath);
          }
          for (TFSORow r : records) r.write(out);
          records.clear();
          out.flush();
          out.close();
        }
      };
    }

    @Override
    public RecordWriter<NullWritable, TFSORow> getRecordWriter(
        FileSystem fileSystem, JobConf entries, String s, Progressable progressable) throws
        IOException {
      return null;
    }

    @Override
    public void checkOutputSpecs(FileSystem fileSystem, JobConf entries) throws IOException {

    }
  }

  public static class TFSOSerDe implements SerDe {

    @Override
    public void initialize(Configuration conf, Properties tbl) throws SerDeException {

    }

    @Override
    public Class<? extends Writable> getSerializedClass() {
      return TFSORow.class;
    }

    @Override
    public Writable serialize(Object obj, ObjectInspector objInspector) throws SerDeException {
      assert obj instanceof TFSORow : "Expected TFSORow or decendent, got "
          + obj.getClass().getName();
      return (TFSORow)obj;
    }

    @Override
    public Object deserialize(Writable blob) throws SerDeException {
      assert blob instanceof TFSORow : "Expected TFSORow or decendent, got "
          + blob.getClass().getName();
      return blob;
    }

    @Override
    public ObjectInspector getObjectInspector() throws SerDeException {
      return null;
    }

    @Override
    public SerDeStats getSerDeStats() {
      return null;
    }
  }

  public static class TFSOStatsPublisher implements StatsPublisher {
    static Map<String, String> stats;

    @Override
    public boolean init(Configuration hconf) {
      return true;
    }

    @Override
    public boolean connect(Configuration hconf) {
      return true;
    }

    @Override
    public boolean publishStat(String fileID, Map<String, String> stats) {
      this.stats = stats;
      return true;
    }

    @Override
    public boolean closeConnection() {
      return true;
    }
  }

  public static class TFSOStatsAggregator implements StatsAggregator {

    @Override
    public boolean connect(Configuration hconf, Task sourceTask) {
      return true;
    }

    @Override
    public String aggregateStats(String keyPrefix, String statType) {
      return null;
    }

    @Override
    public boolean closeConnection() {
      return true;
    }

    @Override
    public boolean cleanUp(String keyPrefix) {
      return true;
    }
  }
}
