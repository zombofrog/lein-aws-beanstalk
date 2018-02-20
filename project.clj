(def aws-java-sdk-version "1.11.276")

(def bcprov-jdk15on-version "1.59")

(defproject lein-aws-beanstalk "0.2.8-SNAPSHOT"
	:description "Leiningen plugin for Amazon's Elastic Beanstalk"
	:url "https://github.com/zombofrog/lein-aws-beanstalk"

	:min-lein-version "2.8.0"
	:eval-in-leiningen true

	:dependencies [[org.clojure/clojure "1.9.0"]
	               [com.amazonaws/aws-java-sdk ~aws-java-sdk-version]
	               [org.bouncycastle/bcprov-jdk15on ~bcprov-jdk15on-version]]

	:plugins
	[[lein-ring "0.12.3"]
	 [lein-ancient "0.6.15" :exclusions [org.clojure/clojure]]])
