/*===========================================================================
 *
 *                            PUBLIC DOMAIN NOTICE
 *               National Center for Biotechnology Information
 *
 *  This software/database is a "United States Government Work" under the
 *  terms of the United States Copyright Act.  It was written as part of
 *  the author's official duties as a United States Government employee and
 *  thus cannot be copyrighted.  This software/database is freely available
 *  to the public for use. The National Library of Medicine and the U.S.
 *  Government have not placed any restriction on its use or reproduction.
 *
 *  Although all reasonable efforts have been taken to ensure the accuracy
 *  and reliability of the software and data, the NLM and the U.S.
 *  Government do not and cannot warrant the performance or results that
 *  may be obtained by using this software or data. The NLM and the U.S.
 *  Government disclaim all warranties, express or implied, including
 *  warranties of performance, merchantability or fitness for any particular
 *  purpose.
 *
 *  Please cite the author in any work or product based on this material.
 *
 *===========================================================================
 *
 */

package gov.nih.nlm.ncbi.blastjni;

import io.opencensus.trace.Span;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.util.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.*;
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder;
import org.apache.spark.sql.catalyst.encoders.RowEncoder;
import org.apache.spark.sql.streaming.DataStreamReader;
import org.apache.spark.sql.streaming.DataStreamWriter;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.storage.StorageLevel;
import org.json.JSONObject;

public final class BLAST_DRIVER implements Serializable {
    private static SparkSession sparksession;
    // Only one spark context allowed per JVM
    private static JavaSparkContext javasparkcontext;
    private final String db_location = "/tmp/blast/db";
    private BLAST_SETTINGS settings;
    // private int max_partitions = 0;
    private BLAST_DB_SETTINGS dbsettings;

    public static void main(String[] args) throws Exception {
        boolean result;

        BLAST_DRIVER driver = new BLAST_DRIVER();

        result = driver.init(args);
        if (!result) {
            driver.shutdown();
            System.exit(1);
        }

        if (!driver.make_partitions()) {
            driver.shutdown();
            System.exit(2);
        }

        DataStreamWriter<Row> prelim_stream = driver.make_prelim_stream();
        DataStreamWriter<Row> traceback_stream = driver.make_traceback_stream();
        result = driver.run_streams(prelim_stream, traceback_stream);
        if (!result) {
            driver.shutdown();
            System.exit(3);
        }
    }

    public void BLAST_DRIVER() {}

    public boolean init(final String[] args) {
        if (args.length != 1) {
            System.out.println("settings json-file missing");
            return false;
        }
        final String ini_path = args[0];

        final String appName = "experiment_dataset";

        final Tracer tracer = Tracing.getTracer();

        Span rootSpan = tracer.spanBuilderWithExplicitParent(appName, null).startSpan();
        rootSpan.addAnnotation("Annotation to the root Span before child is created.");
        Span childSpan =
            tracer.spanBuilderWithExplicitParent(appName + "_blaster", rootSpan).startSpan();
        childSpan.addAnnotation("Annotation to the child Span");
        childSpan.end();
        rootSpan.addAnnotation("Annotation to the root Span after child is ended.");
        rootSpan.end();

        settings = BLAST_SETTINGS_READER.read_from_json(ini_path, appName);
        System.out.println(String.format("settings read from '%s'", ini_path));
        if (!settings.valid()) {
            System.out.println(settings.missing());
            return false;
        }
        System.out.println(settings.toString());

        SparkSession.Builder builder = new SparkSession.Builder();
        builder.appName(settings.appName);

        SparkConf conf = new SparkConf();
        conf.setAppName(settings.appName);

        // GCP NIC has 2 gbit/sec/vCPU, 16 gbit max, ~8 gbit for single stream
        // LZ4 typically saturates at 800MB/sec
        // I'm only seeing ~2.4 gbit/core, so LZ4 a win
        conf.set("spark.broadcast.compress", "true");
        // GCP non-ssd persistent disk is <= 120MB/sec
        conf.set("spark.shuffle.compress", "true");

        dbsettings = settings.db_list;
        /*
           Collection<BLAST_DB_SETTING> ldb_set = dbsettings.list();
           for (BLAST_DB_SETTING db_set : ldb_set)
           max_partitions = Math.max(max_partitions, db_set.num_partitions);

           max_partitions = settings.num_executors;
           System.out.println(String.format("max_partitions is %d", max_partitions));
           */

        // conf.set("spark.default.parallelism", Integer.toString(max_partitions));
        conf.set("spark.dynamicAllocation.enabled", Boolean.toString(settings.with_dyn_alloc));
        conf.set("spark.eventLog.enabled", "false");

        if (settings.num_executor_cores > 0)
            conf.set("spark.executor.cores", String.format("%d", settings.num_executor_cores));
        // These will appear in
        // executor:/var/log/hadoop-yarn/userlogs/applica*/container*/stdout
        // FIX: +UseParallelGC ? Increase G1GC latency?
        conf.set("spark.executor.extraJavaOptions", "-XX:+PrintCompilation -verbose:gc");
        if (settings.num_executors > 0)
            conf.set("spark.executor.instances", String.format("%d", settings.num_executors));
        if (!settings.executor_memory.isEmpty())
            conf.set("spark.executor.memory", settings.executor_memory);

        conf.set("spark.locality.wait", settings.locality_wait);

        // -> process, node, rack, any
        if (settings.scheduler_fair)
            conf.set(
                    "spark.scheduler.mode",
                    "FAIR"); // FIX, need fairscheduler.xml, see /etc/spark/conf.dist/fair_scheduler.xml
        conf.set("spark.shuffle.reduceLocality.enabled", "false");

        // conf.set("spark.sql.shuffle.partitions", Integer.toString(max_partitions));
        // conf.set("spark.sql.streaming.schemaInference", "true");
        String warehouseLocation = new File("spark-warehouse").getAbsolutePath();
        conf.set("spark.sql.warehouse.dir", warehouseLocation);

        conf.set("spark.streaming.stopGracefullyOnShutdown", "true");
        conf.set("spark.streaming.receiver.maxRate", String.format("%d", settings.max_backlog));

        conf.set("spark.ui.enabled", "true");

        builder.config(conf);
        System.out.println("Configuration is");
        System.out.println("----------------");
        System.out.println(conf.toDebugString().replace("\n", "\n  "));
        System.out.println();
        builder.enableHiveSupport();

        sparksession = builder.getOrCreate();
        javasparkcontext = new JavaSparkContext(sparksession.sparkContext());
        javasparkcontext.setLogLevel(settings.spark_log_level);

        // send the given files to all nodes
        List<String> files_to_transfer = new ArrayList<>();
        files_to_transfer.add("libblastjni.so");
        files_to_transfer.add("log4j.properties");
        for (String a_file : files_to_transfer) {
            javasparkcontext.addFile(a_file);
        }

        return true;
    }

