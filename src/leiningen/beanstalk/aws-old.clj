(ns leiningen.beanstalk.aws-old
	"AWS-specific libraries."
	(:require
		[clojure.java.io :as io]
		[clojure.string :as str])
	(:import
		java.text.SimpleDateFormat
		[java.util.logging Logger Level]
		[java.security KeyPairGenerator]
		[javax.crypto KeyGenerator]
		[java.util Date UUID]
		com.amazonaws.auth.BasicAWSCredentials
		com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
		com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
		com.amazonaws.services.elasticbeanstalk.model.CreateApplicationVersionRequest
		com.amazonaws.services.elasticbeanstalk.model.CreateEnvironmentRequest
		com.amazonaws.services.elasticbeanstalk.model.DeleteApplicationRequest
		com.amazonaws.services.elasticbeanstalk.model.DeleteApplicationVersionRequest
		com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsRequest
		com.amazonaws.services.elasticbeanstalk.model.UpdateEnvironmentRequest
		com.amazonaws.services.elasticbeanstalk.model.S3Location
		com.amazonaws.services.elasticbeanstalk.model.TerminateEnvironmentRequest
		com.amazonaws.services.s3.AmazonS3Client
		com.amazonaws.services.s3.AmazonS3EncryptionClient
		com.amazonaws.services.s3.model.Region
		com.amazonaws.services.s3.model.EncryptionMaterials
		com.amazonaws.services.s3.model.StaticEncryptionMaterialsProvider
		com.amazonaws.services.s3.model.PutObjectRequest
		com.amazonaws.services.s3.model.ObjectMetadata
		com.amazonaws.services.s3.model.CryptoConfiguration
		com.amazonaws.services.s3.model.CryptoMode
		com.amazonaws.regions.RegionUtils))

(defonce ^:private current-timestamp
	(.format (SimpleDateFormat. "yyyyMMddHHmmss") (Date.)))

