(ns hyperlight.http-proxy-test
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [byte-streams :as bs]
            [clojure.test :refer [deftest is]]
            [criterium.core :as criterium]
            [hyperlight.http-proxy :as http-proxy]
            [manifold.deferred :as d]))

(def server-port 8081)

(def proxy-port 3001)

(def proxy-url (str "http://localhost:" server-port))

(defn hello-world-handler
  [req]
  {:status 200
   :body "Hello, world!"})

(defn predicated-handler
  [predicate req]
  (predicate req))

(defn proxy-handler
  [req]
  (http-proxy/proxy-request (assoc req :url proxy-url)))

(defmacro with-server
  [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)
         (netty/wait-for-close server#)))))

(defmacro with-handler
  [handler & body]
  `(with-server (http/start-server ~handler {:port server-port})
     ~@body))

(defmacro with-proxy-handler
  [handler & body]
  `(with-server
     (http-proxy/start-server ~handler {:port proxy-port :raw-stream? false})
     ~@body))

(deftest test-proxy-response
  (with-handler hello-world-handler
    (with-proxy-handler proxy-handler
      (let [rsp @(http/get (str "http://localhost:" proxy-port))]
        (is (= "Hello, world!" (bs/to-string (:body rsp))))))))

(deftest test-create-handler
  (let [handler (http-proxy/create-handler
                  {:url proxy-url
                   :headers {"host" "example.com"}})]
    (with-handler
      (partial predicated-handler
        (fn [{{:strs [host]} :headers}]
          (is (= host "example.com"))
          {:status 200
           :body "Hello, world!"}))
      (with-proxy-handler handler
        (let [rsp @(http/get (str "http://localhost:" proxy-port))]
          (is (= "Hello, world!" (bs/to-string (:body rsp)))))))))

(deftest ^:benchmark test-throughput
  (with-handler hello-world-handler
    (with-proxy-handler proxy-handler
      (criterium/quick-bench
        (d/catch
          @(http/get (str "http://localhost:" proxy-port))
          (constantly nil))))))