    private boolean make_partitions() {
        StructType parts_schema = StructType.fromDDL("db string, partition_num int");

        ArrayList<Row> data = new ArrayList<Row>();

        BLAST_DB_SETTING nt = dbsettings.get("nt");
        for (int i = 0; i < nt.num_partitions; i++) {
            Row r = RowFactory.create("nt", Integer.valueOf(i));
            data.add(r);
        }

        BLAST_DB_SETTING nr = dbsettings.get("nr");
        for (int i = 0; i < nr.num_partitions; i++) {
            Row r = RowFactory.create("nr", Integer.valueOf(i));
            data.add(r);
        }

        Dataset<Row> parts = sparksession.createDataFrame(data, parts_schema);
        Dataset<Row> blast_partitions =
            parts
            .sort("partition_num", "db")
            .sortWithinPartitions("partition_num", "db")
            .repartition(parts.col("partition_num"))
            // .repartition(settings.num_executors, parts.col("partition_num"))
            .persist(StorageLevel.MEMORY_AND_DISK());

        blast_partitions.show();
        blast_partitions.createOrReplaceTempView("blast_partitions");

        return true;
    }

    private Dataset<Row> prelim_parsed(Dataset<Row> queries) {
        System.out.print("queries schema is ");
        queries.printSchema();

        StructType parsed_schema =
            StructType.fromDDL(
                    "protocol string, "
                    + "RID string, "
                    + "db_tag string, "
                    + "top_N_prelim int, "
                    + "top_N_traceback int, "
                    + "query_seq string, "
                    + "query_url string, "
                    + "program string, "
                    + "blast_params string, "
                    + "StartTime timestamp");

        ExpressionEncoder<Row> encoder = RowEncoder.apply(parsed_schema);

        Dataset<Row> parsed =
            queries.map(
                    inrow -> {
                        Logger logger = LogManager.getLogger(BLAST_DRIVER.class);
                        // single column of "value"
                        String value = inrow.getString(inrow.fieldIndex("value"));
                        logger.log(Level.INFO, "value is " + value);

                        JSONObject json = new JSONObject(value);
                        logger.log(Level.DEBUG, "JSON was " + json.toString(2));
                        String protocol = json.getString("protocol");
                        if (protocol.equals("1.0")) {
                            String rid = json.getString("RID");
                            String db_tag = json.getString("db_tag");
                            Integer top_N_prelim = json.getInt("top_N_prelim");
                            Integer top_N_traceback = json.getInt("top_N_traceback");
                            String query_seq = json.getString("query_seq");
                            String query_url = json.getString("query_url");
                            String program = json.getString("program");
                            JSONObject blast_params = json.getJSONObject("blast_params");
                            String blast_params_str = blast_params.toString();

                            String starttime = json.getString("StartTime");
                            Timestamp ts = new Timestamp(System.currentTimeMillis());

                            Row outrow =
                                RowFactory.create(
                                        protocol,
                                        rid,
                                        db_tag,
                                        top_N_prelim,
                                        top_N_traceback,
                                        query_seq,
                                        query_url,
                                        program,
                                        blast_params_str,
                                        ts);

                            logger.log(Level.INFO, "queries outrow is  " + outrow.mkString(" : "));

                            return outrow;
                        } else {
                            logger.log(Level.ERROR, "Unknown protocol:" + protocol);
                            Row outrow = RowFactory.create(protocol, "ERROR");
                            return outrow;
                        }
                    }, // mapfunc
                          encoder); // map

        System.out.print("parsed schema is ");
        parsed.printSchema();
        parsed.createOrReplaceTempView("parsed");
        return parsed;
    }

