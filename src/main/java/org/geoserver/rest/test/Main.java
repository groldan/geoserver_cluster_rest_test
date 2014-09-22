package org.geoserver.rest.test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.Reader;
import java.net.URL;
import java.util.Properties;

import com.google.common.base.Preconditions;
import com.google.common.io.Resources;

public class Main {

    public static void main(String args[]) {
        Properties config = checkFile(args);
        try {
            new RunTest(config).run();
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private static Properties checkFile(String[] args) {
        String configFile;
        if (args.length > 0) {
            if ("--help".equals(args[0])) {
                printUsageAndExit();
            }
            configFile = args[0];
        } else {
            configFile = "test.properties";
        }
        File file = new File(configFile);
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            if (args.length == 0) {
                try {
                    Preconditions.checkState(file.createNewFile(),
                            "File.createNewFile can't create test file");
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        URL defaultResource = Main.class.getResource("default_test.properties");
                        Resources.copy(defaultResource, out);
                    } catch (Exception e) {
                        file.delete();
                        throw e;
                    }
                    log("Created default test.properties file "
                            + file.getAbsolutePath()
                            + "\nEdit it to configure the test scenario and run this program again.");
                    System.exit(-1);
                } catch (Exception e) {
                    log("Unable to create default test config file file " + file.getAbsolutePath());
                    e.printStackTrace();
                    System.exit(-1);
                }
            } else {
                log("test config file '%s' not found\n");
                printUsageAndExit();
            }
        }

        Properties props = new Properties();
        try (Reader reader = new FileReader(file)) {
            props.load(reader);
        } catch (Exception e) {
            log("Unable to read config file");
            e.printStackTrace();
            System.exit(-1);
        }
        return props;
    }

    private static void printUsageAndExit() {
        String msg = "Usage: java -jar cluster_stress.jar --help | [config file]\n" //
                + "--help: show this usage info and exit.\n"//
                + "config file: the properties file to use when setting up the test scenario.\n"//
                + "The first time the program is run with no arguments, a test.properties file\n"//
                + "is created with default test scenario settings, which must be edited\n"//
                + "to configure the cluster members entry points and other settings.\n"//
                + "Subsequent program runs will use that file to create and run the tests\n"//
                + "against the configured cluster members. The file contains instructions on\n"//
                + "the meaning of each configuration option.";
        log(msg);
        System.exit(-1);
    }

    private static void log(String msg) {
        System.err.printf(msg);
    }

}
