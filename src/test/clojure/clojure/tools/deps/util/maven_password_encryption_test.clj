(ns clojure.tools.deps.util.maven-password-encryption-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.tools.deps.util.maven :as mvn])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (org.apache.maven.settings Server Settings)
           (org.eclipse.aether RepositorySystemSession)
           (org.eclipse.aether.repository AuthenticationContext RemoteRepository)
           (org.sonatype.plexus.components.sec.dispatcher DefaultSecDispatcher)))

(def ^:private username "john")
(def ^:private encrypted-master-password
  "Result of execution of the following commands: mvn --encrypt-master-password 'M@$terP@ssw0rd'"
  "{HOu/V6aA4ZYHlJyJ4zi79p2JhgUe7+BV/agd+0G/Z30=}")
(def ^:private server-password "P@ssw0rd")
(def ^:private encrypted-server-password "{aZxMljQrXw8HxQrV2L+8m0LEP6gYP9OtCzeE80mNHhE=}")
(def ^:private server-id "some-server-id")
(def ^:private server-url "https://maven.server.com/")

(defn- exec [^String location func]
  (let [old-location (System/getProperty (DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION))]
    (if location
      (System/setProperty (DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION) location)
      (System/clearProperty (DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION)))
    (try
      (func)
      (finally
        (if old-location
          (System/setProperty (DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION) old-location)
          (System/clearProperty (DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION)))
        (when location
          (let [location-file (File. location)]
            (when (.exists location-file)
              (.delete location-file))))))))

(defn- get-creds-from-repo [^RemoteRepository repo]
  (let [auth-context (AuthenticationContext/forRepository (reify RepositorySystemSession) repo)
        auth (.getAuthentication repo)]
    (.fill auth auth-context "password" {})
    (.fill auth auth-context "username" {})
    {:username (.get auth-context "username")
     :password (.get auth-context "password")}))

(defn- create-server [password]
  (doto (Server.)
    (.setUsername username)
    (.setPassword password)
    (.setId server-id)))

(defn- create-settings-security-xml []
  (let [path (-> (Files/createTempFile "settings-security" "xml" (make-array FileAttribute 0))
                 (.toFile)
                 (.getAbsolutePath))]
    (spit path (str/join "\n" ["<settingsSecurity>"
                               (str "<master>" encrypted-master-password "</master>")
                               "</settingsSecurity>"]))
    path))

(deftest test-handling-server-password-when-create-remote-repo
  (testing "Should decrypt server password when settings-security.xml exists."
    (is (= {:username username :password server-password}
           (exec (create-settings-security-xml)
                 #(let [server (create-server encrypted-server-password)
                        settings (doto (Settings.)
                                   (.addServer server))
                        repo (mvn/remote-repo [server-id {:url server-url}]
                                              settings)]
                    (get-creds-from-repo repo))))))
  (testing "Should keep not encrypted server password when settings-security.xml exists."
    (is (= {:username username :password server-password}
           (exec (create-settings-security-xml)
                 #(let [server (create-server server-password)
                        settings (doto (Settings.)
                                   (.addServer server))
                        repo (mvn/remote-repo [server-id {:url server-url}]
                                              settings)]
                    (get-creds-from-repo repo))))))
  (testing "Should keep not encrypted server password when settings-security.xml doesn't exist."
    (is (= {:username username :password server-password}
           (exec "/some/not/existing/path"
                 #(let [server (create-server server-password)
                        settings (doto (Settings.)
                                   (.addServer server))
                        repo (mvn/remote-repo [server-id {:url server-url}]
                                              settings)]
                    (get-creds-from-repo repo))))))
  (testing "Should keep encrypted server password when settings-security.xml doesn't exist"
    (is (= {:username username :password encrypted-server-password}
           (exec "/some/not/existing/path"
                 #(let [server (create-server encrypted-server-password)
                        settings (doto (Settings.)
                                   (.addServer server))
                        repo (mvn/remote-repo [server-id {:url server-url}]
                                              settings)]
                    (get-creds-from-repo repo)))))))
