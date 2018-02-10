(defproject lein-aws-beanstalk "0.2.8-SNAPSHOT"
  :description "Leiningen plugin for Amazon's Elastic Beanstalk"
  :url "https://github.com/zombofrog/lein-aws-beanstalk"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [com.amazonaws/aws-java-sdk "1.3.31"]
                 [com.amazonaws/aws-java-sdk-s3 "1.10.5.1"]
                 [lein-ring "0.8.2"]]
  :eval-in-leiningen true)
