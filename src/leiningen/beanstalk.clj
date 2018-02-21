(ns leiningen.beanstalk
	(:require [leiningen.beanstalk.aws :as aws]
	          [clojure.string :as str]
	          [clojure.set :as set])
	(:use [leiningen.help :only (help-for)]
	      [leiningen.ring.war :only (war-file-path)]
	      [leiningen.ring.uberwar :only (uberwar)]))

(def ^:private app-info-indent "\n                  ")

(defn- last-versions-info [app]
	(str/join app-info-indent (take 5 (.getVersions app))))

(defn- deployed-envs-info [project]
	(str/join
	 app-info-indent
	 (for [env (aws/describe-app-envs project)]
		 (str (.getEnvironmentName env) " (" (.getStatus env) ")"))))

(defn- print-env [env]
	(println
	 (str "Environment ID:    " (.getEnvironmentId env) "\n"
	      "Application Name:  " (.getApplicationName env) "\n"
	      "Environment Name:  " (.getEnvironmentName env) "\n"
	      "Description:       " (.getDescription env) "\n"
	      "URL:               " (.getCNAME env) "\n"
	      "Load Balancer URL: " (.getEndpointURL env) "\n"
	      "Status:            " (.getStatus env) "\n"
	      "Health:            " (.getHealth env) "\n"
	      "Current Version:   " (.getVersionLabel env) "\n"
	      "Solution Stack:    " (.getSolutionStackName env) "\n"
	      "Created On:        " (.getDateCreated env) "\n"
	      "Updated On:        " (.getDateUpdated env))))

(defn deploy
	"Deploy the current project to Amazon Elastic Beanstalk."
	([project]
	 (println "Usage: lein beanstalk deploy <environment>"))
	([project env-name]
	 (let [filename (-> project :ring :uberwar-name)
	       path     (uberwar project filename)]
		 (aws/upload-file project path)
		 (aws/create-app-version project)
		 (aws/deploy-env project env-name))))

(defn terminate
	"Terminate the environment for the current project on Amazon Elastic Beanstalk."
	([project]
	 (println "Usage: lein beanstalk terminate <environment>"))
	([project env-name]
	 (aws/terminate-env project env-name)))

(defn app-info
	"Displays information about a Beanstalk application."
	[project]
	(if-let [app (aws/describe-app project)]
		(println
		 (str "Application Name: " (.getApplicationName app) "\n"
		      "Last 5 Versions:  " (last-versions-info app) "\n"
		      "Created On:       " (.getDateCreated app) "\n"
		      "Updated On:       " (.getDateUpdated app) "\n"
		      "Deployed Envs:    " (deployed-envs-info project)))
		(println
		 (str "Application '" (:name project) "' "
		      "not found on AWS Elastic Beanstalk"))))

(defn env-info
	"Displays information about a Beanstalk environment."
	([project]
	 (doseq [env (aws/describe-envs project)]
		 (print-env env)))
	([project env-name]
	 (if-let [env (aws/get-env project env-name)]
		 (print-env env)
		 (println (str "Environment '" env-name "' " "not found on AWS Elastic Beanstalk")))))

(defn info
	"Provides info for about project on Amazon Elastic Beanstalk."
	([project]
	 (app-info project))
	([project env-name]
	 (env-info project env-name)))

(defn clean
	"Cleans out old versions, except the ones currently deployed."
	[project]
	(let [all-versions      (set (.getVersions (aws/describe-app project)))
	      deployed-versions (set
	                         (map #(.getVersionLabel %)
	                              (aws/describe-app-envs project)))]
		(doseq [version (set/difference all-versions deployed-versions)]
			(print (str "Removing '" version "'"))
			(aws/delete-app-version project version)
			(print (str " -> done!\n")))))

(defn beanstalk
	"Manage Amazon's Elastic Beanstalk service."
	{:help-arglists '([clean deploy info terminate])
	 :subtasks [#'clean #'deploy #'info #'terminate]}
	([project]
	 (println (help-for "beanstalk")))
	([project subtask & args]
	 (aws/quiet-logger)
	 (case subtask
		 "clean"     (apply clean project args)
		 "deploy"    (apply deploy project args)
		 "info"      (apply info project args)
		 "terminate" (apply terminate project args)
		 (println (help-for "beanstalk")))))