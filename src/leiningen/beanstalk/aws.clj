(ns leiningen.beanstalk.aws
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

;(def ^:private project-exapmle
;	{:description "The misty woods of Kekistan"
;	 :aws         {:beanstalk {:app-name    "test"
;	                           :bucket      "zomboura.test"
;	                           :region      "eu-central-1"
;	                           :region-name "EU_Frankfurt"
;	                           :endpoints   {:s3 "s3.eu-central-1.amazonaws.com"
;	                                         :eb "elasticbeanstalk.eu-central-1.amazonaws.com"}
;	                           :v4          true
;	                           :environments
;	                           [{:name                "quality-assurance"
;	                             :cname-prefix        "test-qa"
;	                             :platform-arm        ""
;	                             :solution-stack-name "64bit Amazon Linux 2015.09 v2.0.4 running Tomcat 8 Java 8"
;	                             :option-settings     {}}
;	                            {:name                "production"
;	                             :cname-prefix        "test-prod"
;	                             :platform-arm        ""
;	                             :solution-stack-name "64bit Amazon Linux 2015.09 v2.0.4 running Tomcat 8 Java 8"
;	                             :option-settings     {}}]}}})

(defonce ^:private current-timestamp (.format (SimpleDateFormat. "yyyyMMddHHmmss") (Date.)))

(defn quiet-logger
	"Stop the extremely verbose AWS logger from logging so many messages."
	[]
	(. (Logger/getLogger "com.amazonaws")
		(setLevel Level/WARNING)))

(defn poll-until
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

(defn- generate-secret-key
	([] (.generateKey (KeyGenerator/getInstance "AES")))
	([instance] (.generateKey (KeyGenerator/getInstance instance))))

(defn- generate-keypair
	([] (.generateKeyPair (KeyPairGenerator/getInstance "RSA")))
	([instance] (.generateKeyPair (KeyPairGenerator/getInstance instance))))

; Create bucket

(defn- create-bucket [client bucket]
	(when-not (.doesBucketExist client bucket)
		(.createBucket client bucket)))

; Dispatchers

(defn- is-new-region?
	[{{{:keys [v4] :or {v4 false}} :beanstalk} :aws}]
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

(def ^:private define-app-version* #(assoc-in % [:aws :beanstalk :app-version] (define-app-version %)))

; Region

(defmulti ^:private define-region #'is-new-region?)

(defmethod define-region :true [{{{:keys [region]} :beanstalk} :aws}] (RegionUtils/getRegion region))

(defmethod define-region :false [{{{:keys [region-name]} :beanstalk} :aws}] (Region/valueOf region-name))

(def ^:private define-region* #(assoc-in % [:aws :beanstalk :region] (define-region %)))
;(define-region* project-exapmle)

; Currenct environment

(defn- define-currenct-environment [project env-name]
	(-> project :aws :beanstalk :environments (keyword env-name)))

(def ^:private define-currenct-environment*
	#(assoc-in %1 [:aws :beanstalk :environment] (define-currenct-environment %1 %2)))

; Options settings

(defn- define-option-settings [project]
	(->> (-> project :aws :beanstalk :environment :option-settings)
	     (reduce
	      (fn [options [namespace items]]
		      (concat options (map #(apply vector namespace %) items))))
	     (map
	      (fn [[namespace option-name value]]
		      (ConfigurationOptionSetting. namespace option-name value)))))

(def ^:private define-option-settings*
	#(assoc-in %1 [:aws :beanstalk :environment :option-settings (define-currenct-environment %1)]))

; S3 client

(defn- create-s3-client [credentials region endpoint]
	(doto (AmazonS3Client. credentials)
	      (.setRegion region)
	      (.setEndpoint endpoint)))

(defn- create-s3-encrypt-client [credentials region endpoint]
	(doto
	 (AmazonS3EncryptionClient. credentials
	                            (StaticEncryptionMaterialsProvider. (EncryptionMaterials. (generate-keypair)))
	                            (CryptoConfiguration. CryptoMode/AuthenticatedEncryption))
	 (.setRegion region)
	 (.setEndpoint endpoint)))

(defmulti ^:private create-s3-client* #'is-new-region?)

(defmethod create-s3-client* :true
	[{{{{:keys [s3]} :endpoints credentials :credentials region :region} :beanstalk} :aws :as project}]
	(assoc-in project [:aws :s3 :client] (create-s3-encrypt-client credentials region s3)))

(defmethod create-s3-client* :false
	[{{{{:keys [s3]} :endpoints credentials :credentials region :region} :beanstalk} :aws :as project}]
	(assoc-in project [:aws :s3 :client] (create-s3-client credentials region s3)))

; EB client

(defn- create-eb-client [credentials endpoint]
	(doto (AWSElasticBeanstalkClient. credentials)
	      (.setEndpoint endpoint)))

(defn- create-eb-client*
	[{{{{:keys [eb]} :endpoints credentials  :credentials} :beanstalk} :aws :as project}]
	(assoc-in project [:aws :beanstalk :client] (create-s3-client credentials eb)))

;=======================================================================

(defn- update-environment-settings*
	[{{{{:keys [option-settings]} :environment client :client} :beanstalk} :aws} env]
	(.updateEnvironment client
	                    (doto (UpdateEnvironmentRequest.)
	                          (.setEnvironmentId (.getEnvironmentId env))
	                          (.setEnvironmentName (.getEnvironmentName env))
	                          (.setOptionSettings option-settings))))

(defn- update-environment-version*
	[{{{:keys [app-version client]} :beanstalk} :aws} env]
	(.updateEnvironment client
	                    (doto (UpdateEnvironmentRequest.)
	                          (.setEnvironmentId (.getEnvironmentId env))
	                          (.setEnvironmentName (.getEnvironmentName env))
	                          (.setVersionLabel app-version))))

(defn- s3-upload-file*
	[{{{:keys [bucket]} :beanstalk {:keys [client]} :s3} :aws} file]
	(doto client
	      (create-bucket bucket)
	      (.putObject bucket (.getName file) file)))

(defn- create-app-version*
	[{{{:keys [app-name app-version bucket client]} :beanstalk} :aws} filename]
	(.createApplicationVersion client
	                           (doto (CreateApplicationVersionRequest.)
	                                 (.setAutoCreateApplication true)
	                                 (.setApplicationName app-name)
	                                 (.setVersionLabel app-version)
	                                 (.setSourceBundle (S3Location. bucket filename)))))

(defn- delete-app-version*
	[{{{:keys [app-name client]} :beanstalk} :aws} version]
	(.deleteApplicationVersion client
	                           (doto (DeleteApplicationVersionRequest.)
	                                 (.setApplicationName app-name)
	                                 (.setVersionLabel version)
	                                 (.setDeleteSourceBundle true))))

(defn- get-application*
	[{{{:keys [app-name client]} :beanstalk} :aws}]
	(->> client
	     .describeApplications
	     .getApplications
	     (filter #(= app-name (.getApplicationName %)))
	     (first)))

(defn- create-environment*
	[{{{{:keys [name
	            cname-prefix
	            platform-arm
	            solution-stack-name
	            option-settings]} :environment
	    app-name                  :app-name
	    app-version               :app-version
	    client                    :client} :beanstalk} :aws}]
	(.createEnvironment client
	                    (doto (CreateEnvironmentRequest.)
	                          (.setApplicationName app-name)
	                          (.setEnvironmentName name)
	                          (.setVersionLabel app-version)
	                          (.setCNAMEPrefix cname-prefix)
	                          (.setPlatformArn platform-arm)
	                          (.setSolutionStackName solution-stack-name)
	                          (.setOptionSettings option-settings))))

(defn- app-environments*
	[{{{:keys [app-name client]} :beanstalk} :aws}]
	(->> client
	     .describeEnvironments
	     .getEnvironments
	     (filter #(= (.getApplicationName %) app-name))))

(defn- terminate-environment* [{{{:keys [client]} :beanstalk} :aws} env]
	(.terminateEnvironment client
	                       (doto (TerminateEnvironmentRequest.)
	                             (.setEnvironmentId (.getEnvironmentId env))
	                             (.setEnvironmentName (.getEnvironmentName env)))))

;=======================================================================

(defn ready? [environment]
	(= (.getStatus environment) "Ready"))

(defn terminated? [environment]
	(= (.getStatus environment) "Terminated"))

(defn update-environment-settings [project env]
	(update-environment-settings*
	 (-> project
	     create-credentials*
	     create-eb-client*
	     (define-currenct-environment* (.getEnvironmentName env))
	     define-option-settings*)
	 env))

(defn update-environment-version [project env]
	(update-environment-version*
	 (-> project
	     create-credentials*
	     define-app-version*
	     create-eb-client*)
	 env))

(defn s3-upload-file [project filepath]
	(let [file (io/file filepath)]
		(s3-upload-file*
		 (-> project
		     create-credentials*
		     define-region*
		     create-s3-client*)
		 file)
		(println "Uploaded" (.getName file) "to S3 Bucket")))

(defn create-app-version [project filename]
	(create-app-version*
	 (-> project
	     create-credentials*
	     define-app-version*
	     create-eb-client*)
	 filename)
	(println "Created new app version"))

(defn delete-app-version [project version]
	(delete-app-version*
	 (-> project
	     create-credentials*
	     create-eb-client*)
	 version)
	(println "Deleted app version" version))

(defn get-application [project]
	(get-application*
	 (-> project
	     create-credentials*
	     create-eb-client*)))

(defn app-environments [{{{:keys [app-name]} :beanstalk} :aws :as project}]
	(-> (app-environments* (-> project create-credentials* create-eb-client*))
	    (filter #(= app-name (.getApplicationName %)))
	    (first)))

(defn get-env [project env-name]
	(->> (app-environments project)
	     (filter #(= env-name (.getEnvironmentName %)))
	     (first)))

(defn get-running-env [project env-name]
	(->> (app-environments project)
	     (remove terminated?)
	     (filter #(= env-name (.getEnvironmentName %)))
	     (first)))

(defn create-environment [project env-name]
	(println (str "Creating '" env-name "' environment") "(this may take several minutes)")
	(create-environment*
	 (-> project
	     create-credentials*
	     define-app-version*
	     create-eb-client*
	     (define-currenct-environment* env-name)
	     define-option-settings*)))

(defn update-environment [project env]
	(println (str "Updating '" (.getEnvironmentName env) "' environment") "(this may take several minutes)")
	(update-environment-settings project env)
	(poll-until ready? #(get-env project name))
	(update-environment-version project env))

(defn deploy-environment [project env-name]
	(if-let [env (get-running-env project env-name)]
		(update-environment project env)
		(create-environment project env-name))
	(let [env (poll-until ready? #(get-env project name))]
		(println " Done")
		(println "Environment deployed at:" (.getCNAME env))))

(defn terminate-environment [project env-name]
	(when-let [env (get-running-env project env-name)]
		(terminate-environment*
		 (-> project
		     create-credentials*
		     create-eb-client*)
		 env)
		(println (str "Terminating '" env-name "' environment") "(This may take several minutes)")
		(poll-until terminated? #(get-env project env-name))
		(println " Done")))