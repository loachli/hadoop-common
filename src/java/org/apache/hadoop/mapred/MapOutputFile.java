/**
 * Copyright 2005 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.IOException;
import java.util.logging.Level;

import java.io.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.mapred.TaskTracker.MapOutputServer;

/** A local file to be transferred via the {@link MapOutputProtocol}. */ 
class MapOutputFile implements Writable, Configurable {

    static {                                      // register a ctor
      WritableFactories.setFactory
        (MapOutputFile.class,
         new WritableFactory() {
           public Writable newInstance() { return new MapOutputFile(); }
         });
    }

  private String mapTaskId;
  private String reduceTaskId;
  private int mapId;
  private int partition;
  private long size;
  
  /** Permits reporting of file copy progress. */
  public interface ProgressReporter {
    void progress(float progress) throws IOException;
  }

  private ThreadLocal REPORTERS = new ThreadLocal();
  private JobConf jobConf;
  
  public void setProgressReporter(ProgressReporter reporter) {
    REPORTERS.set(reporter);
  }

  /** Create a local map output file name.
   * @param mapTaskId a map task id
   * @param partition a reduce partition
   */
  public Path getOutputFile(String mapTaskId, int partition)
    throws IOException {
    return this.jobConf.getLocalPath(mapTaskId+"/part-"+partition+".out");
  }

  /** Create a local reduce input file name.
   * @param mapTaskId a map task id
   * @param reduceTaskId a reduce task id
   */
  public Path getInputFile(int mapId, String reduceTaskId)
    throws IOException {
    // TODO *oom* should use a format here
    return this.jobConf.getLocalPath(reduceTaskId+"/map_"+mapId+".out");
  }

  /** Removes all of the files related to a task. */
  public void removeAll(String taskId) throws IOException {
    this.jobConf.deleteLocalFiles(taskId);
  }

  /** 
   * Removes all contents of temporary storage.  Called upon 
   * startup, to remove any leftovers from previous run.
   */
  public void cleanupStorage() throws IOException {
    this.jobConf.deleteLocalFiles();
  }

  /** Construct a file for transfer. */
  public MapOutputFile() { 
  }
  
  public MapOutputFile(String mapTaskId, String reduceTaskId, 
                       int mapId, int partition) {
    this.mapTaskId = mapTaskId;
    this.reduceTaskId = reduceTaskId;
    this.mapId = mapId;
    this.partition = partition;
  }

  private FileSystem getLocalFs() throws IOException {
    return FileSystem.getNamed("local", this.jobConf);
  }
  
  public long getSize() {
    return size;
  }

  public void write(DataOutput out) throws IOException {
    UTF8.writeString(out, mapTaskId);
    UTF8.writeString(out, reduceTaskId);
    out.writeInt(mapId);
    out.writeInt(partition);
    
    Path file = getOutputFile(mapTaskId, partition);
    FSDataInputStream in = null;
    try {
      // write the length-prefixed file content to the wire
      this.size = getLocalFs().getLength(file);
      out.writeLong(this.size);
      in = getLocalFs().open(file);
    } catch (FileNotFoundException e) {
      TaskTracker.LOG.log(Level.SEVERE, "Can't open map output:" + file, e);
      ((MapOutputServer)Server.get()).getTaskTracker().mapOutputLost(mapTaskId);
      throw e;
    }
    try {
      byte[] buffer = new byte[65536];
      int l  = 0;
      
      while (l != -1) {
        out.write(buffer, 0, l);
        try {
          l = in.read(buffer);
        } catch (IOException e) {
          TaskTracker.LOG.log(Level.SEVERE,"Can't read map output:" + file, e);
          ((MapOutputServer)Server.get()).getTaskTracker().mapOutputLost(mapTaskId);
          throw e;
        }
      }
    } finally {
      in.close();
    }
  }

  public void readFields(DataInput in) throws IOException {
    this.mapTaskId = UTF8.readString(in);
    this.reduceTaskId = UTF8.readString(in);
    this.mapId = in.readInt();
    this.partition = in.readInt();

    ProgressReporter reporter = (ProgressReporter)REPORTERS.get();

    // read the length-prefixed file content into a local file
    Path file = getInputFile(mapId, reduceTaskId);
    long length = in.readLong();
    this.size = length;
    
    float progPerByte = 1.0f / length;
    long unread = length;
    FSDataOutputStream out = getLocalFs().create(file);
    try {
      byte[] buffer = new byte[65536];
      while (unread > 0) {
          int bytesToRead = (int)Math.min(unread, buffer.length);
          in.readFully(buffer, 0, bytesToRead);
          out.write(buffer, 0, bytesToRead);
          unread -= bytesToRead;
          if (reporter != null) {
            reporter.progress((length-unread)*progPerByte);
          }
      }
    } finally {
      out.close();
    }
  }

  public void setConf(Configuration conf) {
    if (conf instanceof JobConf) {
      jobConf = (JobConf) conf;
    } else {
      this.jobConf = new JobConf(conf);
    }
  }

  public Configuration getConf() {
    return this.jobConf;
  }

}
