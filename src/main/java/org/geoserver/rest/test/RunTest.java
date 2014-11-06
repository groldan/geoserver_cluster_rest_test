package org.geoserver.rest.test;

import static com.google.common.base.Preconditions.checkState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.restlet.Response;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.engine.util.Base64;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.ResourceException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.io.ByteStreams;

public class RunTest {

    private static final List<String> DEFAULT_BASE_URLS = ImmutableList.of(//
            "http://eva01:8081/geoserver/",//
            "http://eva01:8082/geoserver/",//
            "http://eva01:8083/geoserver/"//
    );

    private final int numRuns;

    private final int numConcClients;

    private final ImmutableList<String> clusterMembers;

    private final String gsUser;

    private final String gsPassword;

    private final boolean cleanup;

    private final String storeHost, storePort, storeSchema, storeDatabase, storeUser,
            storePassword;

    private Map<String, Exception> errors = new ConcurrentHashMap<String, Exception>();

    public RunTest() {
        numRuns = 10;
        numConcClients = 2;
        clusterMembers = ImmutableList.copyOf(DEFAULT_BASE_URLS);
        gsUser = "admin";
        gsPassword = "geoserver";
        storeHost = "hal";
        storePort = "5432";
        storeDatabase = "postgis";
        storeUser = "postgres";
        storePassword = "geo123";
        storeSchema = "public";
        cleanup = true;
    }

    public RunTest(Properties config) {
        numRuns = Integer.parseInt(config.getProperty("runs"));
        numConcClients = Integer.parseInt(config.getProperty("threads"));
        clusterMembers = ImmutableList.copyOf(Splitter.on(',').split(
                config.getProperty("clusterMembers")));
        gsUser = config.getProperty("user");
        gsPassword = config.getProperty("password");

        storeHost = config.getProperty("store.host");
        storePort = config.getProperty("store.port");
        storeDatabase = config.getProperty("store.database");
        storeUser = config.getProperty("store.user");
        storePassword = config.getProperty("store.password");
        storeSchema = config.getProperty("store.schema");
        cleanup = Boolean.valueOf(config.getProperty("cleanup"));
    }

    public static void main(String args[]) {
        try {
            new RunTest().run();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.exit(0);
        }
    }

