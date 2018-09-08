I'm continuing my journey of learning Docker. I am still keeping it simple at this point. This time around, I am going to tackle converting a Spring and Cassandra application to use containers instead of running locally on the host machine. More precisely, using Spring Data Cassandra to sort out the application.

I wish I looked at doing this change a while ago. I have written a fair amount of posts on Cassandra and each time I had to <code>cd</code> to the correct directory or have a shortcut to start it up. I guess it's not that big of a deal, but there were a few other things involved. Such as, dropping and recreating keyspaces so I could test my application from scratch. Now, I just delete the container and restart it. To me anyway, this is helpful!

This post will be slightly different from my previous post, <a href="https://lankydanblog.com/2018/09/02/using-docker-to-shove-an-existing-application-into-some-containers/" target="_blank" rel="noopener">Using Docker to shove an existing application into containers</a>. Instead, I will focus slightly more on the application side and remove the intermediate steps of using only Docker and instead will jump straight into Docker Compose.
<h3>Containers, containers, containers</h3>
I think it's best to start on the container side of the project since the application depends on the configuration of the Cassandra container.

Let's go!

[gist https://gist.github.com/lankydan/073a6d63864c42f8cf3fe0350f50c834 /]

There's not much going on here. This <code>Dockerfile</code> builds the Spring application image that will be put into a container in a few moments.

Next up is the <code>docker-compose</code> file. This is will build both the Spring application and Cassandra containers:

[gist https://gist.github.com/lankydan/ca4c8e02327b1898f330961fef74fb6a /]

Again, there isn't too much here. The <code>app</code> container builds the Spring application using the <code>Dockerfile</code> defined previously. The <code>cassandra</code> container instead relies on an existing image, appropriately named <code>cassandra</code>.

One thing that stands out is that the <code>restart</code> property is set to <code>always</code>. This was my lazy attempt to get past how long Cassandra takes to start and the fact that all the containers started with <code>docker-compose</code> start at the same time. This lead to a situation where the application is trying to connect to Cassandra without it being ready. Unfortunately, this leads to the application dying. I hoped that it would have some retry capability for initial connectivity built in... But it does not.

When we go through the code, we will see how to deal with the initial Cassandra connection programmatically instead of relying on the application dying and restarting multiple times. You will see my version of handling the connection anyway... I'm not really a fan of my solution but everything else I tried caused me much more pain.
<h3>A dash of code</h3>
I said this post would focus more on the application code, which it will, but we are not going to dive into everything I put within this application and how to use Cassandra. For that sort of information, you can have a look at my older posts, which I'll link at the end. What we will do though, is examine the configuration code that creates the beans that connect to Cassandra.

First, let's go through <code>ClusterConfig</code> which sets up the Cassandra cluster:

[gist https://gist.github.com/lankydan/1e4206e50ac47f46562832aaa0810be5 /]

There isn't too much there, but there would be even less if Spring would retry the initial connection to Cassandra. Anyway, let's leave that part for a few minutes and focus on the other points in this class.

The original reason I created <code>ClusterConfig</code> was to create the keyspace that the application will use. To do this <code>getKeyspaceCreations</code> was overridden. When the application connects it will execute the query defined in this method to create the keyspace.

If this wasn't needed and the keyspace was created in some other way, for example, a script executed as part of creating the Cassandra container, Spring Boot's auto-configuration could be relied upon instead. This actually allows the whole application to be configured by the properties defined in <code>application.properties</code> and nothing else. Alas, it was not meant to be.

Since we have defined an <code>AbstractClusterConfiguration</code>, Spring Boot will disable its configuration in this area. Therefore, we need to define the <code>contactPoints</code> (I named the variable <code>hosts</code>) manually by overriding the <code>getContactPoints</code> method. Originally this was only defined in <code>application.properties</code>. I realised I needed to make this change once I started getting the following error:
<pre>All host(s) tried for query failed (tried: localhost/127.0.0.1:9042 (com.datastax.driver.core.exceptions.TransportException: [localhost/127.0.0.1:9042] Cannot connect))
</pre>
Before I created <code>ClusterConfig</code> the address was <code>cassandra</code> rather than <code>localhost</code>.

No other properties for the cluster need to be configured as Spring's defaults are good enough for this scenario.

I have mentioned <code>application.properties</code> so much at this point, I should probably show you what is in it.

[gist https://gist.github.com/lankydan/3f400cefe71e9779ef3c01146d6131be /]

<code>keyspace-name</code> and <code>contact-points</code> have already popped up since they are related to configuring the cluster. <code>schema-action</code> is needed to create tables based on the entities in the project. We don't need to do anything else here as auto-configuration is still working in this area.

The fact that the <code>contact-points</code> value is set to <code>cassandra</code> is very important. This domain name originates from the name given to the container, in this case, <code>cassandra</code>. Therefore either <code>cassandra</code> can be used or the actual IP of the container. The domain name is definitely easier since it will always be static between deployments. Just to test this theory out, you can change the name of the <code>cassandra</code> container to whatever you want and it will still connect, as long as you change it in the <code>application.properties</code> as well.

Back to the <code>ClusterConfig</code> code. More precisely, the <code>cluster</code> bean. I have pasted the code below again so it's easier to look at:

[gist https://gist.github.com/lankydan/24c36d565ac746b89c6f5e7f5d0e2e8f /]

This code is only needed to allow retries on the initial Cassandra connection. It is annoying, but I could not come up with another simple solution. If you have a nicer one then please let me know!

What I have done is actually quite simple, but the code itself is not very nice. The <code>cluster</code> method is a carbon copy of the overridden version from <code>AbstractClusterConfiguration</code>, with the exception of the <code>RetryingCassandraClusterFactoryBean</code> (my own class). The original function used a <code>CassandraClusterFactoryBean</code> (Spring class) instead.

Below is the <code>RetryingCassandraClusterFactoryBean</code>:

[gist https://gist.github.com/lankydan/d748cd95bf62920a983bb829577f1d32 /]

The <code>afterPropertiesSet</code> method in the original <code>CassandraClusterFactoryBean</code> takes its values and creates the representation of a Cassandra cluster by finally delegating to the Datastax Java driver. As I have mentioned throughout the post. If it fails to establish a connection, an exception will be thrown and if not caught will cause the application to terminate. That is the whole point of the above code. It wraps the <code>afterPropertiesSet</code> in a try-catch block specified for the exceptions that can be thrown.

The <code>sleep</code> is added to give Cassandra some time to actually start up. There is no point trying to reconnect straight away when the previous attempt failed.

Using this code the application will eventually connect to Cassandra.

At this point, I would normally show you some meaningless logs to prove that the application works, but in this situation, it really does not bring anything to the table. Just trust me when I say, if you run the below command:
<pre>mvn clean install && docker-compose up
</pre>
Then the Spring application image is created and both containers are spun up.
<h3>Conclusion</h3>
We have had a look at how to put a Spring application that connects to a Cassandra database into containers. One for the application and another for Cassandra. The application image is built from the project's code, whereas the Cassandra image is taken from Docker Hub. The image name is <code>cassandra</code> just to make sure no one forgets. In general connecting the two containers together was relatively simple but the application needed some adjustments to allow retries when connecting to Cassandra running in the other container. This made the code a bit uglier, but it works at least... Thanks to the code written in this post, I now have another application that I don't need to set up on my own machine.

The code used in this post can be found on my <a href="https://github.com/lankydan/spring-data-cassandra-docker" target="_blank" rel="noopener">GitHub</a>.

If you found this post helpful, you can follow me on Twitter at <a href="http://www.twitter.com/LankyDanDev" target="_blank" rel="noopener">@LankyDanDev</a> to keep up with my new posts.
<h3>Links to my Spring Data Cassandra posts</h3>
<ul>
	<li><a href="https://lankydanblog.com/2017/10/12/getting-started-with-spring-data-cassandra/" target="_blank" rel="noopener">Getting started with Spring Data Cassandra</a></li>
	<li><a href="https://lankydanblog.com/2017/10/22/separate-keyspaces-with-spring-data-cassandra/" target="_blank" rel="noopener">Separate keyspaces with Spring Data Cassandra</a></li>
	<li><a href="https://lankydanblog.com/2017/11/12/multiple-keyspaces-using-a-single-spring-data-cassandratemplate/" target="_blank" rel="noopener">Multiple keyspaces using a single Spring Data CassandraTemplate</a></li>
	<li><a href="https://lankydanblog.com/2017/11/26/more-complex-modelling-with-spring-data-cassandra/" target="_blank" rel="noopener">More complex modelling with Spring Data Cassandra</a></li>
	<li><a href="https://lankydanblog.com/2017/12/03/startup-and-shutdown-scripts-in-spring-data-cassandra/" target="_blank" rel="noopener">Startup and shutdown scripts in Spring Data Cassandra</a></li>
	<li><a href="https://lankydanblog.com/2017/12/11/reactive-streams-with-spring-data-cassandra/" target="_blank" rel="noopener">Reactive Streams with Spring Data Cassandra</a></li>
	<li><a href="https://lankydanblog.com/2017/12/16/plumbing-included-with-auto-configuration-in-spring-data-cassandra/" target="_blank" rel="noopener">Plumbing included with auto-configuration in Spring Data Cassandra</a></li>
	<li><a href="https://lankydanblog.com/2018/04/15/interacting-with-cassandra-using-the-datastax-java-driver/" target="_blank" rel="noopener">Interacting with Cassandra using the Datastax Java driver</a></li>
</ul>
Wow, I didn't realise I wrote so many Cassandra posts.