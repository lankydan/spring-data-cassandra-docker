I'm continuing my journey of learning Docker. I am still keeping it simple at this point. This time around, I am going to tackle converting a Spring and Cassandra application to use containers instead of running locally on the host machine. More precisely, using Spring Data Cassandra to sort out the application. 

I wish I looked at doing this change a while ago. I have written a fair amount of posts on Cassandra and each time I had to `cd` to the correct directory or have a shortcut to start it up. I guess it's not that big a of a deal, but there were a few other things involved. Such as, dropping and recreating keyspaces so I could test my application from scratch. Now, I just delete the container and restart it. To me anyway, this is helpful!

This post will be slightly different from my previous post, [Using Docker to shove an existing application into containers](https://lankydanblog.com/2018/09/02/using-docker-to-shove-an-existing-application-into-some-containers/). Instead, I will focus slightly more on the application side and remove the intermediate steps of using only Docker and instead will jump straight into Docker Compose.

### Containers, containers, containers

I think it's best to start on the container side of the project since the application depends on the configuration of the Cassandra container.

Lets go!
```Dockerfile
FROM openjdk:10-jre-slim
LABEL maintainer="Dan Newton"
ARG JAR_FILE
ADD target/${JAR_FILE} app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```
There's not much going on here. This `Dockerfile` builds the Spring application image that will be put into a container in a few moments.

Next up is the `docker-compose` file. This is will build both the Spring application and Cassandra containers:
```yml
version: '3'
services:
  app:
    build:
      context: .
      args:
        JAR_FILE: /spring-data-cassandra-docker-1.0.0.jar
    restart: always
  cassandra:
    image: "cassandra"
```
Again, there isn't too much here. The `app` container builds the Spring application using the `Dockerfile` defined previously. The `cassandra` container instead relies on a existing image, appropriately named `cassandra`. 

One thing that stands out is that the `restart` property is set to `always`. This was my lazy attempt to get past how long Cassandra takes to start and the fact that all the containers started with `docker-compose` start at the same time. This lead to a situation where the application is trying to connect to Cassandra without it being ready. Unfortunately, this leads to the application dying. I hoped that it would have some retry capability for initial connectivity built in... But it does not.

When we go through the code, we will see how to deal with the initial Cassandra connection programmatically instead of relying on the application dying and restarting multiple times. You will see my version of handling the connection anyway... I'm not really a fan of my solution but everything else I tried caused me much more pain.

### A dash of code

I said this post would focus more on the application code, which it will, but we are not going to dive into everything I put within this application and how to use Cassandra. For that sort of information you can have a look at my older posts, which I'll link at the end. What we will do though, is examine the configuration code that creates the beans that connect to Cassandra.

First, let's go through `ClusterConfig` which sets up the Cassandra cluster:
```java
@Configuration
public class ClusterConfig extends AbstractClusterConfiguration {

  private final String keyspace;
  private final String hosts;

  ClusterConfig(
      @Value("${spring.data.cassandra.keyspace-name}") String keyspace,
      @Value("${spring.data.cassandra.contact-points}") String hosts) {
    this.keyspace = keyspace;
    this.hosts = hosts;
  }

  @Bean
  @Override
  public CassandraClusterFactoryBean cluster() {

    RetryingCassandraClusterFactoryBean bean = new RetryingCassandraClusterFactoryBean();

    bean.setAddressTranslator(getAddressTranslator());
    bean.setAuthProvider(getAuthProvider());
    bean.setClusterBuilderConfigurer(getClusterBuilderConfigurer());
    bean.setClusterName(getClusterName());
    bean.setCompressionType(getCompressionType());
    bean.setContactPoints(getContactPoints());
    bean.setLoadBalancingPolicy(getLoadBalancingPolicy());
    bean.setMaxSchemaAgreementWaitSeconds(getMaxSchemaAgreementWaitSeconds());
    bean.setMetricsEnabled(getMetricsEnabled());
    bean.setNettyOptions(getNettyOptions());
    bean.setPoolingOptions(getPoolingOptions());
    bean.setPort(getPort());
    bean.setProtocolVersion(getProtocolVersion());
    bean.setQueryOptions(getQueryOptions());
    bean.setReconnectionPolicy(getReconnectionPolicy());
    bean.setRetryPolicy(getRetryPolicy());
    bean.setSpeculativeExecutionPolicy(getSpeculativeExecutionPolicy());
    bean.setSocketOptions(getSocketOptions());
    bean.setTimestampGenerator(getTimestampGenerator());

    bean.setKeyspaceCreations(getKeyspaceCreations());
    bean.setKeyspaceDrops(getKeyspaceDrops());
    bean.setStartupScripts(getStartupScripts());
    bean.setShutdownScripts(getShutdownScripts());

    return bean;
  }

  @Override
  protected List<CreateKeyspaceSpecification> getKeyspaceCreations() {
    final CreateKeyspaceSpecification specification =
        CreateKeyspaceSpecification.createKeyspace(keyspace)
            .ifNotExists()
            .with(KeyspaceOption.DURABLE_WRITES, true)
            .withSimpleReplication();
    return List.of(specification);
  }

  @Override
  protected String getContactPoints() {
    return hosts;
  }
}
```
There isn't too much there, but there would be even less if Spring would retry the initial connection to Cassandra. Anyway, let's leave that part for a few minutes and focus on the other points in this class.

The original reason I created `ClusterConfig` was to create the keyspace that the application will use. To do this `getKeyspaceCreations` was overridden. When the application connects it will execute the query defined in this method to create the keyspace. 

If this wasn't needed and the keyspace was created in some other way, for example a script executed as part of creating the Cassandra container, Spring Boot's auto-configuration could be relied upon instead. This actually allows the whole application to be configured by the properties defined in `application.properties` and nothing else. Alas, it was not meant to be.

Since we have defined an `AbstractClusterConfiguration`, Spring Boot will disable it's configuration in this area. Therefore, we need to define the `contactPoints` (I named the variable `hosts`) manually by overriding the `getContactPoints` method. Originally this was only defined in `application.properties`. I realised I needed to make this change once I started getting the following error:
```
All host(s) tried for query failed (tried: localhost/127.0.0.1:9042 (com.datastax.driver.core.exceptions.TransportException: [localhost/127.0.0.1:9042] Cannot connect))
```
Before I created created `ClusterConfig` the address was `cassandra` rather than `localhost`. 

No other properties for the cluster need to be configured as Spring's defaults are good enough for this scenario.

I have mentioned `application.properties` so much at this point, I should probably show you what is in it.
```application.properties
spring.data.cassandra.keyspace-name=mykeyspace
spring.data.cassandra.schema-action=CREATE_IF_NOT_EXISTS
spring.data.cassandra.contact-points=cassandra
```
`keyspace-name` and `contact-points` have already popped up since they are related to configuring the cluster. `schema-action` is needed to create tables based on the entities in the project. We don't need to do anything else here as auto-configuration is still working in this area.

The fact that the `contact-points` value is set to `cassandra` is very important. This domain name originates from the name given to the container, in this case `cassandra`. Therefore either `cassandra` can be used or the actual IP of the container. The domain name is definitely easier since it will always be static between deployments. Just to test this theory out, you can change the name of the `cassandra` container to whatever you whatever you want and it will still connect, as long as you change it in the `application.properties` as well.

Back to the `ClusterConfig` code. More precisely, the `cluster` bean. I have pasted the code below again so it's easier to look at:
```java
@Configuration
public class ClusterConfig extends AbstractClusterConfiguration {

  // other stuff

  @Bean
  @Override
  public CassandraClusterFactoryBean cluster() {

    RetryingCassandraClusterFactoryBean bean = new RetryingCassandraClusterFactoryBean();

    bean.setAddressTranslator(getAddressTranslator());
    bean.setAuthProvider(getAuthProvider());
    bean.setClusterBuilderConfigurer(getClusterBuilderConfigurer());
    bean.setClusterName(getClusterName());
    bean.setCompressionType(getCompressionType());
    bean.setContactPoints(getContactPoints());
    bean.setLoadBalancingPolicy(getLoadBalancingPolicy());
    bean.setMaxSchemaAgreementWaitSeconds(getMaxSchemaAgreementWaitSeconds());
    bean.setMetricsEnabled(getMetricsEnabled());
    bean.setNettyOptions(getNettyOptions());
    bean.setPoolingOptions(getPoolingOptions());
    bean.setPort(getPort());
    bean.setProtocolVersion(getProtocolVersion());
    bean.setQueryOptions(getQueryOptions());
    bean.setReconnectionPolicy(getReconnectionPolicy());
    bean.setRetryPolicy(getRetryPolicy());
    bean.setSpeculativeExecutionPolicy(getSpeculativeExecutionPolicy());
    bean.setSocketOptions(getSocketOptions());
    bean.setTimestampGenerator(getTimestampGenerator());

    bean.setKeyspaceCreations(getKeyspaceCreations());
    bean.setKeyspaceDrops(getKeyspaceDrops());
    bean.setStartupScripts(getStartupScripts());
    bean.setShutdownScripts(getShutdownScripts());

    return bean;
  }

  // other stuff
}
```
This code is only needed to allow retries on the initial Cassandra connection. It is annoying, but I could not come up with another simple solution. If you have a nicer one then please let me know!

What I have done is actually quite simple, but the code itself is not very nice. The `cluster` method is a carbon copy of the overridden version from `AbstractClusterConfiguration`, with the exception of the `RetryingCassandraClusterFactoryBean` (my own class). The original function used a `CassandraClusterFactoryBean` (Spring class) instead. 

Below is the `RetryingCassandraClusterFactoryBean`:
```java
public class RetryingCassandraClusterFactoryBean extends CassandraClusterFactoryBean {

  private static final Logger LOG =
      LoggerFactory.getLogger(RetryingCassandraClusterFactoryBean.class);

  @Override
  public void afterPropertiesSet() throws Exception {
    connect();
  }

  private void connect() throws Exception {
    try {
      super.afterPropertiesSet();
    } catch (TransportException | IllegalArgumentException | NoHostAvailableException e) {
      LOG.warn(e.getMessage());
      LOG.warn("Retrying connection in 10 seconds");
      sleep();
      connect();
    }
  }

  private void sleep() {
    try {
      Thread.sleep(10000);
    } catch (InterruptedException ignored) {
    }
  }
}
```
The `afterPropertiesSet` method in the original `CassandraClusterFactoryBean` takes its values and creates the representation of a Cassandra cluster by finally delegating to the Datastax Java driver. As I have mentioned throughout the post. If it fails to establish a connection, an exception will be thrown and if not caught will cause the application to terminate. That is the whole point of the above code. It wraps the `afterPropertiesSet` in a try catch block specified for the exceptions that can be thrown.

The `sleep` is added to give Cassandra some time to actually start up. There is no point trying to reconnect straight away when the previous attempt failed.

Using this code the application will eventually connect to Cassandra.

At this point I would normally show you some meaningless logs to prove that the application works, but in this situation it really does not bring anything to the table. Just trust me when I say, if you run the below command:
```
mvn clean install && docker-compose up
```
Then the Spring application image is created and both containers are spun up.

### Conclusion

We have had a look at how to put a Spring application that connects to a Cassandra database into containers. One for the application and another for Cassandra. The application image is built from the project's code, whereas the Cassandra image is taken from Docker Hub. The image name is `cassandra` just to make sure no one forgets. In general connecting the two containers together was relatively simple but the application needed some adjustments to retry connecting to Cassandra running in the other container. This made the code a bit uglier, but it works at least... Thanks to the code written in this post, I now have another application that I don't need to setup on my own machine.