    public void run() throws Exception {
        checkClusterMembers();

        final ExecutorService executor = Executors.newFixedThreadPool(numConcClients);

        final AtomicInteger count = new AtomicInteger();

        final LinkedHashMap<String, String> orignalAtts = new LinkedHashMap<>();
        orignalAtts.put("state_name", "java.lang.String");
        orignalAtts.put("geom", "com.vividsolutions.jts.geom.MultiPolygon");

        final LinkedHashMap<String, String> alteredAtts = new LinkedHashMap<>();
        alteredAtts.putAll(orignalAtts);
        alteredAtts.put("newcol", "java.lang.String");

        final LinkedHashMap<String, String> modifiedAtts = new LinkedHashMap<>(orignalAtts);
        modifiedAtts.remove("state_name");

        final LinkedHashMap<String, String> shuffledAtts = new LinkedHashMap<>();
        shuffledAtts.put("newcol", "java.lang.String");
        shuffledAtts.put("geom", "com.vividsolutions.jts.geom.MultiPolygon");
        shuffledAtts.put("state_name", "java.lang.String");

        for (int i = 0; i < numRuns; i++) {
            Runnable task = new Runnable() {

                @Override
                public void run() {
                    final int index = count.incrementAndGet();
                    final String table = "clustertest_" + index;
                    info("Creating table, workspace, store, and layer %s\n", table);
                    try {
                        createTable(table);
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                        return;
                    }
                    try {
                        final String wsName = createWorkspace(index);
                        final String dsName = createDataStore(wsName, index);
                        final String ftName = createFeatureTypeAndLayer(wsName, dsName, index,
                                table);

                        LinkedHashMap<String, String> attributes;
                        try {
                            debug("Checking initial attribute order...");
                            attributes = getAttributes(wsName, dsName, ftName);
                            checkState(orignalAtts.equals(attributes), "expected %s, got %s",
                                    orignalAtts.keySet(), attributes.keySet());
                            trace(attributes.keySet() + " OK");
                            debug("Verifying attributes on every node through REST and WFS...");
                            verifyFeatureType(wsName, dsName, ftName, orignalAtts);
                            verifyDescribeFeatureType(wsName, dsName, ftName, orignalAtts);
                            verifyGetFeatures(wsName, dsName, ftName);

                            debug("Removing one attribute through REST, expected result: "
                                    + modifiedAtts.keySet());
                            modifyFeatureType(wsName, dsName, ftName, table, modifiedAtts);

                            debug("Verifying attribute change on every node through REST and WFS...");
                            verifyFeatureType(wsName, dsName, ftName, modifiedAtts);
                            verifyDescribeFeatureType(wsName, dsName, ftName, modifiedAtts);
                            verifyGetFeatures(wsName, dsName, ftName);

                            debug("Adding column 'newcol' to table %s through JDBC...\n", table);
                            alterTableAddColumn(table);
                            debug("Deleting FT %s recursively\n", table);
                            delete("rest/workspaces/" + wsName + "/datastores/" + dsName
                                    + "/featuretypes/" + ftName + ".xml?recurse=true");

                            debug("Re-creating FT %s from altered db table...\n", table);
                            createFeatureTypeAndLayer(wsName, dsName, index, table);

                            debug("Loading new attribute list...");
                            attributes = getAttributes(wsName, dsName, ftName);
                            debug("Verifying new attribute list on all nodes through REST and WFS...");
                            verifyFeatureType(wsName, dsName, ftName, alteredAtts);
                            verifyDescribeFeatureType(wsName, dsName, ftName, alteredAtts);
                            verifyGetFeatures(wsName, dsName, ftName);

                            debug("Modifying FT %s attribute order. Original: %s, new: %s\n",
                                    table, alteredAtts.keySet(), shuffledAtts.keySet());
                            modifyFeatureType(wsName, dsName, ftName, table, shuffledAtts);

                            debug("Verifying new attribute order on all nodes through REST and WFS...");
                            verifyFeatureType(wsName, dsName, ftName, shuffledAtts);
                            verifyDescribeFeatureType(wsName, dsName, ftName, shuffledAtts);
                            verifyGetFeatures(wsName, dsName, ftName);

                        } catch (IllegalStateException e) {
                            trace("ERROR " + e.getMessage());
                        } finally {
                            if (cleanup) {
                                delete("rest/layers/" + ftName + ".xml");
                                delete("rest/workspaces/" + wsName + "/datastores/" + dsName
                                        + "/featuretypes/" + ftName + ".xml");
                                delete("rest/workspaces/" + wsName + "/datastores/" + dsName
                                        + ".xml");
                                delete("rest/workspaces/" + wsName + ".xml");
                            }
                        }
                    } catch (RuntimeException e) {
                        trace("ERROR " + e.getMessage());
                    } finally {
                        if (cleanup) {
                            dropTable(table);
                        }
                    }
                }
            };
            executor.submit(task);
        }

        executor.shutdown();
        while (!executor.isTerminated()) {
            executor.awaitTermination(1, TimeUnit.MINUTES);
        }
    }

    private void checkClusterMembers() {
        String relativePath = "rest/workspaces.xml";
        for (int i = 0; i < clusterMembers.size(); i++) {
            final ClientResource client = newClient(relativePath);
            client.getRequest().setResourceRef(relativePath);
            String msg = "Checking access to cluster member "
                    + client.getRequest().getResourceRef().getTargetRef();
            trace(msg);
            Representation representation = client.get();
        }
    }

    private void trace(String msg) {
        print("    " + msg);
    }

    private void trace(String format, Object... args) {
        print("    " + format, args);
    }

    private void debug(String msg) {
        print("  " + msg);
    }

    private void debug(String format, Object... args) {
        print("  " + format, args);
    }

    private void info(String msg) {
        System.out.println(msg);
    }

    private void info(String format, Object... args) {
        System.out.printf(format, args);
    }

    private void print(String msg) {
        System.out.println(msg);
    }

    private void print(String format, Object... args) {
        System.out.printf(format, args);
    }

    private void delete(final String relativePath) {
        final ClientResource client = newClient(relativePath);
        client.getRequest().setResourceRef(relativePath);

        Reference targetRef = client.getRequest().getResourceRef().getTargetRef();
        try {
            Stopwatch sw = Stopwatch.createStarted();
            client.delete();
            Status status = client.getResponse().getStatus();
            trace("DELETE %s: %s (%s)\n", targetRef, status, sw.stop());
        } catch (Exception e) {
            trace("ERROR DELETE %s: %s\n", targetRef, e.getMessage());
        }
    }

