/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.tools;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.oozie.cli.CLIParser;
import org.apache.oozie.service.HadoopAccessorService;
import org.apache.oozie.service.Services;
import org.apache.oozie.service.WorkflowAppService;

public class OozieSharelibCLI {
    public static final String[] HELP_INFO = {
            "",
            "OozieSharelibCLI creates or upgrade sharelib for oozie",
    };
    public static final String HELP_CMD = "help";
    public static final String CREATE_CMD = "create";
    public static final String UPGRADE_CMD = "upgrade";
    public static final String LIB_OPT = "locallib";
    public static final String FS_OPT = "fs";
    public static final String CONCURRENCY_OPT = "concurrency";
    public static final String OOZIE_HOME = "oozie.home.dir";
    public static final String SHARE_LIB_PREFIX = "lib_";

    private boolean used;

    public static void main(String[] args) throws Exception{
        System.exit(new OozieSharelibCLI().run(args));
    }

    public OozieSharelibCLI() {
        used = false;
    }

    protected Options createUpgradeOptions(String subCommand){
        Option sharelib = new Option(LIB_OPT, true, "Local share library directory");
        Option uri = new Option(FS_OPT, true, "URI of the fileSystem to " + subCommand + " oozie share library");
        Option concurrency = new Option(CONCURRENCY_OPT, true, "Number of threads to be used for copy operations. (default=1)");
        Options options = new Options();
        options.addOption(sharelib);
        options.addOption(uri);
        options.addOption(concurrency);
        return options;
    }

    public synchronized int run(String[] args) throws Exception{
        if (used) {
            throw new IllegalStateException("CLI instance already used");
        }

        used = true;

        CLIParser parser = new CLIParser("oozie-setup.sh", HELP_INFO);
        String oozieHome = System.getProperty(OOZIE_HOME);
        parser.addCommand(HELP_CMD, "", "display usage for all commands or specified command", new Options(), false);
        parser.addCommand(CREATE_CMD, "", "create a new timestamped version of oozie sharelib",
                createUpgradeOptions(CREATE_CMD), false);
        parser.addCommand(UPGRADE_CMD, "",
                "[deprecated][use command \"create\" to create new version]   upgrade oozie sharelib \n",
                createUpgradeOptions(UPGRADE_CMD), false);

        try {
            final CLIParser.Command command = parser.parse(args);
            String sharelibAction = command.getName();

            if (sharelibAction.equals(HELP_CMD)){
                parser.showHelp(command.getCommandLine());
                return 0;
            }

            if (!command.getCommandLine().hasOption(FS_OPT)){
                throw new Exception("-fs option must be specified");
            }

            int threadPoolSize = Integer.valueOf(command.getCommandLine().getOptionValue(CONCURRENCY_OPT, "1"));
            File srcFile = null;

            //Check whether user provided locallib
            if (command.getCommandLine().hasOption(LIB_OPT)){
                srcFile = new File(command.getCommandLine().getOptionValue(LIB_OPT));
            }
            else {
                //Since user did not provide locallib, find the default one under oozie home dir
                Collection<File> files =
                        FileUtils.listFiles(new File(oozieHome), new WildcardFileFilter("oozie-sharelib*.tar.gz"), null);

                if (files.size() > 1){
                    throw new IOException("more than one sharelib tar found at " + oozieHome);
                }

                if (files.isEmpty()){
                    throw new IOException("default sharelib tar not found in oozie home dir: " + oozieHome);
                }

                srcFile = files.iterator().next();
            }

            File temp = File.createTempFile("oozie", ".dir");
            temp.delete();
            temp.mkdir();
            temp.deleteOnExit();

            //Check whether the lib is a tar file or folder
            if (!srcFile.isDirectory()){
                FileUtil.unTar(srcFile, temp);
                srcFile = new File(temp.toString() + "/share/lib");
            }
            else {
                //Get the lib directory since it's a folder
                srcFile = new File(srcFile, "lib");
            }

            String hdfsUri = command.getCommandLine().getOptionValue(FS_OPT);
            Path srcPath = new Path(srcFile.toString());

            Services services = new Services();
            services.getConf().set(Services.CONF_SERVICE_CLASSES,
                "org.apache.oozie.service.LiteWorkflowAppService, org.apache.oozie.service.HadoopAccessorService");
            services.getConf().set(Services.CONF_SERVICE_EXT_CLASSES, "");
            services.init();
            WorkflowAppService lwas = services.get(WorkflowAppService.class);
            HadoopAccessorService has = services.get(HadoopAccessorService.class);
            Path dstPath = lwas.getSystemLibPath();

            if (sharelibAction.equals(CREATE_CMD) || sharelibAction.equals(UPGRADE_CMD)){
                dstPath= new Path(dstPath.toString() +  Path.SEPARATOR +  SHARE_LIB_PREFIX + getTimestampDirectory()  );
            }

            System.out.println("the destination path for sharelib is: " + dstPath);

            URI uri = new Path(hdfsUri).toUri();
            Configuration fsConf = has.createJobConf(uri.getAuthority());
            FileSystem fs = FileSystem.get(uri, fsConf);


            if (!srcFile.exists()){
                throw new IOException(srcPath + " cannot be found");
            }

            if (threadPoolSize > 1) {
                concurrentCopyFromLocal(fs, threadPoolSize, srcFile, dstPath);
            } else {
                fs.copyFromLocalFile(false, srcPath, dstPath);
            }

            services.destroy();
            FileUtils.deleteDirectory(temp);

            return 0;
        }
        catch (ParseException ex) {
            System.err.println("Invalid sub-command: " + ex.getMessage());
            System.err.println();
            System.err.println(parser.shortHelp());
            return 1;
        }
        catch (Exception ex) {
            logError(ex.getMessage(), ex);
            return 1;
        }
    }