;(def ^:private project*
;	{:description "The misty woods of Kekistan"
;	 :aws         {:beanstalk {:app-name           "test"
;	                           :s3-bucket          "zomboura.test"
;	                           :region             "eu-central-1"
;	                           :s3-endpoint        ["s3.eu-central-1.amazonaws.com" "EU_Frankfurt"]
;	                           :beanstalk-endpoint "elasticbeanstalk.eu-central-1.amazonaws.com"
;	                           :v4                 true
;	                           :environments
;	                           [{:name            "quality-assurance"
;	                             :cname-prefix    "test-qa"
;	                             :platform-arm    ""
;	                             :option-settings {}}
;	                            {:name         "production"
;	                             :cname-prefix "test-prod"
;	                             :platform-arm ""
;	                             :option-settings
;	                             {"aws:elasticbeanstalk:application:environment"
;	                              {"ENVIRONMENT" "qa"
;	                               "TEST_VAR"    "test"}
;	                              "aws:autoscaling:launchconfiguration"
;	                              {"EC2KeyName"     "ec2_key_name"
;	                               "InstanceType"   "instance_type"
;	                               "SecurityGroups" "security_group_id"}}}]}}})
;
;(reduce
; (fn [options [namespace items]] (concat options (map #(apply vector namespace %) items)))
; []
; {"aws:elasticbeanstalk:application:environment"
;  {"ENVIRONMENT" "qa"
;   "TEST_VAR"    "test"}
;  "aws:autoscaling:launchconfiguration"
;  {"EC2KeyName"     "ec2_key_name"
;   "InstanceType"   "instance_type"
;   "SecurityGroups" "security_group_id"}})

(def ^:private credentials-example
	"(def lein-beanstalk-credentials {:access-key \"XXX\" :secret-key \"YYY\"})")

(defn- find-one [pred coll]
	(first (filter pred coll)))

(defn- poll-until
	"Poll a function until its value matches a predicate."
	([pred poll]
	 (poll-until pred poll 3000))
	([pred poll & [delay]]
	 (loop []
		 (Thread/sleep delay)
		 (print ".")
		 (.flush *out*)
		 (let [value (poll)]
			 (if (pred value) value (recur))))))

(defn- find-credentials
	[project]
	(let [init-map (resolve 'user/lein-beanstalk-credentials)
	      creds    (and init-map @init-map)]
		((juxt :access-key :secret-key) (or creds (:aws project)))))

(defn- get-credentials [project]
	(let [[access-key secret-key] (find-credentials project)]
		(if access-key
			(BasicAWSCredentials. access-key secret-key)
			(throw
				(IllegalStateException.
					(str "No credentials found; please add to ~/.lein/init.clj: "
					     credentials-example))))))

(defn- get-description [project]
	(:description project))

(defn- get-stack-name [project]
	(or (-> project :aws :beanstalk :stack-name)
	    "64bit Amazon Linux 2015.09 v2.0.4 running Tomcat 8 Java 8"))

(defn- v4? [project]
	(-> project :aws :beanstalk (:v4 false)))

(defn- get-app-name [project]
	(or (-> project :aws :beanstalk :app-name)
	    (:name project)))

(defn- get-app-version [project]
	(str (get-app-name project) "-" current-timestamp))

(defn- get-bucket-name [project]
	(or (-> project :aws :beanstalk :s3-bucket)
	    (str "lein-aws-beanstalk." (get-app-name project))))

(defn- get-region-name [project]
	(-> project :aws :beanstalk :region))

(defn- get-beanstalk-endpoint [project]
	(-> project :aws :beanstalk :beanstalk-endpoint))

(defn- get-s3-endpoint [project]
	(let [endpoint (-> project :aws :beanstalk :s3-endpoint)
	      region   (get-region-name project)]
		(if (v4? project)
			[(first endpoint) (RegionUtils/getRegion region)]
			[(first endpoint) (Region/valueOf (last endpoint))])))

(defn- default-env-vars [project]
	(let [[access-key secret-key] (find-credentials project)]
		{"AWS_ACCESS_KEY_ID" access-key "AWS_SECRET_KEY" secret-key}))

(defn env-var-options [project options]
	(for [[key value] (merge (default-env-vars project)
	                         (:env options))]
		(ConfigurationOptionSetting.
			"aws:elasticbeanstalk:application:environment"
			(if (keyword? key)
				(-> key name str/upper-case (str/replace "-" "_"))
				key)
			value)))

(defn- generate-secret-key
	([] (.generateKey (KeyGenerator/getInstance "AES")))
	([instance] (.generateKey (KeyGenerator/getInstance instance))))

(defn- generate-keypair
	([] (.generateKeyPair (KeyPairGenerator/getInstance "RSA")))
	([instance] (.generateKeyPair (KeyPairGenerator/getInstance instance))))

(defn- create-s3-client [project]
	(let [[endpoint region] (get-s3-endpoint project)
	      credentials       (get-credentials project)
	      keypair           (generate-keypair)
	      client            (if (v4? project)
		                        (AmazonS3EncryptionClient. credentials
		                                                   (StaticEncryptionMaterialsProvider. (EncryptionMaterials. keypair))
		                                                   (CryptoConfiguration. CryptoMode/AuthenticatedEncryption))
		                        (AmazonS3Client. (get-credentials project)))]
		(doto client
		      (.setRegion region)
		      (.setEndpoint endpoint))))

(defn- create-beanstalk-client [project]
	(let [endpoint    (get-beanstalk-endpoint project)
	      credentials (get-credentials project)]
		(doto (AWSElasticBeanstalkClient. credentials)
		      (.setEndpoint endpoint))))

(defn- create-bucket [client bucket]
	(when-not (.doesBucketExist client bucket)
		(.createBucket client bucket)))

(defn- update-environment-settings [project env options]
	(let [options          (env-var-options project env)
	      beanstalk-client (create-beanstalk-client project)]
		(.updateEnvironment beanstalk-client
		                    (doto (UpdateEnvironmentRequest.)
		                          (.setEnvironmentId (.getEnvironmentId env))
		                          (.setEnvironmentName (.getEnvironmentName env))
		                          (.setOptionSettings options)))))

(defn- update-environment-version [project env]
	(let [app-version      (get-app-version project)
	      env-id           (.getEnvironmentId env)
	      env-name         (.getEnvironmentName env)
	      beanstalk-client (create-beanstalk-client project)]
		(.updateEnvironment beanstalk-client
		                    (doto (UpdateEnvironmentRequest.)
		                          (.setEnvironmentId env-id)
		                          (.setEnvironmentName env-name)
		                          (.setVersionLabel app-version)))))

(defn- ready? [environment]
	(= (.getStatus environment) "Ready"))

(defn- terminated? [environment]
	(= (.getStatus environment) "Terminated"))

;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC FUNCTIONS ;;
;;;;;;;;;;;;;;;;;;;;;;

(defn quiet-logger
	"Stop the extremely verbose AWS logger from logging so many messages."
	[]
	(. (Logger/getLogger "com.amazonaws")
		(setLevel Level/WARNING)))

(defn s3-upload-file [project filepath]
	(let [[endpoint region] (get-s3-endpoint project)
	      bucket            (get-bucket-name project)
	      file              (io/file filepath)]
		(doto (create-s3-client project)
		      (create-bucket bucket)
		      (.putObject bucket (.getName file) file))
		(println "Uploaded" (.getName file) "to S3 Bucket")))

(defn create-app-version [project filename]
	(let [bucket           (get-bucket-name project)
	      app-name         (get-app-name project)
	      app-version      (get-app-version project)
	      description      (get-description project)
	      beanstalk-client (create-beanstalk-client project)]
		(.createApplicationVersion beanstalk-client
		                           (doto (CreateApplicationVersionRequest.)
		                                 (.setAutoCreateApplication true)
		                                 (.setApplicationName app-name)
		                                 (.setVersionLabel app-version)
		                                 (.setSourceBundle (S3Location. bucket filename))))
		(println "Created new app version" app-version)))

(defn delete-app-version [project version]
	(let [app-name         (get-app-name project)
	      beanstalk-client (create-beanstalk-client project)]
		(.deleteApplicationVersion beanstalk-client
		                           (doto (DeleteApplicationVersionRequest.)
		                                 (.setApplicationName app-name)
		                                 (.setVersionLabel version)
		                                 (.setDeleteSourceBundle true)))
		(println "Deleted app version" version)))

(defn get-application [project]
	(let [app-name (get-app-name project)]
		(->> (create-beanstalk-client project)
		     .describeApplications
		     .getApplications
		     (find-one #(= (.getApplicationName %) app-name)))))

(defn create-environment [project env]
	(let [env-name         (:name env)
	      prefix           (:cname-prefix env)
	      app-name         (get-app-name project)
	      app-version      (get-app-version project)
	      options          (env-var-options project env)
	      stack-name       (get-stack-name project)
	      beanstalk-client (create-beanstalk-client project)]
		(println (str "Creating '" env-name "' environment") "(this may take several minutes)")
		(.createEnvironment beanstalk-client
		                    (doto (CreateEnvironmentRequest.)
		                          (.setApplicationName app-name)
		                          (.setEnvironmentName env-name)
		                          (.setVersionLabel app-version)
		                          (.setOptionSettings options)
		                          (.setCNAMEPrefix prefix)
		                          (.setSolutionStackName stack-name)))))

(defn app-environments [project]
	(let [app-name         (get-app-name project)
	      beanstalk-client (create-beanstalk-client project)]
		(->> beanstalk-client
		     .describeEnvironments
		     .getEnvironments
		     (filter #(= (.getApplicationName %) app-name)))))

(defn get-env [project env-name]
	(->> (app-environments project)
	     (find-one #(= (.getEnvironmentName %) env-name))))

(defn get-running-env [project env-name]
	(->> (app-environments project)
	     (remove terminated?)
	     (find-one #(= (.getEnvironmentName %) env-name))))

(defn update-environment [project env {name :name :as options}]
	(println (str "Updating '" name "' environment") "(this may take several minutes)")
	(update-environment-settings project env options)
	(poll-until ready? #(get-env project name))
	(update-environment-version project env))

(defn deploy-environment [project {name :name :as options}]
	(if-let [env (get-running-env project name)]
		(update-environment project env options)
		(create-environment project options))
	(let [env (poll-until ready? #(get-env project name))]
		(println " Done")
		(println "Environment deployed at:" (.getCNAME env))))

(defn terminate-environment [project env-name]
	(when-let [env (get-running-env project env-name)]
		(.terminateEnvironment (create-beanstalk-client project)
		                       (doto (TerminateEnvironmentRequest.)
		                             (.setEnvironmentId (.getEnvironmentId env))
		                             (.setEnvironmentName (.getEnvironmentName env))))
		(println (str "Terminating '" env-name "' environment") "(This may take several minutes)")
		(poll-until terminated? #(get-env project env-name))
		(println " Done")))