package org.geoserver.rest.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
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

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
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

    private final String storeHost, storePort, storeSchema, storeDatabase, storeUser,
            storePassword, storeTable;

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
        storeTable = "states";
        storeSchema = "public";
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
        storeTable = config.getProperty("store.table");
        storeSchema = config.getProperty("store.schema");
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

    void run() throws Exception {
        checkClusterMembers();

        final ExecutorService executor = Executors.newFixedThreadPool(numConcClients);

        final AtomicInteger count = new AtomicInteger();

        for (int i = 0; i < numRuns; i++) {
            executor.submit(new Runnable() {

                @Override
                public void run() {
                    final int index = count.incrementAndGet();
                    final String wsName = createWorkspace(index);
                    final String dsName = createDataStore(wsName, index);
                    final String ftName = createFeatureTypeAndLayer(wsName, dsName, index);

                    Map<String, String> attributes = getAttributes(wsName, dsName, ftName);

                    verifyDescribeFeatureType(wsName, dsName, ftName, attributes.size());

                    modifyFeatureType(wsName, dsName, ftName, attributes);

                    verifyFeatureType(wsName, dsName, ftName, 1);
                    verifyDescribeFeatureType(wsName, dsName, ftName, 1);
                    verifyGetFeatures(wsName, dsName, ftName);

                    addFeatureTypeAttributes(wsName, dsName, ftName, attributes);

                    verifyFeatureType(wsName, dsName, ftName, attributes.size());
                    verifyDescribeFeatureType(wsName, dsName, ftName, attributes.size());
                    verifyGetFeatures(wsName, dsName, ftName);

                    delete("rest/layers/" + ftName + ".xml");
                    delete("rest/workspaces/" + wsName + "/datastores/" + dsName + "/featuretypes/"
                            + ftName + ".xml");
                    delete("rest/workspaces/" + wsName + "/datastores/" + dsName + ".xml");
                    delete("rest/workspaces/" + wsName + ".xml");

                }
            });
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
            log(msg);
            Representation representation = client.get();
        }
    }

    private void log(String msg) {
        System.out.println(msg);
    }

    private void delete(final String relativePath) {
        final ClientResource client = newClient(relativePath);
        client.getRequest().setResourceRef(relativePath);

        Reference targetRef = client.getRequest().getResourceRef().getTargetRef();
        try {
            client.delete();
            Status status = client.getResponse().getStatus();
            System.out.printf("Delete response for %s: %s\n", targetRef, status);
        } catch (Exception e) {
            System.out.printf("Exception deleting %s: %s\n", targetRef, e.getMessage());
        }
    }

    private String createFeatureTypeAndLayer(final String wsName, final String dsName,
            final int index) {
        final String ftName = storeTable + "-clustertest-" + index;
        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes";
        final String ftXml = "<featureType>\n" + //
                "  <name>" + ftName + "</name>\n" + //
                "  <nativeName>" + storeTable + "</nativeName>\n" + //
                "  <title>" + storeTable + " cluster stress test #" + index + "</title>\n" + //
                "  <srs>EPSG:4326</srs>\n" + //
                "</featureType>\n";
        postXml(relativePath, ftXml);
        return ftName;
    }

    private void verifyDescribeFeatureType(final String wsName, final String dsName,
            final String ftName, final int expectedAttributeCount) {

        for (String version : Arrays.asList("1.0.0")) {

            String relativePath = wsName + "/ows?service=WFS&version=" + version
                    + "&request=DescribeFeatureType&typeName=" + wsName + ":" + ftName;

            for (int i = 0; i < clusterMembers.size(); i++) {
                final ClientResource client = newClient(relativePath);
                client.getRequest().setResourceRef(relativePath);
                client.setRetryOnError(false);
                Reference targetRef = client.getRequest().getResourceRef().getTargetRef();
                Representation representation;
                try {
                    representation = client.get();
                } catch (Exception e) {
                    log("ERROR GET " + targetRef + ": " + e.getMessage());
                    continue;
                }
                Status status = client.getResponse().getStatus();

                log("GET " + targetRef + ": " + status);
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
                if (expectedAttributeCount != count) {
                    log(String.format("Expected %d attributes, got %d:\n%s\n",
                            expectedAttributeCount, count, stringRep));
                }
            }
        }
    }

    private void verifyGetFeatures(final String wsName, final String dsName, final String ftName) {

        for (String version : Arrays.asList("1.0.0")) {

            String relativePath = wsName + "/ows?service=WFS&version=" + version
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
                try {
                    url = new URL(targetRef.toString());
                    connection = url.openConnection();
                } catch (Exception e) {
                    log("ERROR Can't connect to " + targetRef + ": " + e.getMessage());
                    continue;
                }

                if (gsUser != null && gsPassword != null) {
                    String usrpwd = gsUser + ":" + gsPassword;
                    String encodedAuthorization = Base64.encode(usrpwd.getBytes(Charsets.UTF_8),
                            false);
                    connection.setRequestProperty("Authorization", "Basic " + encodedAuthorization);
                }
                ByteArrayOutputStream to = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    ByteStreams.copy(in, to);
                } catch (IOException ex) {
                    log("ERROR GET " + targetRef + ": " + ex.getMessage());
                    continue;
                }

                String stringRep = to.toString();
                if (stringRep.contains("FeatureCollection")) {
                    log("GET " + targetRef + ": OK");
                } else {
                    log("ERROR GET " + targetRef + ": " + stringRep);
                }
            }
        }
    }

    private Map<String, String> getAttributes(final String wsName, final String dsName,
            final String ftName) {

        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes/" + ftName + ".xml";

        final ClientResource client = newClient(relativePath);
        client.getRequest().setResourceRef(relativePath);

        Map<String, String> attNamesAndBindings = new HashMap<String, String>();
        try {
            Representation representation = client.get();
            Status status = client.getResponse().getStatus();

            Reference targetRef = client.getRequest().getResourceRef().getTargetRef();
            log("GET " + targetRef + ": " + status);
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
            final int expectedAttributeCount) {
        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes/" + ftName + ".xml";
        for (int i = 0; i < clusterMembers.size(); i++) {
            final ClientResource client = newClient(relativePath);
            client.getRequest().setResourceRef(relativePath);

            try {
                Representation representation = client.get();
                Status status = client.getResponse().getStatus();

                Reference targetRef = client.getRequest().getResourceRef().getTargetRef();
                log("GET " + targetRef + ": " + status);
                StringWriter writer = new StringWriter();
                representation.write(writer);
                String stringRep = writer.toString();
                Pattern p = Pattern.compile("<attribute>");
                Matcher m = p.matcher(stringRep);
                int count = 0;
                while (m.find()) {
                    count += 1;
                }
                if (expectedAttributeCount != count) {
                    System.out.println(String.format("Expected %d attributes, got %d:\n%s\n",
                            expectedAttributeCount, count, stringRep));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void addFeatureTypeAttributes(String wsName, String dsName, String ftName,
            Map<String, String> attributes) {
        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes/" + ftName + ".xml";

        String ftXml = "<featureType>\n" + "  <name>" + ftName + "</name>\n"//
                + "  <nativeName>" + storeTable + "</nativeName>\n"//
                + "  <title>" + ftName + " + modified</title>\n"//
                + "  <srs>EPSG:4326</srs>\n"//
                + "  <enabled>true</enabled>\n"//
                + "  <attributes>\n";
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            String name = e.getKey();
            String binding = e.getValue();
            String att = "     <attribute><name>" + name + "</name><binding>" + binding
                    + "</binding></attribute>\n";
            ftXml += att;
        }
        ftXml += "  </attributes>\n"//
                + "</featureType>\n";
        putXml(relativePath, ftXml);
    }

    private void modifyFeatureType(final String wsName, final String dsName, final String ftName,
            final Map<String, String> attributes) {

        final String relativePath = "rest/workspaces/" + wsName + "/datastores/" + dsName
                + "/featuretypes/" + ftName + ".xml";

        Entry<String, String> entry = attributes.entrySet().iterator().next();
        String name = entry.getKey();
        String binding = entry.getValue();
        final String ftXml = "<featureType>\n" + "  <name>" + ftName
                + "</name>\n"//
                + "  <nativeName>" + storeTable
                + "</nativeName>\n"//
                + "  <title>" + ftName
                + " + modified</title>\n"//
                + "  <srs>EPSG:4326</srs>\n"//
                + "  <enabled>true</enabled>\n"//
                + "  <attributes>\n"//
                + "     <attribute>\n"//
                + "             <name>" + name + "</name><binding>" + binding + "</binding>\n"
                + "     </attribute>\n"//
                + "  </attributes>\n"//
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
                log(method.getName() + " ERROR to " + targetRef + ": server response: "
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
        log(method.getName() + " to " + targetRef + ": " + response.getStatus());
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
}