    private void logError(String errorMessage, Throwable ex) {
        System.err.println();
        System.err.println("Error: " + errorMessage);
        System.err.println();
        System.err.println("Stack trace for the error was (for debug purposes):");
        System.err.println("--------------------------------------");
        ex.printStackTrace(System.err);
        System.err.println("--------------------------------------");
        System.err.println();
    }

    public String getTimestampDirectory() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        Date date = new Date();
        return dateFormat.format(date).toString();
    }

    private void concurrentCopyFromLocal(final FileSystem fs, int threadPoolSize,
            File srcFile, final Path dstPath) throws IOException {
        List<Future<Void>> futures = Collections.emptyList();
        ExecutorService threadPool = Executors.newFixedThreadPool(threadPoolSize);
        try {
            futures = copyFolderRecursively(fs, threadPool, srcFile, dstPath);
            System.out.println("Running " + futures.size() + " copy tasks on " + threadPoolSize + " threads");
        } finally {
            try {
                threadPool.shutdown();
            } finally {
                checkCopyResults(futures);
            }
        }
    }

    private void checkCopyResults(List<Future<Void>> futures) throws IOException {
        Throwable t = null;
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (CancellationException ce) {
                t = ce;
                logError("Copy task was cancelled", ce);
            } catch (ExecutionException ee) {
                t = ee.getCause();
                logError("Copy task failed with exception", t);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (t != null) {
            throw new IOException ("At least one copy task failed with exception", t);
        }
    }

    private List<Future<Void>> copyFolderRecursively(final FileSystem fs, final ExecutorService threadPool,
            File srcFile, final Path dstPath) throws IOException {
        List<Future<Void>> taskList = new ArrayList<Future<Void>>();
        for (final File file : srcFile.listFiles()) {
            final Path trgName = new Path(dstPath, file.getName());
            if (file.isDirectory()) {
                taskList.addAll(copyFolderRecursively(fs, threadPool, file, trgName));
            } else {
                taskList.add(threadPool.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        fs.copyFromLocalFile(new Path(file.toURI()), trgName);
                        return null;
                    }
                }));
            }
        }
        return taskList;
    }
}