    private Dataset<Row> prelim_joined(Dataset<Row> parsed) {
        Dataset<Row> joined =
            sparksession.sql(
                    "select * "
                    + "from parsed, blast_partitions "
                    + "where substr(parsed.db_tag,0,2)=blast_partitions.db "
                    + "distribute by partition_num");
        joined.createOrReplaceTempView("joined");
        System.out.print("joined schema is ");
        joined.printSchema();
        return joined;
    }

    private boolean copyfile(String src, String dest) {
        Logger logger = LogManager.getLogger(BLAST_DRIVER.class);

        final File donefile = new File(dest + ".done");
        int loops = 0;
        if (donefile.exists()) {
            logger.log(Level.INFO, "Preloaded already: " + dest);
            return true;
        }

        int retries = 0;
        int backoff = 500;
        final String lockname = dest + ".lock";
        File lockfile = new File(lockname);
        FileLock lock = null;
        FileChannel fileChannel = null;
        while (!donefile.exists()) {
            ++retries;
            try {
                if (!lockfile.exists())
                    if (!lockfile.createNewFile()) logger.log(Level.ERROR, lockname + " already exists?");

                java.nio.file.Path lockpath = Paths.get(lockname);
                fileChannel =
                    FileChannel.open(
                            lockpath, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
                lock = fileChannel.lock();
                if (donefile.exists()) continue;

                // Lock succeeded, this thread may download
                logger.log(
                        Level.INFO, String.format("Preloading (attempt #%d) %s -> %s", retries, src, dest));
                Configuration conf = new Configuration();
                org.apache.hadoop.fs.Path srcpath = new org.apache.hadoop.fs.Path(src);
                FileSystem fs = FileSystem.get(srcpath.toUri(), conf);
                Path dstpath = new org.apache.hadoop.fs.Path(dest);
                fs.copyToLocalFile(false, srcpath, dstpath, true);
                fs.close();
                if (new File(dest).length() != 0) {
                    if (donefile.createNewFile()) {
                        logger.log(Level.INFO, "Preloaded " + src + " -> " + dest);
                        continue;
                    }
                } else {
                    logger.log(Level.ERROR, "Empty file " + dest);
                }
            } catch (Exception e) {
                logger.log(
                        Level.ERROR,
                        String.format(
                            "Couldn't load (attempt #%d) %s from GS:// : %s", retries, src, e.toString()));
                try {
                    if (lock != null) lock.release();
                } catch (Exception e2) {
                }
            }
            try {
                Thread.sleep(backoff);
                backoff *= 2;
            } catch (Exception e) {
            }
        } // !donefile.exists
        try {
            if (lock != null) lock.release();
            if (fileChannel != null) fileChannel.close();
            lockfile.delete();
        } catch (Exception e) {
            logger.log(Level.ERROR, "Couldn't delete/unlock: " + e.toString());
        }
        return true;
    }

    private void preload(String db_tag, int partition_num) {
        Logger logger = LogManager.getLogger(BLAST_DRIVER.class);
        final String selector = db_tag.substring(0, 2);
        logger.log(Level.INFO, "selector is " + selector);
        // logger.log(Level.INFO, "dbsettings is " + dbsettings.toString());

        BLAST_DB_SETTING dbs = dbsettings.get(selector);
        final String bucket = "gs://" + dbs.bucket;
        final String pattern = dbs.pattern; // nt_50M

        for (String ext : dbs.extensions) {
            final String src = String.format("%s/%s.%02d.%s", bucket, pattern, partition_num, ext);
            final String dest = String.format("%s/%s.%02d.%s", db_location, pattern, partition_num, ext);

            File dbdir = new File(db_location);
            if (!dbdir.exists()) dbdir.mkdirs();
            copyfile(src, dest);
        } // extensions
    } // preload

    private Dataset<Row> prelim_results(Dataset<Row> joined) {
        StructType results_schema =
            StructType.fromDDL(
                    "protocol string, "
                    + "RID string, "
                    + "db_tag string, "
                    + "top_N_prelim int, "
                    + "top_N_traceback int, "
                    + "query_seq string, "
                    + "query_url string, "
                    + "program string, "
                    + "blast_params string, "
                    + "StartTime timestamp, "
                    + "db string, "
                    + "partition_num int, "
                    + "oid int, "
                    + "max_score int, "
                    + "hsp_blob string");

        ExpressionEncoder<Row> encoder = RowEncoder.apply(results_schema);

        String jni_log_level = settings.jni_log_level;
        Dataset<Row> prelim_search_results =
            joined.flatMap( // FIX: Make a functor
                    (FlatMapFunction<Row, Row>)
                    inrow -> {
                        Logger logger = LogManager.getLogger(BLAST_DRIVER.class);
                        logger.log(Level.INFO, "prelim_results <inrow> is : " + inrow.mkString(" : "));
                        if (inrow.anyNull()) logger.log(Level.ERROR, " inrow has NULLS");

                        String protocol = inrow.getString(inrow.fieldIndex("protocol"));
                        String rid = inrow.getString(inrow.fieldIndex("RID"));
                        logger.log(Level.DEBUG, "Flatmapped RID " + rid);
                        String db_tag = inrow.getString(inrow.fieldIndex("db_tag"));
                        int top_N_prelim = inrow.getInt(inrow.fieldIndex("top_N_prelim"));
                        int top_N_traceback = inrow.getInt(inrow.fieldIndex("top_N_traceback"));
                        String query_seq = inrow.getString(inrow.fieldIndex("query_seq"));
                        String query_url = inrow.getString(inrow.fieldIndex("query_url"));
                        String program = inrow.getString(inrow.fieldIndex("program"));
                        String blast_params = inrow.getString(inrow.fieldIndex("blast_params"));
                        Timestamp starttime = inrow.getTimestamp(inrow.fieldIndex("StartTime"));
                        String db = inrow.getString(inrow.fieldIndex("db"));

                        int partition_num = inrow.getInt(inrow.fieldIndex("partition_num"));

                        logger.log(Level.INFO, String.format("in flatmap %d", partition_num));

                        String selector = db_tag.substring(0, 2);
                        BLAST_DB_SETTING dbs = dbsettings.get(selector);
                        String pattern = dbs.pattern; // nt_50M

                        BLAST_REQUEST requestobj = new BLAST_REQUEST();
                        requestobj.id = rid;
                        requestobj.query_seq = query_seq;
                        requestobj.query_url = query_url;
                        requestobj.db = db_tag;
                        requestobj.program = program;
                        requestobj.params = blast_params;
                        // FIX VVV
                        requestobj.params = program;
                        requestobj.top_n = top_N_prelim;
                        BLAST_PARTITION partitionobj =
                            new BLAST_PARTITION(db_location, pattern, partition_num, true);
                        logger.log(Level.INFO, "PARTOBJ is " + partitionobj.toString());

                        preload(db_tag, partition_num);

                        BLAST_LIB blaster = new BLAST_LIB();

                        List<Row> hsp_rows;
                        if (blaster != null) {
                            BLAST_HSP_LIST[] search_res =
                                blaster.jni_prelim_search(partitionobj, requestobj, jni_log_level);
                            if (search_res.length > 0)
                                logger.log(
                                        Level.INFO,
                                        String.format(" prelim returned %d hsps to Spark", search_res.length));

                            hsp_rows = new ArrayList<>(search_res.length);
                            for (BLAST_HSP_LIST S : search_res) {
                                byte[] encoded = Base64.getEncoder().encode(S.hsp_blob);
                                String b64blob = new String(encoded, StandardCharsets.UTF_8);

                                logger.log(Level.WARN, "hsp outrow building");
                                try {
                                    Row outrow =
                                        RowFactory.create(
                                                protocol,
                                                rid,
                                                db_tag,
                                                top_N_prelim,
                                                top_N_traceback,
                                                query_seq,
                                                query_url,
                                                program,
                                                blast_params,
                                                starttime,
                                                db_tag,
                                                partition_num,
                                                S.oid,
                                                S.max_score,
                                                b64blob);

                                    logger.log(Level.WARN, "hsp outrow is " + outrow.mkString(" : "));
                                    hsp_rows.add(outrow);
                                } catch (Exception e) {
                                    logger.log(Level.ERROR, "factory exception");
                                }

                                // logger.log(Level.INFO, "json is " + json.toString());
                            }
                            logger.log(
                                    Level.INFO, String.format("hsp_rows has %d entries", hsp_rows.size()));
                        } else {
                            logger.log(Level.ERROR, "NULL blaster library");
                            hsp_rows = new ArrayList<>();
                            //                            Row outrow = RowFactory.create("null blaster",
                            // "null blaster");
                            //                            hsp_rows.add(outrow);
                        }
                        return hsp_rows.iterator();
                    },
                          encoder); // flastmap

        // prelim_search_results.explain(true);
        prelim_search_results.createOrReplaceTempView("prelim_search_results");
        System.out.print("prelim_search_results schema is ");
        prelim_search_results.printSchema();

        return prelim_search_results;
    }

    private DataStreamWriter<Row> topn_dsw(DataStreamWriter<Row> prelim_dsw) {
        int top_n = 100; // FIX
        String hsp_result_dir = settings.hdfs_result_dir + "/hsps";
        DataStreamWriter<Row> topn_dsw =
            prelim_dsw
            .foreach( // FIX: Make a separate function
                    new ForeachWriter<Row>() {
                        private long partitionId;
                        private int recordcount = 0;
                        private FileSystem fs;
                        private Logger logger;
                        private BLAST_TOPN topn;
                        // FIX map of RID->topn_N_prelims

                        @Override
                        public boolean open(long partitionId, long version) {
                            topn = new BLAST_TOPN();
                            this.partitionId = partitionId;
                            logger = LogManager.getLogger(BLAST_DRIVER.class);
                            try {
                                Configuration conf = new Configuration();
                                fs = FileSystem.get(conf);
                            } catch (IOException e) {
                                logger.log(Level.ERROR, e.toString());
                                System.err.println(e);
                                return false;
                            }

                            logger.log(
                                    Level.INFO, String.format("topn_dsw open %d %d", partitionId, version));
                            return true;
                        } // open

                        @Override
                        public void process(Row inrow) {
                            ++recordcount;
                            logger.log(Level.INFO, String.format(" topn_dsw in process %d", partitionId));
                            logger.log(Level.INFO, "topn_dsw " + inrow.mkString(" : "));
                            JSONObject json = new JSONObject();

                            json.put("protocol", inrow.getString(inrow.fieldIndex("protocol")));
                            final String rid = inrow.getString(inrow.fieldIndex("RID"));
                            json.put("rid", rid);
                            json.put("db_tag", inrow.getString(inrow.fieldIndex("db_tag")));
                            final int top_N_prelim = inrow.getInt(inrow.fieldIndex("top_N_prelim"));
                            json.put("top_N_prelim", top_N_prelim);
                            json.put("top_N_traceback", inrow.getInt(inrow.fieldIndex("top_N_prelim")));
                            json.put("query_seq", inrow.getString(inrow.fieldIndex("query_seq")));
                            json.put("query_url", inrow.getString(inrow.fieldIndex("query_url")));
                            json.put("program", inrow.getString(inrow.fieldIndex("program")));
                            json.put("blast_params", inrow.getString(inrow.fieldIndex("blast_params")));
                            json.put("starttime", inrow.getTimestamp(inrow.fieldIndex("StartTime")));
                            json.put("partition_num", inrow.getInt(inrow.fieldIndex("partition_num")));
                            json.put("oid", inrow.getInt(inrow.fieldIndex("oid")));
                            final int max_score = inrow.getInt(inrow.fieldIndex("max_score"));
                            json.put("max_score", max_score);
                            json.put("hsp_blob", inrow.getString(inrow.fieldIndex("hsp_blob")));

                            topn.add(rid, (double) max_score, json.toString());
                        }

                        @Override
                        public void close(Throwable errorOrNull) {
                            logger.log(Level.INFO, "topn_dsw close results:");
                            logger.log(Level.INFO, "---------------");
                            logger.log(Level.INFO, String.format(" topn_dsw saw %d records", recordcount));

                            // FIX: topn
                            ArrayList<String> results = topn.results(top_n);

                            logger.log(
                                    Level.INFO,
                                    String.format(
                                        "topn_dsw close partition %d had %d", partitionId, results.size()));
                            for (String r : results) {
                                logger.log(Level.INFO, "topn_dsw result: " + r);
                                JSONObject json = new JSONObject(r);
                                String rid = json.getString("rid");

                                ps_write_to_hdfs(rid, r);
                            }
                            return;
                        } // close

                        private void ps_write_to_hdfs(String rid, String output) {
                            try {
                                String tmpfile = String.format("/tmp/%s_hsp.json", rid);
                                FSDataOutputStream os = fs.create(new Path(tmpfile));
                                os.writeBytes(output);
                                os.close();

                                Path newFolderPath = new Path(hsp_result_dir);
                                if (!fs.exists(newFolderPath)) {
                                    logger.log(Level.INFO, "Creating HDFS dir " + hsp_result_dir);
                                    fs.mkdirs(newFolderPath);
                                }
                                String outfile = String.format("%s/%s.txt", hsp_result_dir, rid);
                                fs.delete(new Path(outfile), false);
                                // Rename in HDFS is supposed to be atomic
                                fs.rename(new Path(tmpfile), new Path(outfile));
                                logger.log(
                                        Level.INFO,
                                        String.format("Wrote %d bytes to HDFS %s", output.length(), outfile));

                            } catch (IOException ioe) {
                                logger.log(Level.ERROR, "Couldn't write to HDFS");
                                logger.log(Level.ERROR, ioe.toString());
                            }
                        } // ps_write_to_hdfs
                    } // ForeachWriter
        ) // foreach
            .outputMode("update");

        return topn_dsw;
    }

    private DataStreamWriter<Row> make_prelim_stream() {
        System.out.println("making prelim_stream");

        DataStreamReader query_stream = sparksession.readStream();
        // Note: each line in text file becomes separate row in DataFrame
        query_stream.option("maxFilesPerTrigger", settings.max_backlog);
        Dataset<Row> queries = query_stream.text(settings.hdfs_source_dir);

        System.out.print("queries schema is ");
        queries.printSchema();
        queries.createOrReplaceTempView("queries");

        Dataset<Row> parsed = prelim_parsed(queries);
        Dataset<Row> joined = prelim_joined(parsed);
        Dataset<Row> results = prelim_results(joined);

        // Don't use coalesce here, it'll group previous work onto one worker
        // Dataset<Row> prelim_search_1 = results.repartition(settings.num_executors,
        // results.col("RID"));
        Dataset<Row> prelim_search_1 =
            results.repartition(
                    // max_partitions,
                    results.col("RID")); // .sort(results.col("RID"), results.col("max_score").desc());

        DataStreamWriter<Row> prelim_dsw = prelim_search_1.writeStream();

        DataStreamWriter<Row> topn_dsw = topn_dsw(prelim_dsw);

        System.out.println("made  prelim_stream\n");

        return topn_dsw;
    }

    private DataStreamWriter<Row> make_traceback_stream() {
        System.out.println("making traceback_stream");
        Integer top_n = settings.top_n; // FIX: topn_n for traceback
        String jni_log_level = settings.jni_log_level;

        StructType hsp_schema =
            StructType.fromDDL(
                    "top_N_traceback int, "
                    + "top_N_prelim int, "
                    + "query_url string, "
                    + "program string, "
                    + "starttime timestamp, "
                    + "oid int, "
                    + "rid string, "
                    + "db_tag string, "
                    + "protocol string, "
                    + "max_score int, "
                    + "hsp_blob string, "
                    + "query_seq string, "
                    + "blast_params string, "
                    + "partition_num int ");

        DataStreamReader hsp_stream = sparksession.readStream();
        hsp_stream.format("json");
        hsp_stream.option("maxFilesPerTrigger", settings.max_backlog);
        // hsp_stream.option("multiLine", true);
        hsp_stream.option("mode", "FAILFAST");
        hsp_stream.option("includeTimestamp", true);
        hsp_stream.schema(hsp_schema);

        String hdfs_result_dir = settings.hdfs_result_dir;
        String hsp_result_dir = hdfs_result_dir + "/hsps";
        String gs_result_bucket = settings.gs_result_bucket;

        Dataset<Row> hsps = hsp_stream.json(hsp_result_dir);
        System.out.print("hsps schema is:");
        hsps.printSchema();
        hsps.createOrReplaceTempView("hsps");

        Dataset<Row> parted =
            sparksession.sql("select * " + "from hsps " + "distribute by partition_num");
        parted.createOrReplaceTempView("parted");

        System.out.print("parted schema is ");
        parted.printSchema();

        Dataset<Row> tb_struct =
            sparksession.sql(
                    "select "
                    + "RID, db_tag, cast(partition_num as int), query_seq, "
                    + "top_N_traceback, program, blast_params, "
                    + "struct(oid, max_score, hsp_blob) as hsp "
                    + "from parted");
        System.out.print("tb_struct schema is ");
        tb_struct.printSchema();
        tb_struct.createOrReplaceTempView("tb_struct");
        Dataset<Row> tb_hspl =
            sparksession.sql(
                    "select "
                    + "RID, db_tag, partition_num, query_seq, "
                    + "top_N_traceback, program, blast_params, "
                    + "collect_list(hsp) as hsp_collected "
                    + "from tb_struct "
                    + "group by RID, db_tag, partition_num, query_seq, top_N_traceback, program, blast_params "
                    + "distribute by partition_num");
        System.out.print("tb_hspl schema is ");
        tb_hspl.printSchema();

        //        Dataset<Row> traceback_results = tb_hspl;
        System.out.println("tb_hspl is: " + Arrays.toString(tb_hspl.columns()));
        // tb_hspl.explain(true);

        Dataset<Row> traceback_results =
            tb_hspl
            .flatMap( // FIX: Make a functor
                    (FlatMapFunction<Row, String>)
                    inrow -> {
                        Logger logger = LogManager.getLogger(BLAST_DRIVER.class);
                        logger.log(Level.INFO, "tb <row> is :  " + inrow.mkString(" : "));
                        if (inrow.anyNull()) logger.log(Level.ERROR, " tb inrow has NULLS");

                        String rid = inrow.getString(inrow.fieldIndex("RID"));
                        logger.log(Level.DEBUG, "Tracebacked RID " + rid);
                        String db_tag = inrow.getString(inrow.fieldIndex("db_tag"));
                        int partition_num = inrow.getInt(inrow.fieldIndex("partition_num"));
                        String query_seq = inrow.getString(inrow.fieldIndex("query_seq"));
                        String program = inrow.getString(inrow.fieldIndex("program"));
                        String blast_params = inrow.getString(inrow.fieldIndex("blast_params"));
                        logger.log(
                                Level.DEBUG,
                                String.format(
                                    "in tb flatmap rid=%s part=%d db_location=%s",
                                    rid, partition_num, db_location));

                        List<Row> hsplist = inrow.getList(inrow.fieldIndex("hsp_collected"));
                        // logger.log(Level.INFO, "alhspl is " + alhspl.toString());
                        ArrayList<BLAST_HSP_LIST> hspal = new ArrayList<>(hsplist.size());

                        for (Row hsp : hsplist) {
                            logger.log(Level.DEBUG, "alhspl # " + hsp.mkString(":"));
                            StructType sc = hsp.schema();
                            logger.log(Level.DEBUG, "alhspl schema:" + sc.toString());
                            int oid = hsp.getInt(hsp.fieldIndex("oid"));
                            int max_score = hsp.getInt(hsp.fieldIndex("max_score"));
                            String hsp_blob = hsp.getString(hsp.fieldIndex("hsp_blob"));
                            logger.log(
                                    Level.DEBUG,
                                    String.format(
                                        "alhspl oid=%d max_score=%d blob=%d bytes",
                                        oid, max_score, hsp_blob.length()));
                            byte[] blob = Base64.getDecoder().decode(hsp_blob);

                            BLAST_HSP_LIST hspl = new BLAST_HSP_LIST(oid, max_score, blob);
                            hspal.add(hspl);
                        }

                        // FIX
                        blast_params = blast_params.replace("\"evalue\":10", "\"evalue\":10.0");
                        blast_params = blast_params.replace("\"evalue\":1000", "\"evalue\":1000.0");
                        blast_params =
                            blast_params.replace("\"evalue\":200000", "\"evalue\":20000.0");
                        logger.log(Level.ERROR, "blast_params is " + blast_params);

                        BLAST_REQUEST requestobj = new BLAST_REQUEST();
                        requestobj.id = rid;
                        requestobj.query_seq = query_seq;
                        requestobj.query_url = "";
                        requestobj.db = db_tag;
                        requestobj.params = blast_params;
                        requestobj.program = program;
                        requestobj.top_n = top_n;
                        BLAST_PARTITION partitionobj =
                            new BLAST_PARTITION(db_location, "nt_50M", partition_num, false);
                        preload(db_tag, partition_num);

                        BLAST_LIB blaster = new BLAST_LIB();

                        List<String> asns;
                        if (blaster != null) {
                            asns = new ArrayList<>();

                            BLAST_HSP_LIST[] hsparray = hspal.toArray(new BLAST_HSP_LIST[hspal.size()]);
                            logger.log(
                                    Level.INFO,
                                    String.format(
                                        " spark calling traceback with %d HSP_LISTS", hsparray.length));
                            BLAST_TB_LIST[] tb_res =
                                blaster.jni_traceback(
                                        hsparray, partitionobj, requestobj, jni_log_level);
                            logger.log(
                                    Level.INFO,
                                    String.format(" traceback returned %d blobs to Spark", tb_res.length));

                            for (BLAST_TB_LIST tb : tb_res) {
                                JSONObject json = new JSONObject();
                                json.put("RID", rid);
                                json.put("oid", tb.oid);
                                json.put("evalue", tb.evalue);
                                byte[] encoded = Base64.getEncoder().encode(tb.asn1_blob);
                                String b64blob = new String(encoded, StandardCharsets.UTF_8);
                                json.put("asn1_blob", b64blob);

                                asns.add(json.toString());
                            }
                        } else {
                            logger.log(Level.ERROR, "NULL blaster library");
                            asns = new ArrayList<>();
                            asns.add("null blaster ");
                        }
                        return asns.iterator();
                    },
                          Encoders.STRING())
                              .toDF("asns")
                              .repartition(1); // FIX

        // traceback_results.explain();

        DataStreamWriter<Row> tb_dsw = traceback_results.writeStream();

        DataStreamWriter<Row> out_dsw =
            tb_dsw
            .foreach( // FIX: Make a separate function
                    new ForeachWriter<Row>() {
                        HashMap<String, TreeMap<Double, ArrayList<String>>> score_map;
                        private long partitionId;
                        private Logger logger;

                        @Override
                        public boolean open(long partitionId, long version) {
                            score_map = new HashMap<>();

                            this.partitionId = partitionId;
                            logger = LogManager.getLogger(BLAST_DRIVER.class);
                            logger.log(Level.DEBUG, String.format("tb open %d %d", partitionId, version));
                            if (partitionId != 0)
                                logger.log(
                                        Level.DEBUG, String.format(" *** not partition 0 %d ??? ", partitionId));
                            return true;
                        } // open

                        @Override
                        public void process(Row value) {
                            logger.log(Level.DEBUG, String.format(" in tb process %d", partitionId));
                            logger.log(Level.DEBUG, " tb process " + value.mkString(" : "));
                            String line = value.getString(0);
                            JSONObject json = new JSONObject(line);
                            String rid = json.getString("RID");
                            double evalue = json.getDouble("evalue");
                            // int oid=json.getInt("oid");
                            String asn1_blob = json.getString("asn1_blob");

                            if (!score_map.containsKey(rid)) {
                                TreeMap<Double, ArrayList<String>> tm =
                                    new TreeMap<Double, ArrayList<String>>(Collections.reverseOrder());
                                score_map.put(rid, tm);
                            }

                            TreeMap<Double, ArrayList<String>> tm = score_map.get(rid);

                            // FIX optimize: early cutoff if tm.size>topn
                            if (!tm.containsKey(evalue)) {
                                ArrayList<String> al = new ArrayList<String>();
                                tm.put(evalue, al);
                            }
                            ArrayList<String> al = tm.get(evalue);

                            al.add(asn1_blob);
                            tm.put(evalue, al);
                            score_map.put(rid, tm);
                        }

                        @Override
                        public void close(Throwable errorOrNull) {
                            logger.log(Level.DEBUG, "tb close results:");
                            logger.log(Level.DEBUG, "---------------");

                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                            byte[] seq_annot_prefix = {
                                (byte) 0x30,
                                (byte) 0x80,
                                (byte) 0xa4,
                                (byte) 0x80,
                                (byte) 0xa1,
                                (byte) 0x80,
                                (byte) 0x31,
                                (byte) 0x80
                            };
                            byte[] seq_annot_suffix = {0, 0, 0, 0, 0, 0, 0, 0};
                            for (String rid : score_map.keySet()) {
                                TreeMap<Double, ArrayList<String>> tm = score_map.get(rid);

                                bytes.write(seq_annot_prefix, 0, seq_annot_prefix.length);

                                int i = 0;
                                for (Double score : tm.keySet()) {
                                    if (i < top_n) {
                                        ArrayList<String> al = tm.get(score);
                                        for (String line : al) {
                                            byte[] blob = Base64.getDecoder().decode(line);
                                            bytes.write(blob, 0, blob.length);
                                        }
                                    } else {
                                        logger.log(Level.DEBUG, " Skipping rest");
                                        break;
                                    }
                                    ++i;
                                }
                                bytes.write(seq_annot_suffix, 0, seq_annot_suffix.length);

                                tb_write_to_hdfs(rid, bytes.toByteArray());
                            }

                            logger.log(Level.DEBUG, String.format("close %d", partitionId));

                            return;
                        } // close

                        private void tb_write_to_hdfs(String rid, byte[] output) {
                            try {
                                String outfile = "gs://" + gs_result_bucket + "/output/" + rid + ".asn1";
                                Configuration conf = new Configuration();
                                Path path = new Path(outfile);
                                FileSystem fs = FileSystem.get(path.toUri(), conf);

                                FSDataOutputStream os = fs.create(path);
                                os.write(output, 0, output.length);
                                os.close();
                                logger.log(
                                        Level.INFO,
                                        String.format("Wrote %d bytes to %s", output.length, outfile));

                            } catch (IOException ioe) {
                                logger.log(Level.ERROR, "Couldn't write to HDFS");
                                logger.log(Level.ERROR, ioe.toString());
                            }
                        } // tb_write_to_hdfs
                    } // ForeachWriter
        ) // foreach
            .outputMode("update"); // Must be complete or update for aggregations

        System.out.println("made  traceback_stream\n");
        return out_dsw;
    }

    private boolean run_streams(
            DataStreamWriter<Row> prelim_dsw, DataStreamWriter<Row> traceback_dsw) {
        System.out.println("starting streams...");
        //  StreamingQuery prelim_results = prelim_dsw.outputMode("append").format("console").start();
        try {
            StreamingQuery presults = prelim_dsw.start();
            // traceback_dsw.format("console").option("truncate", false).start();
            StreamingQuery tresults = traceback_dsw.start();

            for (int i = 0; i < 30; ++i) {
                System.out.println("\nstreams running...\n");
                Thread.sleep(30000);
                System.out.println(presults.lastProgress());
                System.out.println(presults.status());
                System.out.println(tresults.lastProgress());
                System.out.println(tresults.status());
            }
        } catch (Exception e) {
            System.err.println("Spark exception: " + e);
            return false;
        }
        System.out.println("That is enough for now");

        return true;
            }

    private void shutdown() {
        javasparkcontext.stop();
        sparksession.stop();
    }
}