    private String createFeatureTypeAndLayer(final String wsName, final String dsName,
            final int index, final String storeTable) {

        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes";
        final String ftXml = "<featureType>\n"//
                + "  <name>" + storeTable + "</name>\n" //
                + "  <nativeName>" + storeTable + "</nativeName>\n" + //
                "  <title>" + storeTable + " cluster stress test #" + index + "</title>\n" + //
                "  <srs>EPSG:4326</srs>\n" + //
                "  <nativeBoundingBox>"//
                + "<minx>-180</minx><maxx>180</maxx><miny>-90</miny><maxy>90</maxy>"//
                + "<crs>EPSG:4326</crs>"//
                + "</nativeBoundingBox>\n" + //
                "</featureType>\n";
        postXml(relativePath, ftXml);
        return storeTable;
    }

    private void verifyDescribeFeatureType(final String wsName, final String dsName,
            final String ftName, final LinkedHashMap<String, String> expected) {

        String relativePath = wsName + "/wfs?service=WFS&version=1.0.0"
                + "&request=DescribeFeatureType&typeName=" + wsName + ":" + ftName;

        for (int i = 0; i < clusterMembers.size(); i++) {
            final ClientResource client = newClient(relativePath);
            client.getRequest().setResourceRef(relativePath);
            client.setRetryOnError(false);
            Reference targetRef = client.getRequest().getResourceRef().getTargetRef();
            Representation representation;
            Stopwatch sw = Stopwatch.createStarted();
            try {
                representation = client.get();
            } catch (Exception e) {
                trace("ERROR GET " + targetRef + ": " + e.getMessage());
                continue;
            }
            Status status = client.getResponse().getStatus();

            trace("GET %s: %s (%s)\n", targetRef, status, sw.stop());
            StringWriter writer = new StringWriter();
            try {
                representation.write(writer);
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }
            String stringRep = writer.toString();
            Pattern p = Pattern.compile("maxOccurs");
            Matcher m = p.matcher(stringRep);
            int count = 0;
            while (m.find()) {
                count += 1;
            }
            int expectedAttributeCount = expected.size();
            if (expectedAttributeCount != count) {
                trace(String.format("ERROR: Expected %d attributes, got %d:\n%s\n",
                        expectedAttributeCount, count, stringRep));
            }
        }
    }

