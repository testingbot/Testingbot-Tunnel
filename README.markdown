# TestingBot Tunnel

This is a Java-based application to test websites on your local computer or staging environment with the TestingBot.com browser cloud.
A secure connection is established between your machine and the TestingBot.com cloud.
You can find more information on https://testingbot.com/support/other/tunnel

About
-------

Whether you want to test on your local computer (localhost), on a staging server inside your LAN, or on a computer across VPN, TestingBot Tunnel makes all of this possible in a secure and reliable way.

You will no longer need to open up ports on your firewall or whitelist our IP range to run tests on your staging environment.
Below are some of the features of the TestingBot Tunnel:

* Fast: at TestingBot's end of the tunnel we keep a cache of static content, to reduce traffic inside the tunnel.
* Secure: based on an SSH Tunnel.
* Robust: full HTTP(S) support, coded in Java
* Easy to set up and use, supported on all operating systems.

Prerequisites
-------

This version of the tunnel requires Java 8 or higher. If you'd like to run on Java < 8, please use [TestingBot Tunnel 1.21](https://github.com/testingbot/Testingbot-Tunnel/tree/TestingBotTunnel-1.21) (no longer maintained)

**NOTE:** If you use the containerized tunnel, Java is not needed. See below under the *Docker*-header.

Install
-------

To start the tunnel, enter the following command:
    `java -jar testingbot-tunnel.jar key secret`

You can obtain a free key and secret from https://testingbot.com/members/user/edit

**Hint:** Instead of passing the key and secret to the command, you can have them as environment variables `${TESTINGBOT_KEY}` and `${TESTINGBOT_SECRET}`.

Options
-------

The tunnel comes with various options:

|Command|Description|
|---------|-------------|
|-a,--auth <host:port:user:passwd>|Performs Basic Authentication for specific hosts.|
|-b,--nobump|Do not perform SSL bumping.|
|-d,--debug|Enables debug messages. Will output request/response headers.|
|-dns,--dns|Use a custom DNS server. For example: 8.8.8.8|
|--doctor|Perform sanity/health checks to detect possible misconfiguration or problems.|
|--extra-headers <JSON Map with Header Key and Value>|Inject extra headers in the requests the tunnel makes.|
|-f,--readyfile <FILE>|This file will be touched when the tunnel is ready for usage|
|-F,--fast-fail-regexps <OPTIONS>|Specify domains you don't want to proxy, comma separated.|
|-h,--help|Displays help text|
|-i,--tunnel-identifier <id>|Add an identifier to this tunnel connection. In case of multiple tunnels, specify this identifier in your desired capabilities to use this specific tunnel.|
|-j,--localproxy <port>|The port to launch the local proxy on (default 8087).|
|-l,--logfile <FILE>|Write logging to a file.|
|--metrics-port <port>|Use the specified port to access metrics. Default port 8003|
|-P,--se-port <PORT>|The local port your Selenium test should connect to. Default port is 4445|
|-p,--hubport <HUBPORT>|Use this if you want to connect to port 80 on our hub instead of the default port 4444|
|--pac <arg>|Proxy autoconfiguration. Should be a http(s) URL|
|-q,--nocache|Bypass our Caching Proxy running on our tunnel VM.|
|-v,--version|Displays the current version of the Tunnel|
|-w,--web <directory>|Point to a directory for testing. Creates a local webserver.|
|-x,--noproxy|Do not start a local proxy (requires user provided proxy server on port 8087)|
|-Y,--proxy <PROXYHOST:PROXYPORT>|Specify an upstream proxy.|
|-z,--proxy-userpwd <user:pwd>|Username and password required to access the proxy configured with --proxy.|


Example
-------
To use the tunnel, simply start it:

```
$ java -jar testingbot-tunnel.jar <key> <secret>
```

Now point your tests to use the tunnel's IP (localhost if the .jar is running on your local computer) and port 4445
```ruby
require "rubygems"
require 'testingbot'
gem "selenium-client"
gem "selenium-webdriver"
require "selenium-webdriver"
require "selenium/client"

caps = {
  :browserName => "internet explorer",
  :version => "latest",
  :platform => "WINDOWS"
}

urlhub = "http://key:secret@localhost:4445/wd/hub"
client = Selenium::WebDriver::Remote::Http::Default.new
client.timeout = 120

webdriver = Selenium::WebDriver.for :remote, :url => urlhub,
                :desired_capabilities => caps, :http_client => client
webdriver.navigate.to "http://staging.local"
puts webdriver.title
webdriver.quit
```

Node
-------
We have created a NodeJS based launcher which you can use in your NodeJS tests and projects:
[testingbot-tunnel-launcher](https://github.com/testingbot/testingbot-tunnel-launcher)

More documentation about this tunnel is available on https://testingbot.com/support/other/tunnel

Docker
------
For those who don't want to deal with Java, we also provide a containerized version of the tunnel.

To start the tunnel, run:
```
$ docker run -p 4445:4445 --net=host testingbot/tunnel:2.9 <key> <secret> <options>
```

Alternatively:
```
$ docker run -p 4445:4445 --net=host -e TESTINGBOT_KEY=<key> -e TESTINGBOT_SECRET=<secret> testingbot/tunnel:2.9 <options>
```

To build the docker image, run:
```
docker buildx build --platform linux/amd64,linux/arm64 --no-cache -t testingbot/tunnel:4.0 -t testingbot/tunnel:latest .
```

Compile from Source
-------------------

To compile and test the Jar yourself you can use the following command:

    mvn package

Copyright
---------

Copyright (c) TestingBot.com
See the LICENSE for more information.
