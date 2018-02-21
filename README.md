# lein-aws-beanstalk

[![Clojars Project](https://img.shields.io/clojars/v/lein-aws-beanstalk.svg)](https://clojars.org/lein-aws-beanstalk)

Leiningen plugin for Amazon's [Elastic Beanstalk][1].

## Prerequisites

You will need an [Amazon Web Services][2] account, and know your
account key and secret key.

You will also need to be signed up for Elastic Beanstalk.

## Basic Configuration

To use lein-aws-beanstalk, you'll need to add a few additional values to
your `project.clj` file.

First, add lein-aws-beanstalk as a plugin:

```clojure
:plugins [[lein-aws-beanstalk "0.2.8-SNAPHOT"]]
```

Then add credentials to your `~/.aws/credentials` file:

```yaml
[default]
aws_access_key_id={YOUR_ACCESS_KEY_ID}
aws_secret_access_key={YOUR_SECRET_ACCESS_KEY}

[profile2]
aws_access_key_id={YOUR_ACCESS_KEY_ID}
aws_secret_access_key={YOUR_SECRET_ACCESS_KEY}
```

Or just set env variables `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` if you use Jenkins or GitLab.

Then add application beanstalk and it's environments definitions to your `project.clj` file:

```clojure
:aws 
  {:beanstalk 
    {:app-name "hello-world"
     :bucket "hello-world-bucket"
     :region "eu-central-1"
     :description "the misty woods of kekistan"
     :environments [
      {:name "development"
       :cname-prefix "hello-world-dev"
       :description "hello-workd dev env"
       :solution-stack-name "64bit Amazon Linux 2017.09 v2.7.5 running Tomcat 8 Java 8"
       :option-settings
        {"aws:elasticbeanstalk:application:environment"
          {"APP_ENV" "dev"}}}
      {:name "production"
       :cname-prefix "hello-world-prod"
       :description "hello-workd prod env"
       :solution-stack-name "64bit Amazon Linux 2017.09 v2.7.5 running Tomcat 8 Java 8"
       :option-settings
        {"aws:elasticbeanstalk:application:environment"
          {"APP_ENV" "prod"}}}]}}
``` 

All options are mandatory.

Finally, lein-aws-beanstalk uses lein-ring for packaging your
application, so all of lein-ring's configuration applies as well.
At a minimum, you'll need to your `project.clj` a reference to
your application's top-level handler and uberwar-name, e.g.:

```clojure
:ring {:handler      hello-world.core/handler
       :uberwar-name "hello-world.war"}
```

See the documentation for [lein-ring](https://github.com/weavejester/lein-ring)
for more about the options it provides.

### Deploy

You should now be able to deploy your application to the Amazon cloud
using the following command:

    $ lein beanstalk deploy development

### Info

To get information about the application itself run

    $ lein beanstalk info
    Application Name : myapp
    Description      : My Awesome Compojure App
    Last 5 Versions  : 0.1.0-20110209030504
                       0.1.0-20110209030031
                       0.1.0-20110209025533
                       0.1.0-20110209021110
                       0.1.0-20110209015216
    Created On       : Wed Feb 09 03:00:45 EST 2011
    Updated On       : Wed Feb 09 03:00:45 EST 2011
    Deployed Envs    : development (Ready)
                       staging (Ready)
                       production (Terminated)

and information about a particular environment execute

    $ lein beanstalk info development
    Environment Id   : e-lm32mpkr6t
    Application Name : myapp
    Environment Name : development
    Description      : Default environment for the myapp application.
    URL              : development-feihvibqb.elasticbeanstalk.com
    LoadBalancer URL : awseb-myapp-46156215.us-east-1.elb.amazonaws.com
    Status           : Ready
    Health           : Green
    Current Version  : 0.1.0-20110209030504
    Solution Stack   : 32bit Amazon Linux running Tomcat 6
    Created On       : Tue Feb 08 08:01:44 EST 2011
    Updated On       : Tue Feb 08 08:05:01 EST 2011

### Shutdown

To shutdown an existing environment use the following command

    $ lein beanstalk terminate development

This terminates the environment and all of its resources, i.e.
the Auto Scaling group, LoadBalancer, etc.

### Cleanup

To remove any unused versions from the S3 bucket run

    $ lein beanstalk clean

## Trouble-Shooting

Q: Why does my deployed web application still shows up as 'red' in the
Elastic Beanstalk console?

A: Elastic Beanstalk sends a HTTP `HEAD` request to '/' to check if
the application is running. Simply add the necessary handling to the
application. e.g. for Compojure add

```clojure
(HEAD "/" [] "")
```

[1]: http://aws.amazon.com/elasticbeanstalk
[2]: http://aws.amazon.com
[3]: http://aws.amazon.com/s3