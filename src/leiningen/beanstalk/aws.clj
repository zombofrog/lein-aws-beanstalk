(ns leiningen.beanstalk.aws
	(:require
		[clojure.java.io :as io]
		[clojure.string :as str])
	(:import
		java.text.SimpleDateFormat
		[java.util.logging Logger Level]
		[java.util Date UUID]
		com.amazonaws.auth.AWSCredentials
		com.amazonaws.auth.BasicAWSCredentials
		com.amazonaws.auth.AWSStaticCredentialsProvider
		com.amazonaws.auth.profile.ProfileCredentialsProvider
		com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClientBuilder
		com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
		com.amazonaws.services.elasticbeanstalk.model.CreateApplicationVersionRequest
		com.amazonaws.services.elasticbeanstalk.model.CreateEnvironmentRequest
		com.amazonaws.services.elasticbeanstalk.model.DeleteApplicationVersionRequest
		com.amazonaws.services.elasticbeanstalk.model.UpdateEnvironmentRequest
		com.amazonaws.services.elasticbeanstalk.model.S3Location
		com.amazonaws.services.elasticbeanstalk.model.TerminateEnvironmentRequest
		com.amazonaws.services.s3.AmazonS3ClientBuilder))

; HELPERS

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

; CREATE S3 KEY

(defn- s3-key [version] (str version ".war"))

; CREATE S3 BUCKET IF NOT EXISTS

(defn- create-bucket [client bucket]
	(when-not (.doesBucketExist client bucket) (.createBucket client bucket)))

; GET AWS CREDENTIALS

(defn- credentials []
	(.getCredentials (ProfileCredentialsProvider. "default")))

(defn- credentials* [project]
	(assoc-in project [:aws :credentials] (credentials)))

; GENARATE APP VERSION

(defn- app-version [{{{:keys [app-name]} :beanstalk} :aws}]
	(str app-name "-" current-timestamp))

(defn- app-version* [project]
	(if-not (-> project :aws :beanstalk :app-version)
		(assoc-in project [:aws :beanstalk :app-version] (app-version project))
		project))

; DEFINE CURRENCT ENVIRONMENT

