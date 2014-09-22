# GeoServer cluster stress tests for jdbcconfig and REST configuration

## Requirements:
* Java 7
* Maven 3

Run `mvn clean install assembly:single` to create the executable jar under `target/reststress-1.0-jar-with-dependencies.jar`. 

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