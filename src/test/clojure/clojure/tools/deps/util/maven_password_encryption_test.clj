(ns clojure.tools.deps.util.maven-password-encryption-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.tools.deps.util.maven :as mvn])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (org.apache.maven.settings Proxy Server Settings)
           (org.eclipse.aether RepositorySystemSession)
           (org.eclipse.aether.repository Authentication AuthenticationContext RemoteRepository)
           (org.sonatype.plexus.components.sec.dispatcher DefaultSecDispatcher)))

(def ^:private encrypted-master-password
  "Result of execution of the following command: mvn --encrypt-master-password 'M@$terP@ssw0rd'"
  "{HOu/V6aA4ZYHlJyJ4zi79p2JhgUe7+BV/agd+0G/Z30=}")

(def ^:private server-id "some-server-id")
(def ^:private server-url "https://maven.server.com/")
(def ^:private server-username "john")
(def ^:private server-password "P@ssw0rd")
(def ^:private encrypted-server-password
  "Result of execution of the following command when [[encrypted-master-password]] is in `~/.m2/settings-security.xml`:
  mvn --encrypt-password 'P@ssw0rd'"
  "{aZxMljQrXw8HxQrV2L+8m0LEP6gYP9OtCzeE80mNHhE=}")

(def ^:private proxy-id "some-proxy-id")
(def ^:private proxy-username "jack")
(def ^:private proxy-password "Dr0wss@p")
(def ^:private encrypted-proxy-password
  "Result of execution of the following command when [[encrypted-master-password]] is in `~/.m2/settings-security.xml`:
  mvn --encrypt-password 'Dr0wss@p'"
  "{4WAO7orIfqgHMcRfv3ehHb3x2Pw/ayCazy12hMrJD8E=}")

(def ^:private non-existing-path "/some/not/existing/path")

(defn- change-location [location]
  (if location
    (System/setProperty (DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION) location)
    (System/clearProperty (DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION))))

(defn- exec [^String location func]
  (let [old-location (System/getProperty (DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION))]
    (try
      (change-location location)
      (func)
      (finally
        (change-location old-location)
        (when location
          (let [location-file (File. location)]
            (when (.exists location-file)
              (.delete location-file))))))))

(defn- authentication-to-creds [^RemoteRepository repo ^Authentication auth]
  (let [auth-context (AuthenticationContext/forRepository (reify RepositorySystemSession) repo)]
    (.fill auth auth-context "password" {})
    (.fill auth auth-context "username" {})
    {:username (.get auth-context "username")
     :password (.get auth-context "password")}))

(defn- get-creds-from-repo [^RemoteRepository repo]
  {:server (authentication-to-creds repo (.getAuthentication repo))
   :proxy  (authentication-to-creds repo (.getAuthentication (.getProxy repo)))})

(defn- create-server [password]
  (doto (Server.)
    (.setUsername server-username)
    (.setPassword password)
    (.setId server-id)))

(defn- create-proxy [password]
  (doto (Proxy.)
    (.setId proxy-id)
    (.setActive true)
    (.setUsername proxy-username)
    (.setPassword password)))

(defn- create-settings-security-xml []
  (let [path (-> (Files/createTempFile "settings-security" "xml" (make-array FileAttribute 0))
                 (.toFile)
                 (.getAbsolutePath))]
    (spit path (str/join "\n" ["<settingsSecurity>"
                               (str "<master>" encrypted-master-password "</master>")
                               "</settingsSecurity>"]))
    path))

(defn- create-repo-and-get-server-creds [server-password proxy-password]
  (let [server (create-server server-password)
        proxy (create-proxy proxy-password)
        settings (doto (Settings.)
                   (.addServer server)
                   (.addProxy proxy))
        repo (mvn/remote-repo [server-id {:url server-url}] settings)]
    (get-creds-from-repo repo)))

(deftest test-handling-server-password-when-create-remote-repo
  (testing "Should decrypt server and proxy passwords when settings-security.xml exists."
    (is (= {:server {:username server-username :password server-password}
            :proxy  {:username proxy-username :password proxy-password}}
           (exec (create-settings-security-xml) #(create-repo-and-get-server-creds encrypted-server-password
                                                                                   encrypted-proxy-password)))))
  (testing "Should keep not encrypted server and proxy passwords when settings-security.xml exists."
    (is (= {:server {:username server-username :password server-password}
            :proxy  {:username proxy-username :password proxy-password}}
           (exec (create-settings-security-xml) #(create-repo-and-get-server-creds server-password
                                                                                   proxy-password)))))
  (testing "Should keep not encrypted server and proxy passwords when settings-security.xml doesn't exist."
    (is (= {:server {:username server-username :password server-password}
            :proxy  {:username proxy-username :password proxy-password}}
           (exec non-existing-path #(create-repo-and-get-server-creds server-password
                                                                      proxy-password)))))
  (testing "Should keep encrypted server password when settings-security.xml doesn't exist"
    (is (= {:server {:username server-username :password encrypted-server-password}
            :proxy  {:username proxy-username :password encrypted-proxy-password}}
           (exec non-existing-path #(create-repo-and-get-server-creds encrypted-server-password
                                                                      encrypted-proxy-password))))))