(defn- currenct-env [project env-name]
	(->> (-> project :aws :beanstalk :environments)
	     (filter #(= env-name (:name %)))
	     (first)))

(defn- currenct-env* [project env-name]
	(assoc-in project [:aws :beanstalk :environment] (currenct-env project env-name)))

; DEFINE OPTION SETTINGS

(defn- option-settings [project]
	(->> (-> project :aws :beanstalk :environment :option-settings)
	     (reduce
	      (fn [options [namespace items]]
		      (concat options (map #(apply vector namespace %) items)))
	      [])
	     (map
	      (fn [[namespace option-name value]]
		      (doto (ConfigurationOptionSetting.)
		            (.withNamespace namespace)
		            (.withOptionName option-name)
		            (.withValue value))))))

(defn- option-settings* [project]
	(assoc-in project [:aws :beanstalk :environment :option-settings] (option-settings project)))

; CREATE S3 CLIENT

(defn- create-s3-client [^AWSCredentials credentials ^String region]
	(.build
		(doto (AmazonS3ClientBuilder/standard)
		      (.withCredentials (AWSStaticCredentialsProvider. credentials))
		      (.withRegion region))))

(defn- create-s3-client* [{{{:keys [region]} :beanstalk credentials :credentials} :aws :as project}]
	(assoc-in project [:aws :s3 :client] (create-s3-client credentials region)))

; CREATE EB CLIENT

(defn- create-eb-client [^AWSCredentials credentials ^String region]
	(.build
		(doto (AWSElasticBeanstalkClientBuilder/standard)
		      (.withCredentials (AWSStaticCredentialsProvider. credentials))
		      (.withRegion region))))

(defn- create-eb-client* [{{{:keys [region]} :beanstalk credentials :credentials} :aws :as project}]
	(assoc-in project [:aws :beanstalk :client] (create-eb-client credentials region)))

; UPLOAD FILE TO S3 BUCKET

(defn- upload-file*
	[{{{:keys [bucket app-version]} :beanstalk {:keys [client]} :s3} :aws} file]
	(doto client
	      (create-bucket bucket)
	      (.putObject bucket (s3-key app-version) file)))

; CREATE APPLICATION VERSION

(defn- create-app-version*
	[{{{:keys [app-name app-version bucket client]} :beanstalk} :aws}]
	(.createApplicationVersion client
	                           (doto (CreateApplicationVersionRequest.)
	                                 (.withAutoCreateApplication true)
	                                 (.withProcess true)
	                                 (.withApplicationName app-name)
	                                 (.withVersionLabel app-version)
	                                 (.withSourceBundle (doto (S3Location.)
	                                                          (.withS3Bucket bucket)
	                                                          (.withS3Key (s3-key app-version)))))))

; DELETE APPLICATION VERSION

(defn- delete-app-version*
	[{{{:keys [app-name client]} :beanstalk} :aws} version]
	(.deleteApplicationVersion client
	                           (doto (DeleteApplicationVersionRequest.)
	                                 (.withApplicationName app-name)
	                                 (.withVersionLabel version)
	                                 (.withDeleteSourceBundle true)
	                                 (.withProcess true))))

; DESCRIBE APPLICATION

(defn- describe-app*
	[{{{:keys [app-name client]} :beanstalk} :aws}]
	(->> client
	     .describeApplications
	     .getApplications
	     (filter #(= app-name (.getApplicationName %)))
	     (first)))

; CREATE ENVIRONMENT

(defn- create-env*
	[{{{{:keys [name
	            description
	            cname-prefix
	            solution-stack-name
	            option-settings]} :environment
	    app-name                  :app-name
	    app-version               :app-version
	    client                    :client} :beanstalk} :aws}]
	(.createEnvironment client
	                    (doto (CreateEnvironmentRequest.)
	                          (.withApplicationName app-name)
	                          (.withEnvironmentName name)
	                          (.withDescription description)
	                          (.withVersionLabel app-version)
	                          (.withCNAMEPrefix cname-prefix)
	                          (.withSolutionStackName solution-stack-name)
	                          (.withOptionSettings option-settings))))

; UPDATE ENVIRONMENT OPTION SETTINGS

(defn- update-env-settings*
	[{{{{:keys [option-settings]} :environment client :client} :beanstalk} :aws} env]
	(.updateEnvironment client
	                    (doto (UpdateEnvironmentRequest.)
	                          (.withEnvironmentId (.getEnvironmentId env))
	                          (.withEnvironmentName (.getEnvironmentName env))
	                          (.withOptionSettings option-settings))))

; UPDATE ENVIRONMENT VERSION

(defn- update-env-version*
	[{{{:keys [app-version client]} :beanstalk} :aws} env]
	(.updateEnvironment client
	                    (doto (UpdateEnvironmentRequest.)
	                          (.withEnvironmentId (.getEnvironmentId env))
	                          (.withEnvironmentName (.getEnvironmentName env))
	                          (.withVersionLabel app-version))))

; TERMINATE ENVIRONMENT

(defn- terminate-env* [{{{:keys [client]} :beanstalk} :aws} env]
	(.terminateEnvironment client
	                       (doto (TerminateEnvironmentRequest.)
	                             (.withEnvironmentId (.getEnvironmentId env))
	                             (.withEnvironmentName (.getEnvironmentName env)))))

; DESCRIBE CLIENT ENVIRONMENTS

(defn- describe-envs*
	[{{{:keys [client]} :beanstalk} :aws}]
	(.getEnvironments (.describeEnvironments client)))

; DESCRIBE APPLICATION ENVIRONMENT

(defn- describe-app-envs*
	[{{{:keys [app-name]} :beanstalk} :aws :as project}]
	(filter #(= app-name (.getApplicationName %)) (describe-envs* project)))

; IS ENVIRONMENT READY

(defn- ready? [environment] (= (.getStatus environment) "Ready"))

; IS ENVIRONMENT TERMINATED

(defn- terminated? [environment] (= (.getStatus environment) "Terminated"))

;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC INTERFACE ;;
;;;;;;;;;;;;;;;;;;;;;;

(defn upload-file [project filepath]
	(-> project
	    credentials*
	    app-version*
	    create-s3-client*
	    (upload-file* (io/file filepath)))
	(println "Uploaded" filepath "to S3 Bucket"))

(defn create-app-version [project]
	(-> project
	    credentials*
	    app-version*
	    create-eb-client*
	    create-app-version*)
	(println "Created new app version:" (app-version project)))

(defn delete-app-version [project version]
	(-> project
	    credentials*
	    create-eb-client*
	    (delete-app-version* version))
	(println "Deleted app version:" version))

(defn describe-app [project]
	(-> project
	    credentials*
	    create-eb-client*
	    describe-app*))

(defn update-env-settings [project env]
	(-> project
	    credentials*
	    app-version*
	    create-eb-client*
	    (currenct-env* (.getEnvironmentName env))
	    option-settings*
	    (update-env-settings* env)))

(defn update-env-version [project env]
	(-> project
	    credentials*
	    app-version*
	    create-eb-client*
	    (update-env-version* env)))

(defn describe-envs [project]
	(-> project
	    credentials*
	    create-eb-client*
	    describe-envs*))

(defn describe-app-envs [project]
	(-> project
	    credentials*
	    create-eb-client*
	    describe-app-envs*))

(defn get-env [project env-name]
	(->> (describe-app-envs project)
	     (filter #(= env-name (.getEnvironmentName %)))
	     (first)))

(defn get-running-env [project env-name]
	(->> (describe-app-envs project)
	     (remove terminated?)
	     (filter #(= env-name (.getEnvironmentName %)))
	     (first)))

(defn create-env [project env-name]
	(println (str "Creating '" env-name "' environment") "(this may take several minutes)")
	(-> project
	    credentials*
	    app-version*
	    create-eb-client*
	    (currenct-env* env-name)
	    option-settings*
	    create-env*))

(defn update-env [project env]
	(println (str "Updating '" (.getEnvironmentName env) "' environment") "(this may take several minutes)")
	(update-env-settings project env)
	(poll-until ready? #(get-env project (.getEnvironmentName env)))
	(update-env-version project env))

(defn deploy-env [project env-name]
	(if-let [env (get-running-env project env-name)]
		(update-env project env)
		(create-env project env-name))
	(let [env (poll-until ready? #(get-env project env-name))]
		(println " Done")
		(println "Environment deployed at:" (.getCNAME env))))

(defn terminate-env [project env-name]
	(when-let [env (get-running-env project env-name)]
		(-> project
		    credentials*
		    create-eb-client*
		    (terminate-env* env))
		(println (str "Terminating '" env-name "' environment") "(This may take several minutes)")
		(poll-until terminated? #(get-env project env-name))
		(println " Done")))