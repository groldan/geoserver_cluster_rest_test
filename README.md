# GeoServer cluster stress tests for jdbcconfig and REST configuration

This is a program to automate system testing of GeoServer configuration through the REST API when using `jdbcconfig` and the `cluster` plugin, to run a cluster of geoserver instances against a single database holding the geoserver catalog objects.

It was used to expose a number of issues and then for regression testing on a live environment, such as a cluster of geoservers running on different EC2 instances, sharing a PostGIS database for data and a PostgreSQL database for catalog configuration.

For instance, the program ensures that, while making config changes to layers/feature types through the REST API to an arbitrary cluster member, the change gets properly reflected in the other members.
The `cluster` plugin has uses a publish-subscribe mechanism (by means of Hazelcast `topics`) so that any cluster member that receives a catalog change request through REST notifies the other members. The other members then shall process the change event making sure any locally cached information such as GeoTools `DataStore` instances are cleaned up, in order to stay current with the config changes.

This program uses a `test.properties` file with environment configuration such as cluster members, geoserver REST credentials, and PostGIS connection info. With that information in place, performs the following actions:

* Creates a workspace, datastore, and layer; using the postgis connection info and table name on a randomly chosen cluster member
	* Obtains the list of attributes for the configured layer
	* Modifies the feature type to contain only one attribute
	* For each cluster member:
		* Verify that the feature type contains the expected number of attributes through the REST API
		* Verify that the feature type contains the expected number of attributes through a WFS DescribeFeatureType request
		* Verify that WFS GetFeature works
	* Restore the original attributes, possibly in different order than the native one
	* For each cluster member:
		* Verify that the feature type contains the expected number of attributes through the REST API
		* Verify that the feature type contains the expected number of attributes through a WFS DescribeFeatureType request
		* Verify that WFS GetFeature works
* Delete the layer, feature type, datastore, and workspace

The above steps are performed concurrently by a configured number of threads and up to a configured total number of executions.
The target for each request to a geoserver instance is selected in a round-robbin fashion.

## Build Requirements:
* Java 7
* Maven 3

Run `mvn clean install assembly:single` to create the executable jar under `target/reststress-1.0-jar-with-dependencies.jar`. 

## Test Environment Requirements:
Configure a GeoServer cluster with jdbcconfig and the cluster plugin.
Have a PostGIS instance that the geoservers can connect to, and a postgis table with more than one attribute.

**Important** make sure there's a "default workspace" configured, to avoid a possible error like the following as this program adds and removes workspaces concurrently:

	<ServiceExceptionReport version="1.2.0" xmlns=...>
	<ServiceException>java.lang.IllegalStateException: No default namespace configured in GeoServer
		No default namespace configured in GeoServer
	</ServiceException></ServiceExceptionReport>
 
Run `java -jar target/reststress-1.0-jar-with-dependencies.jar`.
The first time a `test.properties` file will be created in the working directory. Edit it and follow instructions in it to set the test environment.

Once the `test.properties` config settings are correct, run `java -jar target/reststress-1.0-jar-with-dependencies.jar | tee out.log`.

Once finished, check the `out.log` file for errors. If everything went well, it should only contain lines with the accessed URL's and a `OK` status at the end of each line, like in:

	Checking access to cluster member http://localhost:8081/geoserver/rest/workspaces.xml
	Checking access to cluster member http://localhost:8082/geoserver/rest/workspaces.xml
	Checking access to cluster member http://localhost:8083/geoserver/rest/workspaces.xml
	POST to http://localhost:8081/geoserver/rest/workspaces: Created (201) - Created
	POST to http://localhost:8082/geoserver/rest/workspaces/rest-stress-ws-1/datastores: Created (201) - Created
	POST to http://localhost:8081/geoserver/rest/workspaces: Created (201) - Created
	POST to http://localhost:8082/geoserver/rest/workspaces/rest-stress-ws-2/datastores: Created (201) - Created
	POST to http://localhost:8081/geoserver/rest/workspaces: Created (201) - Created
	POST to http://localhost:8083/geoserver/rest/workspaces/rest-stress-ws-1/datastores/ds-1/featuretypes: Created (201) - Created
	POST to http://localhost:8082/geoserver/rest/workspaces/rest-stress-ws-3/datastores: Created (201) - Created
	POST to http://localhost:8081/geoserver/rest/workspaces: Created (201) - Created
	GET http://localhost:8081/geoserver/rest/workspaces/rest-stress-ws-1/datastores/ds-1/featuretypes/way-clustertest-1.xml: OK (200) - OK
	POST to http://localhost:8082/geoserver/rest/workspaces/rest-stress-ws-4/datastores: Created (201) - Created
	GET http://localhost:8082/geoserver/ows?service=WFS&version=1.0.0&request=DescribeFeatureType&typeName=rest-stress-ws-1:way-clustertest-1: OK (200) - OK
	POST to http://localhost:8083/geoserver/rest/workspaces/rest-stress-ws-2/datastores/ds-2/featuretypes: Created (201) - Created
	GET http://localhost:8083/geoserver/ows?service=WFS&version=1.0.0&request=DescribeFeatureType&typeName=rest-stress-ws-1:way-clustertest-1: OK (200) - OK
	GET http://localhost:8081/geoserver/ows?service=WFS&version=1.0.0&request=DescribeFeatureType&typeName=rest-stress-ws-1:way-clustertest-1: OK (200) - OK
	GET http://localhost:8081/geoserver/rest/workspaces/rest-stress-ws-2/datastores/ds-2/featuretypes/way-clustertest-2.xml: OK (200) - OK
	GET http://localhost:8082/geoserver/ows?service=WFS&version=1.0.0&request=DescribeFeatureType&typeName=rest-stress-ws-2:way-clustertest-2: OK (200) - OK
	GET http://localhost:8083/geoserver/ows?service=WFS&version=1.0.0&request=DescribeFeatureType&typeName=rest-stress-ws-2:way-clustertest-2: OK (200) - OK
	GET http://localhost:8081/geoserver/ows?service=WFS&version=1.0.0&request=DescribeFeatureType&typeName=rest-stress-ws-2:way-clustertest-2: OK (200) - OK
	PUT to http://localhost:8082/geoserver/rest/workspaces/rest-stress-ws-1/datastores/ds-1/featuretypes/way-clustertest-1.xml: OK (200) - OK


`test.properties` contents are as follows:

	#Number of total test runs
	runs=100
	#number of concurrent threads to split the test runs between
	threads=4

	#comma separated list of root geoserver context endpoints constituting
	#the cluster members the REST and WFS requests are to be sent to
	clusterMembers=http://localhost:8081/geoserver,http://localhost:8082/geoserver,http://localhost:8083/geoserver

	#geoserver HTTP basic authentication user and password
	user=admin
	password=geoserver

	#the following are connection parameters to the PostGIS database used by the
	#data stores created during the tests. Make sure the connection information is right.
	store.host=localhost
	store.port=5432
	store.database=postgis
	store.user=postgres
	store.password=geo123

	#the following is a PostGIS table name for which each test run will create a feature
	#type and layer. Make sure it exists as a postgis layer in the databse.
	#store.table=states
	store.table=way