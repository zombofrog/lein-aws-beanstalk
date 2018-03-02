(defproject lein-aws-beanstalk "0.2.82-SNAPSHOT"
	:description "Leiningen plugin for Amazon's Elastic Beanstalk"
	:url "https://github.com/zombofrog/lein-aws-beanstalk"
	:min-lein-version "2.8.0"
	:eval-in-leiningen true
	:dependencies [[org.clojure/clojure "1.9.0"]
	               [com.amazonaws/aws-java-sdk "1.11.276"]]
	:plugins [[lein-ring "0.12.3"]])