    private void verifyGetFeatures(final String wsName, final String dsName, final String ftName) {

        for (String version : Arrays.asList("1.0.0")) {

            String relativePath = wsName + "/wfs?service=WFS&version=" + version
                    + "&request=GetFeature&maxFeatures=1&typeName=" + wsName + ":" + ftName;

            for (int i = 0; i < clusterMembers.size(); i++) {
                final ClientResource client = newClient(relativePath);
                client.getRequest().setResourceRef(relativePath);
                client.setRetryOnError(false);
                Reference targetRef = client.getRequest().getResourceRef().getTargetRef();
                // Can't use ClientResource cause it can't parse the weird Content-Type headers the
                // WFS returns
                URL url;
                URLConnection connection;
                Stopwatch sw = Stopwatch.createStarted();
                try {
                    url = new URL(targetRef.toString());
                    connection = url.openConnection();
                } catch (Exception e) {
                    trace("ERROR Can't connect to " + targetRef + ": " + e.getMessage());
                    continue;
                }

                if (gsUser != null && gsPassword != null) {
                    String usrpwd = gsUser + ":" + gsPassword;
                    String encodedAuthorization = Base64.encode(usrpwd.toCharArray(), false);
                    connection.setRequestProperty("Authorization", "Basic " + encodedAuthorization);
                }
                ByteArrayOutputStream to = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    ByteStreams.copy(in, to);
                } catch (IOException ex) {
                    trace("ERROR GET " + targetRef + ": " + ex.getMessage());
                    continue;
                }

                String stringRep = to.toString();
                if (stringRep.contains("FeatureCollection")) {
                    trace("GET %s: OK (%s)\n", targetRef, sw.stop());
                } else {
                    trace("ERROR GET " + targetRef + ": " + stringRep);
                }
            }
        }
    }

    private LinkedHashMap<String, String> getAttributes(final String wsName, final String dsName,
            final String ftName) {

        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes/" + ftName + ".xml";

        final ClientResource client = newClient(relativePath);
        return getAttributes(relativePath, client);
    }

    private LinkedHashMap<String, String> getAttributes(final String relativePath,
            final ClientResource client) {
        client.getRequest().setResourceRef(relativePath);
        Reference targetRef = client.getRequest().getResourceRef().getTargetRef();

        LinkedHashMap<String, String> attNamesAndBindings = new LinkedHashMap<>();
        try {
            Stopwatch sw = Stopwatch.createStarted();
            Representation representation = client.get();
            Status status = client.getResponse().getStatus();

            trace("GET %s: %s (%s)\n", targetRef, status, sw.stop());
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document dom = builder.parse(representation.getStream());
            NodeList attributesList = dom.getElementsByTagName("attributes");
            if (attributesList.getLength() != 1) {
                throw new RuntimeException("Unable to parse feature type " + relativePath);
            }
            Node attributes = attributesList.item(0);
            NodeList atts = ((Element) attributes).getElementsByTagName("attribute");
            for (int j = 0; j < atts.getLength(); j++) {
                Node nameAtt = ((Element) atts.item(j)).getElementsByTagName("name").item(0);
                Node bindingAtt = ((Element) atts.item(j)).getElementsByTagName("binding").item(0);
                String attName = nameAtt.getTextContent();
                String binding = bindingAtt.getTextContent();
                attNamesAndBindings.put(attName, binding);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return attNamesAndBindings;
    }

    private void verifyFeatureType(final String wsName, final String dsName, final String ftName,
            final LinkedHashMap<String, String> expected) {

        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes/" + ftName + ".xml";

        for (int i = 0; i < clusterMembers.size(); i++) {
            final ClientResource client = newClient(relativePath);
            LinkedHashMap<String, String> attributes = getAttributes(relativePath, client);
            if (!expected.equals(attributes)) {
                trace("ERROR: attributes don't match. Expected %s, got %s\n", expected.keySet(),
                        attributes.keySet());
            } else {
                trace(" OK: " + attributes.keySet());
            }
        }
    }

    private void modifyFeatureType(final String wsName, final String dsName, final String ftName,
            final String table, final Map<String, String> attributes) {

        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes/" + ftName + ".xml";

        String ftXml = "<featureType>\n" + "  <name>" + ftName + "</name>\n"//
                + "  <nativeName>" + table + "</nativeName>\n"//
                + "  <title>" + ftName + " + modified</title>\n"//
                + "  <srs>EPSG:4326</srs>\n"//
                + "  <enabled>true</enabled>\n"//
                + "  <attributes>\n";
        for (Entry<String, String> entry : attributes.entrySet()) {
            String name = entry.getKey();
            String binding = entry.getValue();
            ftXml += "     <attribute>\n"//
                    + "      <name>" + name + "</name><binding>" + binding
                    + "</binding>\n"
                    + "     </attribute>\n";
        }
        ftXml += "  </attributes>\n"//
                + "</featureType>\n";
        putXml(relativePath, ftXml);
    }

    private String createDataStore(final String wsName, final int index) {
        final String dsName = "ds-" + index;
        String dsxml = "<dataStore>\n" + //
                "<name>" + dsName + "</name>\n" + //
                " <connectionParameters>\n" + //
                "  <host>" + storeHost + "</host>\n" + //
                "  <port>" + storePort + "</port>\n" + //
                "  <schema>" + storeSchema + "</schema>\n" + //
                "  <database>" + storeDatabase + "</database>\n" + //
                "  <user>" + storeUser + "</user>\n" + //
                "  <passwd>" + storePassword + "</passwd>\n" + //
                "  <dbtype>postgis</dbtype>\n" + //
                " </connectionParameters>\n" + //
                "</dataStore>";
        final String relativePath = "rest/workspaces/" + wsName + "/datastores";

        postXml(relativePath, dsxml);
        return dsName;
    }

    private String createWorkspace(final int index) {
        final String wsName = "rest-stress-ws-" + index;
        final String wsxml = "<workspace><name>" + wsName + "</name></workspace>";
        postXml("rest/workspaces", wsxml);
        return wsName;
    }

    private Representation putXml(final String relativePath, final String xml) {
        return sendXml(relativePath, xml, Method.PUT);
    }

    private Representation postXml(final String relativePath, final String xml) {
        return sendXml(relativePath, xml, Method.POST);
    }

    private Representation sendXml(final String relativePath, final String xml, Method method) {
        ClientResource client = newClient(relativePath);
        final String targetRef = client.getRequest().getResourceRef().getTargetRef().toString();
        StringRepresentation reqRep = new StringRepresentation(xml);
        reqRep.setMediaType(MediaType.APPLICATION_XML);
        Representation result;
        Stopwatch sw = Stopwatch.createStarted();
        try {
            if (Method.PUT.equals(method)) {
                result = client.put(reqRep);
            } else if (Method.POST.equals(method)) {
                result = client.post(reqRep);
            } else {
                throw new IllegalArgumentException("Method: " + method);
            }
        } catch (ResourceException re) {
            Representation responseEntity = client.getResponseEntity();
            if (responseEntity != null) {
                trace(method.getName() + " ERROR to " + targetRef + ": server response: "
                        + re.getMessage());
                try {
                    responseEntity.write(System.out);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                re.printStackTrace();
            }
            return null;
        }
        Response response = client.getResponse();
        trace("%s to %s: %s (%s)\n", method.getName(), targetRef, response.getStatus(), sw.stop());
        return result;
    }

    private final ThreadLocal<Iterator<ClientResource>> CLIENTS_BY_THREAD = new ThreadLocal<Iterator<ClientResource>>() {

        @Override
        protected Iterator<ClientResource> initialValue() {
            List<ClientResource> clients = new ArrayList<ClientResource>();
            for (String baseUrl : clusterMembers) {
                if (!baseUrl.endsWith("/")) {
                    baseUrl += "/";
                }
                ClientResource client = new ClientResource(baseUrl);
                client.getRequest().getResourceRef().setBaseRef(baseUrl);
                client.setChallengeResponse(ChallengeScheme.HTTP_BASIC, gsUser, gsPassword);
                client.setRetryOnError(false);
                clients.add(client);
            }
            return Iterators.cycle(clients);
        }
    };

    private synchronized ClientResource newClient(final String relativePath) {
        ClientResource client = CLIENTS_BY_THREAD.get().next();
        if (null != relativePath) {
            client.getRequest().setResourceRef(relativePath);
        }
        return client;

        // ClientResource client = new ClientResource("");
        // client.getRequest().getResourceRef().setBaseRef(roundRobbinUrls.next());
        // if (null != relativePath) {
        // client.getRequest().setResourceRef(relativePath);
        // }
        // client.setChallengeResponse(HTTP_BASIC, "admin", "geoserver");
        // return client;
    }

    private Connection createConnection() {
        final String driver = "org.postgresql.Driver";
        Connection connection;
        try {
            Class.forName(driver);
            String url = "jdbc:postgresql://" + storeHost + ":" + storePort + "/" + storeDatabase;
            connection = DriverManager.getConnection(url, storeUser, storePassword);
        } catch (ClassNotFoundException | SQLException e) {
            throw Throwables.propagate(e);
        }
        return connection;
    }

    private void createTable(String table) {
        final String sql1 = "drop table if exists \"" + table + "\"; CREATE TABLE \"" + table
                + "\" (gid serial, \"state_name\" varchar(25))";
        final String sql2 = "ALTER TABLE \"" + table + "\" ADD PRIMARY KEY (gid)";
        final String sql3 = "SELECT AddGeometryColumn('','" + table
                + "','geom','4326','MULTIPOLYGON',2)";

        final String sql4 = String
                .format("insert into \"%s\"(state_name, geom) values('oregon', ST_GeomFromText('MULTIPOLYGON(((0 0, 0 1, 1 1, 1 0, 0 0)))', 4326) )",
                        table);

        try (Connection c = createConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                String sql = String.format("%s;%s;%s;%s;", sql1, sql2, sql3, sql4);
                execute(sql, st);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
            //avoid "ERROR: stats for "<table>.geom" do not exist" logs from postgres
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                execute("vacuum analyze", st);
            }
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    private boolean execute(final String sql, Statement st) throws SQLException {
        String searchPath = "set search_path to '" + storeSchema + "';";
        String query = searchPath + sql;
        trace("Running SQL: " + query);
        return st.execute(query);
    }

    private void alterTableAddColumn(String table) {
        String sql = "ALTER TABLE \"" + table + "\" add column newcol text";
        try (Connection c = createConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                execute(sql, st);
            }
            c.commit();
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    private void dropTable(String table) {
        final String sql = "DROP TABLE \"" + table + "\"";

        try (Connection c = createConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                execute(sql, st);
            }
            c.commit();
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }
}
