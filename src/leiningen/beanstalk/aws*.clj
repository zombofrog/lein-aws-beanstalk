(ns leiningen.beanstalk.aws*
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

(def ^:private project-exapmle
	{:description "The misty woods of Kekistan"
	 :aws         {:beanstalk {:app-name    "test"
	                           :bucket      "zomboura.test"
	                           :region      "eu-central-1"
	                           :region-name "EU_Frankfurt"
	                           :endpoints   {:s3 "s3.eu-central-1.amazonaws.com"
	                                         :eb "elasticbeanstalk.eu-central-1.amazonaws.com"}
	                           :v4          true
	                           :environments
	                           [{:name            "quality-assurance"
	                             :cname-prefix    "test-qa"
	                             :platform-arm    ""
	                             :option-settings {}}
	                            {:name         "production"
	                             :cname-prefix "test-prod"
	                             :platform-arm ""
	                             :option-settings
	                             {"aws:elasticbeanstalk:application:environment"
	                              {"ENVIRONMENT" "qa"
	                               "TEST_VAR"    "test"}
	                              "aws:autoscaling:launchconfiguration"
	                              {"EC2KeyName"     "ec2_key_name"
	                               "InstanceType"   "instance_type"
	                               "SecurityGroups" "security_group_id"}}}]}}})

(defonce ^:private current-timestamp (.format (SimpleDateFormat. "yyyyMMddHHmmss") (Date.)))

; Other

(defn- ready? [environment]
	(= (.getStatus environment) "Ready"))

(defn- terminated? [environment]
	(= (.getStatus environment) "Terminated"))

(defn- generate-secret-key
	([] (.generateKey (KeyGenerator/getInstance "AES")))
	([instance] (.generateKey (KeyGenerator/getInstance instance))))

(defn- generate-keypair
	([] (.generateKeyPair (KeyPairGenerator/getInstance "RSA")))
	([instance] (.generateKeyPair (KeyPairGenerator/getInstance instance))))

; Dispatchers

(defn- is-new-region? [{{{:keys [v4] :or {v4 false}} :beanstalk} :aws}]
	(-> v4 str keyword))

; Credentials

(defn- find-credentials [project]
	(let [init-map (resolve 'user/lein-beanstalk-credentials)
	      creds    (and init-map @init-map)]
		((juxt :access-key :secret-key) (or creds (:aws project)))))

(defn- create-credentials [project]
	(let [[access-key secret-key] (find-credentials project)]
		(if (and access-key secret-key)
			(BasicAWSCredentials. access-key secret-key)
			(throw
				(IllegalStateException. "No credentials found; please add to ~/.lein/init.clj: (def lein-beanstalk-credentials {:access-key \"XXX\" :secret-key \"YYY\"})")))))

(def ^:private create-credentials* #(assoc-in % [:aws :credentials] (create-credentials %)))
;(create-credentials* project-exapmle)

; App version

(defn- define-app-version [{{{:keys [app-name]} :beanstalk} :aws}]
	(str app-name "-" current-timestamp))

(def define-app-version* ^:private #(assoc-in % [:aws :beanstalk :app-version] (define-app-version %)))

; Region

(defmulti ^:private define-region #'is-new-region?)

(defmethod define-region :true [{{{:keys [region]} :beanstalk} :aws}] (RegionUtils/getRegion region))

(defmethod define-region :false [{{{:keys [region-name]} :beanstalk} :aws}] (Region/valueOf region-name))

(def ^:private define-region* #(assoc-in % [:aws :beanstalk :region] (define-region %)))
;(define-region* project-exapmle)

; S3 client

(defmulti ^:private create-s3-client #'is-new-region?)

(defmethod create-s3-client :true
	[{{{{:keys [s3]} :endpoints credentials :credentials region :region} :beanstalk} :aws}]
	(doto
	 (AmazonS3EncryptionClient. credentials
	                            (StaticEncryptionMaterialsProvider. (EncryptionMaterials. (generate-keypair)))
	                            (CryptoConfiguration. CryptoMode/AuthenticatedEncryption))
	 (.setRegion region)
	 (.setEndpoint s3)))

(defmethod create-s3-client :false
	[{{{{:keys [s3]} :endpoints credentials :credentials region :region} :beanstalk} :aws}]
	(doto (AmazonS3Client. credentials)
	      (.setRegion region)
	      (.setEndpoint s3)))

(def ^:private create-s3-client #(-> % create-credentials* define-region* create-s3-client*))
;(create-s3-client project-exapmle)

; EB client

(defn- create-eb-client*
	[{{{{:keys [eb]} :endpoints credentials  :credentials} :beanstalk} :aws}]
	(doto (AWSElasticBeanstalkClient. credentials)
	      (.setEndpoint eb)))

(def ^:private create-eb-client #(-> % create-credentials* define-region* define-app-version* ))

; Bucket

(defn- create-bucket [client bucket]
	(when-not (.doesBucketExist client bucket)
		(.createBucket client bucket)))

;=======================================================================
;
;(defn- s3-upload-file
;	[{{{:keys [bucket]} :beanstalk} :aws :as project} filepath]
;	(let [file (io/file filepath)]
;		(doto client
;		      (create-bucket bucket)
;		      (.putObject bucket (.getName file) file))
;		(println "Uploaded" (.getName file) "to S3 Bucket")))