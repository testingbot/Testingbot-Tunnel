TestingBot.com Tunnel to run Cloud Selenium tests on your local computer or staging environment.

You can find more information on https://testingbot.com/support/other/tunnel

About
-------

Whether you want to test on your local computer (localhost), on a staging server inside your LAN, or on a computer across VPN, our TestingBot Tunnel makes all of this possible in a secure and reliable way.

You will no longer need to open up ports on your firewall or whitelist our IP range to run tests on your staging environment. 
Below are some of the features of the TestingBot Tunnel:

* Fast: at our end of the tunnel we keep a cache of static content, to reduce traffic inside the tunnel.
* Secure: when you start the Tunnel, a pristine VM server is booted just for you and will last until you end the tunnel.
* Robust: full HTTP(s) support, coded in Java
* Easy to set up and use.

Install
-------

To start the tunnel, enter the following command:
    java -jar testingbot-tunnel.jar key secret

You can obtain a free key and secret from https://testingbot.com

Now point your tests to use localhost and port 4445
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
Compile from Source
-------------------

To compile and test the Jar yourself you can use the following command:

    mvn assembly:assembly

Copyright
---------

Copyright (c) TestingBot.com
See the LICENSE for more information.
