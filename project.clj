(defproject lein-aws-beanstalk "0.2.8-SNAPSHOT"
	:description "Leiningen plugin for Amazon's Elastic Beanstalk"
	:url "https://github.com/zombofrog/lein-aws-beanstalk"
	:dependencies [[org.clojure/clojure "1.9.0-RC2"]
	               [com.amazonaws/aws-java-sdk
	                "1.11.275"
	                :exclusions
	                [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor
	                 com.fasterxml.jackson.core/jackson-databind]]
	               [com.fasterxml.jackson.core/jackson-core "2.9.0"]
	               [com.fasterxml.jackson.core/jackson-databind "2.9.0"]
	               [lein-ring "0.12.3"]]
	:eval-in-leiningen true
	:profiles
	{:dev      {:dependencies [[org.bouncycastle/bcprov-jdk15on "1.59"]]}
	 :provided {:dependencies [[org.bouncycastle/bcprov-jdk15on "1.59"]]}})
