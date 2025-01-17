;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns waiter.handler-test
  (:require [clj-time.core :as t]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.walk :as walk]
            [full.async :as fa]
            [plumbing.core :as pc]
            [waiter.authorization :as authz]
            [waiter.core :as core]
            [waiter.handler :refer :all]
            [waiter.interstitial :as interstitial]
            [waiter.kv :as kv]
            [waiter.scheduler :as scheduler]
            [waiter.service-description :as sd]
            [waiter.statsd :as statsd]
            [waiter.status-codes :refer :all]
            [waiter.test-helpers :refer :all]
            [waiter.util.date-utils :as du]
            [waiter.util.utils :as utils])
  (:import (clojure.core.async.impl.channels ManyToManyChannel)
           (clojure.lang ExceptionInfo)
           (java.io StringBufferInputStream StringReader)
           (java.util.concurrent Executors)))

(deftest test-wrap-https-redirect
  (let [handler-response (Object.)
        execute-request (fn execute-request-fn [test-request]
                          (let [request-handler-argument-atom (atom nil)
                                test-request-handler (fn request-handler-fn [request]
                                                       (reset! request-handler-argument-atom request)
                                                       handler-response)
                                test-response ((wrap-https-redirect test-request-handler) test-request)]
                            {:handled-request @request-handler-argument-atom
                             :response test-response}))]

    (testing "no redirect"
      (testing "http request with token https-redirect set to false"
        (let [test-request {:headers {"host" "token.localtest.me"}
                            :scheme :http
                            :waiter-discovery {:passthrough-headers {}
                                               :service-description-template {}
                                               :token "token.localtest.me"
                                               :token-metadata {"https-redirect" false}
                                               :waiter-headers {}}}
              {:keys [handled-request response]} (execute-request test-request)]
          (is (= test-request handled-request))
          (is (= handler-response response))))

      (testing "http request with waiter header https-redirect set to false"
        (let [test-request {:headers {"host" "token.localtest.me"
                                      "x-waiter-https-redirect" "true"}
                            :request-method :get
                            :scheme :http
                            :waiter-discovery {:passthrough-headers {}
                                               :service-description-template {}
                                               :token "token.localtest.me"
                                               :token-metadata {"https-redirect" false}
                                               :waiter-headers {"x-waiter-https-redirect" true}}}
              {:keys [handled-request response]} (execute-request test-request)]
          (is (= test-request handled-request))
          (is (= handler-response response))))

      (testing "https request with token https-redirect set to false"
        (let [test-request {:headers {"host" "token.localtest.me"}
                            :scheme :https
                            :waiter-discovery {:passthrough-headers {}
                                               :service-description-template {}
                                               :token "token.localtest.me"
                                               :token-metadata {"https-redirect" false}
                                               :waiter-headers {}}}
              {:keys [handled-request response]} (execute-request test-request)]
          (is (= test-request handled-request))
          (is (= handler-response response))))

      (testing "https request with token https-redirect set to true"
        (let [test-request {:headers {"host" "token.localtest.me"}
                            :scheme :https
                            :waiter-discovery {:passthrough-headers {}
                                               :service-description-template {}
                                               :token "token.localtest.me"
                                               :token-metadata {"https-redirect" true}
                                               :waiter-headers {}}}
              {:keys [handled-request response]} (execute-request test-request)]
          (is (= test-request handled-request))
          (is (= handler-response response))))

      (testing "https request with waiter-header https-redirect set to false"
        (let [test-request {:headers {"host" "token.localtest.me"
                                      "x-waiter-https-redirect" "false"}
                            :scheme :https
                            :waiter-discovery {:passthrough-headers {}
                                               :service-description-template {"cpus" 1}
                                               :token "token.localtest.me"
                                               :token-metadata {"https-redirect" false}
                                               :waiter-headers {"x-waiter-https-redirect" false}}}
              {:keys [handled-request response]} (execute-request test-request)]
          (is (= test-request handled-request))
          (is (= handler-response response))))

      (testing "https request with waiter-header https-redirect set to true"
        (let [test-request {:headers {"host" "token.localtest.me"
                                      "x-waiter-https-redirect" "true"}
                            :scheme :https
                            :waiter-discovery {:passthrough-headers {}
                                               :service-description-template {"cpus" 1}
                                               :token "token.localtest.me"
                                               :token-metadata {"https-redirect" false}
                                               :waiter-headers {"x-waiter-https-redirect" true}}}
              {:keys [handled-request response]} (execute-request test-request)]
          (is (= test-request handled-request))
          (is (= handler-response response)))))

    (testing "https redirect"
      (testing "http request with token https-redirect set to true"
        (let [test-request {:headers {"host" "token.localtest.me:1234"}
                            :scheme :http
                            :waiter-discovery {:passthrough-headers {}
                                               :service-description-template {}
                                               :token "token.localtest.me"
                                               :token-metadata {"https-redirect" true}
                                               :waiter-headers {}}}
              {:keys [handled-request response]} (execute-request test-request)]
          (is (nil? handled-request))
          (is (= {:body ""
                  :headers {"Location" "https://token.localtest.me"}
                  :status http-307-temporary-redirect
                  :waiter/response-source :waiter}
                 response))))

      (testing "http request with waiter header https-redirect set to false"
        (let [test-request {:headers {"host" "token.localtest.me"
                                      "x-waiter-https-redirect" "false"}
                            :request-method :get
                            :scheme :http
                            :waiter-discovery {:passthrough-headers {}
                                               :service-description-template {}
                                               :token "token.localtest.me"
                                               :token-metadata {"https-redirect" true}
                                               :waiter-headers {"x-waiter-https-redirect" false}}}
              {:keys [handled-request response]} (execute-request test-request)]
          (is (nil? handled-request))
          (is (= {:body ""
                  :headers {"Location" "https://token.localtest.me"}
                  :status http-301-moved-permanently
                  :waiter/response-source :waiter}
                 response)))))))

(deftest test-wrap-https-redirect-acceptor
  (testing "returns 301 with proper url if ws and https-redirect is true and uri is nil"
    (let [handler (wrap-wss-redirect (fn [_] (is false "Not supposed to call this handler") true))
          upgrade-response (reified-upgrade-response)
          request {:headers {"host" "token.localtest.me"}
                   :scheme :ws
                   :upgrade-response upgrade-response
                   :waiter-discovery {:passthrough-headers {}
                                      :service-description-template {}
                                      :token "token.localtest.me"
                                      :token-metadata {"https-redirect" true}
                                      :waiter-headers {}}}
          response-status (handler request)]
      (is (= http-301-moved-permanently response-status))
      (is (= http-301-moved-permanently (.getStatusCode upgrade-response)))
      (is (= "https://token.localtest.me" (.getHeader upgrade-response "location")))
      (is (= "https-redirect is enabled" (.getStatusReason upgrade-response)))))

  (testing "returns 301 with proper url if ws and https-redirect is true and uri is set"
    (let [handler (wrap-wss-redirect (fn [_] (is false "Not supposed to call this handler") true))
          upgrade-response (reified-upgrade-response)
          request {:headers {"host" "token.localtest.me"}
                   :scheme :ws
                   :upgrade-response upgrade-response
                   :uri "/random/uri/path"
                   :waiter-discovery {:passthrough-headers {}
                                      :service-description-template {}
                                      :token "token.localtest.me"
                                      :token-metadata {"https-redirect" true}
                                      :waiter-headers {}}}
          response-status (handler request)]
      (is (= http-301-moved-permanently response-status))
      (is (= http-301-moved-permanently (.getStatusCode upgrade-response)))
      (is (= "https://token.localtest.me/random/uri/path" (.getHeader upgrade-response "location")))
      (is (= "https-redirect is enabled" (.getStatusReason upgrade-response)))))

  (testing "passes on to next handler if wss and https-redirect is true"
    (let [handler (wrap-wss-redirect (fn [_] true))
          request {:headers {"host" "token.localtest.me"}
                   :scheme :wss
                   :waiter-discovery {:passthrough-headers {}
                                      :service-description-template {}
                                      :token "token.localtest.me"
                                      :token-metadata {"https-redirect" true}
                                      :waiter-headers {}}}
          success? (handler request)]
      (is (true? success?))))

  (testing "passes on to next handler if https-redirect is false for wss request"
    (let [handler (wrap-wss-redirect (fn [_] true))
          upgrade-response (reified-upgrade-response)
          request {:headers {"host" "token.localtest.me"}
                   :scheme :wss
                   :upgrade-response upgrade-response
                   :uri "/random/uri/path"
                   :waiter-discovery {:passthrough-headers {}
                                      :service-description-template {}
                                      :token "token.localtest.me"
                                      :token-metadata {"https-redirect" false}
                                      :waiter-headers {}}}
          success? (handler request)]
      (is (true? success?))))

  (testing "passes on to next handler if https-redirect is false for ws request"
    (let [handler (wrap-wss-redirect (fn [_] true))
          upgrade-response (reified-upgrade-response)
          request {:headers {"host" "token.localtest.me"}
                   :scheme :ws
                   :upgrade-response upgrade-response
                   :uri "/random/uri/path"
                   :waiter-discovery {:passthrough-headers {}
                                      :service-description-template {}
                                      :token "token.localtest.me"
                                      :token-metadata {"https-redirect" false}
                                      :waiter-headers {}}}
          success? (handler request)]
      (is (true? success?)))))

(deftest test-complete-async-handler
  (testing "missing-request-id"
    (let [src-router-id "src-router-id"
          service-id "test-service-id"
          request {:basic-authentication {:src-router-id src-router-id}
                   :headers {"accept" "application/json"}
                   :route-params {:service-id service-id}
                   :uri (str "/waiter-async/complete//" service-id)}
          async-request-terminate-fn (fn [_] (throw (Exception. "unexpected call!")))
          {:keys [body headers status]} (complete-async-handler async-request-terminate-fn request)]
      (is (= http-400-bad-request status))
      (is (= expected-json-response-headers headers))
      (is (str/includes? body "No request-id specified"))))

  (testing "missing-service-id"
    (let [src-router-id "src-router-id"
          request-id "test-req-123456"
          request {:basic-authentication {:src-router-id src-router-id}
                   :headers {"accept" "application/json"}
                   :route-params {:request-id request-id}
                   :uri (str "/waiter-async/complete/" request-id "/")}
          async-request-terminate-fn (fn [_] (throw (Exception. "unexpected call!")))
          {:keys [body headers status]} (complete-async-handler async-request-terminate-fn request)]
      (is (= http-400-bad-request status))
      (is (= expected-json-response-headers headers))
      (is (str/includes? body "No service-id specified"))))

  (testing "valid-request-id"
    (let [src-router-id "src-router-id"
          service-id "test-service-id"
          request-id "test-req-123456"
          request {:basic-authentication {:src-router-id src-router-id}
                   :route-params {:request-id request-id, :service-id service-id}
                   :uri (str "/waiter-async/complete/" request-id "/" service-id)}
          async-request-terminate-fn (fn [in-request-id] (= request-id in-request-id))
          {:keys [body headers status]} (complete-async-handler async-request-terminate-fn request)]
      (is (= http-200-ok status))
      (is (= expected-json-response-headers headers))
      (is (= {:request-id request-id, :success true} (pc/keywordize-map (json/read-str body))))))

  (testing "unable-to-terminate-request"
    (let [src-router-id "src-router-id"
          service-id "test-service-id"
          request-id "test-req-123456"
          request {:basic-authentication {:src-router-id src-router-id}
                   :route-params {:request-id request-id, :service-id service-id}
                   :uri (str "/waiter-async/complete/" request-id "/" service-id)}
          async-request-terminate-fn (fn [_] false)
          {:keys [body headers status]} (complete-async-handler async-request-terminate-fn request)]
      (is (= http-200-ok status))
      (is (= expected-json-response-headers headers))
      (is (= {:request-id request-id, :success false} (pc/keywordize-map (json/read-str body)))))))

(deftest test-async-result-handler-errors
  (let [my-router-id "my-router-id"
        service-id "test-service-id"
        make-route-params (fn [code]
                            {:host "host"
                             :location (when (not= code "missing-location") "location/1234")
                             :port "port"
                             :request-id (when (not= code "missing-request-id") "req-1234")
                             :router-id (when (not= code "missing-router-id") my-router-id)
                             :service-id service-id})
        service-id->service-description-fn (fn [in-service-id]
                                             (is (= service-id in-service-id))
                                             {"backend-proto" "http"
                                              "metric-group" "test-metric-group"})]
    (testing "missing-location"
      (let [request {:headers {"accept" "application/json"}
                     :route-params (make-route-params "missing-location")}
            {:keys [body headers status]}
            (async/<!!
              (async-result-handler nil nil service-id->service-description-fn request))]
        (is (= http-400-bad-request status))
        (is (= expected-json-response-headers headers))
        (is (str/includes? body "Missing host, location, port, request-id, router-id or service-id in uri"))))

    (testing "missing-request-id"
      (let [request {:headers {"accept" "application/json"}
                     :route-params (make-route-params "missing-request-id")}
            {:keys [body headers status]}
            (async/<!!
              (async-result-handler nil nil service-id->service-description-fn request))]
        (is (= http-400-bad-request status))
        (is (= expected-json-response-headers headers))
        (is (str/includes? body "Missing host, location, port, request-id, router-id or service-id in uri"))))

    (testing "missing-router-id"
      (let [request {:headers {"accept" "application/json"}
                     :route-params (make-route-params "missing-router-id")}
            {:keys [body headers status]}
            (async/<!!
              (async-result-handler nil nil service-id->service-description-fn request))]
        (is (= http-400-bad-request status))
        (is (= expected-json-response-headers headers))
        (is (str/includes? body "Missing host, location, port, request-id, router-id or service-id in uri"))))

    (testing "error-in-checking-backend-status"
      (let [request {:authorization/pricipal "test-user@DOMAIN"
                     :authorization/user "test-user"
                     :headers {"accept" "application/json"}
                     :request-method :http-method
                     :route-params (make-route-params "local")}
            make-http-request-fn (fn [instance in-request end-route metric-group backend-proto]
                                   (is (= {:host "host" :port "port" :service-id service-id}
                                          (select-keys instance [:host :port :service-id])))
                                   (is (= request in-request))
                                   (is (= (-> request :route-params :location) end-route))
                                   (is (= "test-metric-group" metric-group))
                                   (is (= "http" backend-proto))
                                   (async/go {:error (ex-info "backend-status-error" {:status http-502-bad-gateway})}))
            async-trigger-terminate-fn (fn [in-router-id in-service-id in-request-id]
                                         (is (= my-router-id in-router-id))
                                         (is (= service-id in-service-id))
                                         (is (= "req-1234" in-request-id)))
            {:keys [body headers status]}
            (async/<!!
              (async-result-handler async-trigger-terminate-fn make-http-request-fn service-id->service-description-fn request))]
        (is (= http-502-bad-gateway status))
        (is (= expected-json-response-headers headers))
        (is (every? #(str/includes? body %) ["backend-status-error"]))))))

(deftest test-async-result-handler-with-return-codes
  (let [my-router-id "my-router-id"
        service-id "test-service-id"
        remote-router-id "remote-router-id"
        make-route-params (fn [code]
                            {:host "host"
                             :location (if (= code "local") "location/1234" "location/6789")
                             :port "port"
                             :request-id (if (= code "local") "req-1234" "req-6789")
                             :router-id (if (= code "local") my-router-id remote-router-id)
                             :service-id service-id})
        service-id->service-description-fn (fn [in-service-id]
                                             (is (= service-id in-service-id))
                                             {"backend-proto" "http"
                                              "metric-group" "test-metric-group"})
        request-id-fn (fn [router-type] (if (= router-type "local") "req-1234" "req-6789"))]
    (letfn [(execute-async-result-check
              [{:keys [request-method return-status router-type]}]
              (let [terminate-call-atom (atom false)
                    async-trigger-terminate-fn (fn [target-router-id in-service-id request-id]
                                                 (reset! terminate-call-atom true)
                                                 (is (= (if (= router-type "local") my-router-id remote-router-id) target-router-id))
                                                 (is (= service-id in-service-id))
                                                 (is (= (request-id-fn router-type) request-id)))
                    request {:authorization/principal "test-user@DOMAIN"
                             :authorization/user "test-user"
                             :headers {"accept" "application/json"}
                             :request-method request-method,
                             :route-params (make-route-params router-type)}
                    make-http-request-fn (fn [instance in-request end-route metric-group backend-proto]
                                           (is (= {:host "host" :port "port" :service-id service-id}
                                                  (select-keys instance [:host :port :service-id])))
                                           (is (= request in-request))
                                           (is (= (-> request :route-params :location) end-route))
                                           (is (= "test-metric-group" metric-group))
                                           (is (= "http" backend-proto))
                                           (async/go {:body "async-result-response", :headers {}, :status return-status}))
                    {:keys [status headers]}
                    (async/<!!
                      (async-result-handler async-trigger-terminate-fn make-http-request-fn service-id->service-description-fn request))]
                {:terminated @terminate-call-atom, :return-status status, :return-headers headers}))]
      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-result-check {:request-method :get, :return-status http-200-ok, :router-type "local"})))
      (is (= {:terminated true, :return-status http-303-see-other, :return-headers {}}
             (execute-async-result-check {:request-method :get, :return-status http-303-see-other, :router-type "local"})))
      (is (= {:terminated true, :return-status http-410-gone, :return-headers {}}
             (execute-async-result-check {:request-method :get, :return-status http-410-gone, :router-type "local"})))
      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-result-check {:request-method :get, :return-status http-200-ok, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-303-see-other, :return-headers {}}
             (execute-async-result-check {:request-method :get, :return-status http-303-see-other, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-410-gone, :return-headers {}}
             (execute-async-result-check {:request-method :get, :return-status http-410-gone, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-200-ok, :router-type "local"})))
      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-result-check {:request-method :post, :return-status http-200-ok, :router-type "local"})))
      (is (= {:terminated true, :return-status http-303-see-other, :return-headers {}}
             (execute-async-result-check {:request-method :post, :return-status http-303-see-other, :router-type "local"})))
      (is (= {:terminated true, :return-status http-410-gone, :return-headers {}}
             (execute-async-result-check {:request-method :post, :return-status http-410-gone, :router-type "local"})))
      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-result-check {:request-method :post, :return-status http-200-ok, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-303-see-other, :return-headers {}}
             (execute-async-result-check {:request-method :post, :return-status http-303-see-other, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-410-gone, :return-headers {}}
             (execute-async-result-check {:request-method :post, :return-status http-410-gone, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-200-ok, :router-type "local"})))
      (is (= {:terminated true, :return-status http-204-no-content, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-204-no-content, :router-type "local"})))
      (is (= {:terminated true, :return-status http-404-not-found, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-404-not-found, :router-type "local"})))
      (is (= {:terminated true, :return-status http-405-method-not-allowed, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-405-method-not-allowed, :router-type "local"})))
      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-200-ok, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-204-no-content, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-204-no-content, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-404-not-found, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-404-not-found, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-405-method-not-allowed, :return-headers {}}
             (execute-async-result-check {:request-method :delete, :return-status http-405-method-not-allowed, :router-type "remote"}))))))

(deftest test-async-status-handler-errors
  (let [my-router-id "my-router-id"
        service-id "test-service-id"
        make-route-params (fn [code]
                            {:host "host"
                             :location (when (not= code "missing-location") "location/1234")
                             :port "port"
                             :request-id (when (not= code "missing-request-id") "req-1234")
                             :router-id (when (not= code "missing-router-id") my-router-id)
                             :service-id service-id})
        service-id->service-description-fn (fn [in-service-id]
                                             (is (= service-id in-service-id))
                                             {"backend-proto" "http"
                                              "metric-group" "test-metric-group"})]
    (testing "missing-code"
      (let [request {:headers {"accept" "application/json"}
                     :query-string ""}
            {:keys [body headers status]} (async/<!! (async-status-handler nil nil service-id->service-description-fn request))]
        (is (= http-400-bad-request status))
        (is (= expected-json-response-headers headers))
        (is (str/includes? body "Missing host, location, port, request-id, router-id or service-id in uri"))))

    (testing "missing-location"
      (let [request {:headers {"accept" "application/json"}
                     :route-params (make-route-params "missing-location")}
            {:keys [body headers status]} (async/<!! (async-status-handler nil nil service-id->service-description-fn request))]
        (is (= http-400-bad-request status))
        (is (= expected-json-response-headers headers))
        (is (str/includes? body "Missing host, location, port, request-id, router-id or service-id in uri"))))

    (testing "missing-request-id"
      (let [request {:headers {"accept" "application/json"}
                     :route-params (make-route-params "missing-request-id")}
            {:keys [body headers status]} (async/<!! (async-status-handler nil nil service-id->service-description-fn request))]
        (is (= http-400-bad-request status))
        (is (= expected-json-response-headers headers))
        (is (str/includes? body "Missing host, location, port, request-id, router-id or service-id in uri"))))

    (testing "missing-router-id"
      (let [request {:headers {"accept" "application/json"}
                     :route-params (make-route-params "missing-router-id")}
            {:keys [body headers status]} (async/<!! (async-status-handler nil nil service-id->service-description-fn request))]
        (is (= http-400-bad-request status))
        (is (= expected-json-response-headers headers))
        (is (str/includes? body "Missing host, location, port, request-id, router-id or service-id in uri"))))

    (testing "error-in-checking-backend-status"
      (let [request {:authorization/principal "test-user@DOMAIN"
                     :authorization/user "test-user"
                     :headers {"accept" "application/json"}
                     :route-params (make-route-params "local")
                     :request-method :http-method}
            make-http-request-fn (fn [instance in-request end-route metric-group backend-proto]
                                   (is (= {:host "host" :port "port" :service-id service-id}
                                          (select-keys instance [:host :port :service-id])))
                                   (is (= request in-request))
                                   (is (= (-> request :route-params :location) end-route))
                                   (is (= "test-metric-group" metric-group))
                                   (is (= "http" backend-proto))
                                   (async/go {:error (ex-info "backend-status-error" {:status http-400-bad-request})}))
            async-trigger-terminate-fn nil
            {:keys [body headers status]} (async/<!! (async-status-handler async-trigger-terminate-fn make-http-request-fn service-id->service-description-fn request))]
        (is (= http-400-bad-request status))
        (is (= expected-json-response-headers headers))
        (is (every? #(str/includes? body %) ["backend-status-error"]))))))

(deftest test-async-status-handler-with-return-codes
  (let [my-router-id "my-router-id"
        service-id "test-service-id"
        remote-router-id "remote-router-id"
        make-route-params (fn [code]
                            {:host "host"
                             :location (if (= code "local") "query/location/1234" "query/location/6789")
                             :port "port"
                             :request-id (if (= code "local") "req-1234" "req-6789")
                             :router-id (if (= code "local") my-router-id remote-router-id)
                             :service-id service-id})
        service-id->service-description-fn (fn [in-service-id]
                                             (is (= service-id in-service-id))
                                             {"backend-proto" "http"
                                              "metric-group" "test-metric-group"})
        request-id-fn (fn [router-type] (if (= router-type "local") "req-1234" "req-6789"))
        result-location-fn (fn [router-type & {:keys [include-host-port] :or {include-host-port false}}]
                             (str (when include-host-port "http://www.example.com:8521")
                                  "/path/to/result-" (if (= router-type "local") "1234" "6789")))
        async-result-location (fn [router-type & {:keys [include-host-port location] :or {include-host-port false}}]
                                (if include-host-port
                                  (result-location-fn router-type :include-host-port include-host-port)
                                  (str "/waiter-async/result/" (request-id-fn router-type) "/"
                                       (if (= router-type "local") my-router-id remote-router-id) "/"
                                       service-id "/host/port"
                                       (or location (result-location-fn router-type :include-host-port false)))))]
    (letfn [(execute-async-status-check
              [{:keys [request-method result-location return-status router-type]}]
              (let [terminate-call-atom (atom false)
                    async-trigger-terminate-fn (fn [target-router-id in-service-id request-id]
                                                 (reset! terminate-call-atom true)
                                                 (is (= (if (= router-type "local") my-router-id remote-router-id) target-router-id))
                                                 (is (= service-id in-service-id))
                                                 (is (= (request-id-fn router-type) request-id)))
                    request {:authorization/principal "test-user@DOMAIN"
                             :authorization/user "test-user"
                             :request-method request-method
                             :route-params (make-route-params router-type)}
                    make-http-request-fn (fn [instance in-request end-route metric-group backend-proto]
                                           (is (= {:host "host" :port "port" :service-id service-id}
                                                  (select-keys instance [:host :port :service-id])))
                                           (is (= request in-request))
                                           (is (= (-> request :route-params :location) end-route))
                                           (is (= "test-metric-group" metric-group))
                                           (is (= "http" backend-proto))
                                           (async/go {:body "status-check-response"
                                                      :headers (if (= return-status http-303-see-other) {"location" (or result-location (result-location-fn router-type))} {})
                                                      :status return-status}))
                    {:keys [status headers]}
                    (async/<!!
                      (async-status-handler async-trigger-terminate-fn make-http-request-fn service-id->service-description-fn request))]
                {:terminated @terminate-call-atom, :return-status status, :return-headers headers}))]
      (is (= {:terminated false, :return-status http-200-ok, :return-headers {}}
             (execute-async-status-check {:request-method :get, :return-status http-200-ok, :router-type "local"})))
      (let [result-location (async-result-location "local" :include-host-port false)]
        (is (= {:terminated false, :return-status http-303-see-other, :return-headers {"location" result-location}}
               (execute-async-status-check {:request-method :get, :return-status http-303-see-other, :router-type "local"}))))
      (let [result-location (async-result-location "local" :include-host-port false :location "/query/location/another/path/to/result")]
        (is (= {:terminated false, :return-status http-303-see-other, :return-headers {"location" result-location}}
               (execute-async-status-check {:request-method :get, :result-location "another/path/to/result", :return-status http-303-see-other, :router-type "local"}))))
      (let [result-location (async-result-location "local" :include-host-port false :location "/query/location/another/path/to/result")]
        (is (= {:terminated false, :return-status http-303-see-other, :return-headers {"location" result-location}}
               (execute-async-status-check {:request-method :get, :result-location "./another/path/to/result", :return-status http-303-see-other, :router-type "local"}))))
      (let [result-location (async-result-location "local" :include-host-port false :location "/query/another/path/to/result")]
        (is (= {:terminated false, :return-status http-303-see-other, :return-headers {"location" result-location}}
               (execute-async-status-check {:request-method :get, :result-location "../another/path/to/result", :return-status http-303-see-other, :router-type "local"}))))
      (let [result-location (async-result-location "local" :include-host-port false :location "/another/path/to/result")]
        (is (= {:terminated false, :return-status http-303-see-other, :return-headers {"location" result-location}}
               (execute-async-status-check {:request-method :get, :result-location "../../another/path/to/result", :return-status http-303-see-other, :router-type "local"}))))
      (let [result-location (str "http://www.example.com:1234" (async-result-location "local" :include-host-port true))]
        (is (= {:terminated true, :return-status http-303-see-other, :return-headers {"location" result-location}}
               (execute-async-status-check {:request-method :get, :result-location result-location, :return-status http-303-see-other, :router-type "local"}))))
      (is (= {:terminated true, :return-status http-410-gone, :return-headers {}}
             (execute-async-status-check {:request-method :get, :return-status http-410-gone, :router-type "local"})))

      (is (= {:terminated false, :return-status http-200-ok, :return-headers {}}
             (execute-async-status-check {:request-method :get, :return-status http-200-ok, :router-type "remote"})))
      (let [result-location (async-result-location "remote" :include-host-port false)]
        (is (= {:terminated false, :return-status http-303-see-other, :return-headers {"location" result-location}}
               (execute-async-status-check {:request-method :get, :return-status http-303-see-other, :router-type "remote"}))))
      (let [result-location (str "http://www.example.com:1234" (async-result-location "remote" :include-host-port true))]
        (is (= {:terminated true, :return-status http-303-see-other, :return-headers {"location" result-location}}
               (execute-async-status-check {:request-method :get, :result-location result-location, :return-status http-303-see-other, :router-type "remote"}))))
      (is (= {:terminated true, :return-status http-410-gone, :return-headers {}}
             (execute-async-status-check {:request-method :get, :return-status http-410-gone, :router-type "remote"})))

      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-status-check {:request-method :delete, :return-status http-200-ok, :router-type "local"})))
      (is (= {:terminated true, :return-status http-204-no-content, :return-headers {}}
             (execute-async-status-check {:request-method :delete, :return-status http-204-no-content, :router-type "local"})))
      (is (= {:terminated false, :return-status http-404-not-found, :return-headers {}}
             (execute-async-status-check {:request-method :delete, :return-status http-404-not-found, :router-type "local"})))
      (is (= {:terminated false, :return-status http-405-method-not-allowed, :return-headers {}}
             (execute-async-status-check {:request-method :delete, :return-status http-405-method-not-allowed, :router-type "local"})))

      (is (= {:terminated true, :return-status http-200-ok, :return-headers {}}
             (execute-async-status-check {:request-method :delete, :return-status http-200-ok, :router-type "remote"})))
      (is (= {:terminated true, :return-status http-204-no-content, :return-headers {}}
             (execute-async-status-check {:request-method :delete, :return-status http-204-no-content, :router-type "remote"})))
      (is (= {:terminated false, :return-status http-404-not-found, :return-headers {}}
             (execute-async-status-check {:request-method :delete, :return-status http-404-not-found, :router-type "remote"})))
      (is (= {:terminated false, :return-status http-405-method-not-allowed, :return-headers {}}
             (execute-async-status-check {:request-method :delete, :return-status http-405-method-not-allowed, :router-type "remote"}))))))

(deftest test-list-services-handler
  (let [test-user "test-user"
        test-user-services #{"service1" "service2" "service3" "service7" "service8" "service9" "service10"}
        other-user-services #{"service4" "service5" "service6" "service11"}
        healthy-services #{"service1" "service2" "service4" "service6" "service7" "service8" "service9" "service10" "service11"}
        unhealthy-services #{"service2" "service3" "service5"}
        service-id->references {"service1" {:sources [{:token "t1.org" :version "v1"} {:token "t2.com" :version "v2"}]
                                            :type :token}
                                "service3" {:sources [{:token "t2.com" :version "v2"} {:token "t3.edu" :version "v3"}]
                                            :type :token}
                                "service4" {:sources [{:token "t1.org" :version "v1"} {:token "t2.com" :version "v2"}]
                                            :type :token}
                                "service5" {:sources [{:token "t1.org" :version "v1"} {:token "t3.edu" :version "v3"}]
                                            :type :token}
                                "service7" {:sources [{:token "t1.org" :version "v2"} {:token "t2.com" :version "v1"}]
                                            :type :token}
                                "service9" {:sources [{:token "t2.com" :version "v3"}]
                                            :type :token}}
        service-id->source-tokens {"service1" [{:token "t1.org" :version "v1"} {:token "t2.com" :version "v2"}]
                                   "service3" [{:token "t2.com" :version "v2"} {:token "t3.edu" :version "v3"}]
                                   "service4" [{:token "t1.org" :version "v1"} {:token "t2.com" :version "v2"}]
                                   "service5" [{:token "t1.org" :version "v1"} {:token "t3.edu" :version "v3"}]
                                   "service7" [{:token "t1.org" :version "v2"} {:token "t2.com" :version "v1"}]
                                   "service9" [{:token "t2.com" :version "v3"}]}
        all-services (set/union other-user-services test-user-services)
        current-time (t/now)
        query-state-fn (constantly {:all-available-service-ids all-services
                                    :service-id->healthy-instances (pc/map-from-keys
                                                                     (fn [service-id]
                                                                       (let [instance-id-1 (str service-id ".i1")]
                                                                         [{:flags []
                                                                           :healthy? true
                                                                           :host (str "127.0.0." (hash instance-id-1))
                                                                           :id instance-id-1
                                                                           :port (+ 1000 (rand 1000))
                                                                           :service-id service-id
                                                                           :started-at (du/date-to-str current-time)}]))
                                                                     healthy-services)
                                    :service-id->unhealthy-instances (pc/map-from-keys (constantly []) unhealthy-services)})
        query-autoscaler-state-fn (constantly
                                    (pc/map-from-keys
                                      (fn [_] {:scale-amount (rand-nth [-1 0 1])})
                                      test-user-services))
        request {:authorization/user test-user}
        instance-counts-present (fn [body]
                                  (let [parsed-body (-> body (str) (json/read-str) (walk/keywordize-keys))]
                                    (every? (fn [service-entry]
                                              (is (contains? service-entry :instance-counts))
                                              (is (every? #(contains? (get service-entry :instance-counts) %)
                                                          [:healthy-instances, :unhealthy-instances])))
                                            parsed-body)))
        prepend-waiter-url identity
        entitlement-manager (reify authz/EntitlementManager
                              (authorized? [_ user action {:keys [service-id]}]
                                (let [id (subs service-id (count "service"))]
                                  (and (str/includes? (str test-user id) user)
                                       (= action :manage)
                                       (some #(= % service-id) test-user-services)))))
        scheduler (reify scheduler/ServiceScheduler
                    (compute-instance-usage [_ service-id]
                      (let [id (subs service-id (count "service"))]
                        {:cpus (Integer/parseInt id)
                         :mem (* 10 (Integer/parseInt id))})))
        retrieve-token-based-fallback-fn (constantly nil)
        token->token-hash (constantly nil)
        list-services-handler (wrap-handler-json-response list-services-handler)
        assert-successful-json-response (fn [{:keys [body headers status]}]
                                          (is (= http-200-ok status))
                                          (is (= "application/json" (get headers "content-type")))
                                          (is (instance-counts-present body)))]
    (letfn [(service-id->metrics-fn []
              {})
            (service-id->references-fn [service-id]
              (get service-id->references service-id))
            (service-id->service-description-fn [service-id & _]
              (let [id (subs service-id (count "service"))]
                {"cpus" (Integer/parseInt id)
                 "env" {"E_ID" (str "id-" id)}
                 "mem" (* 10 (Integer/parseInt id))
                 "metadata" {"m-id" (str "id-" id)}
                 "metric-group" (str "mg" id)
                 "run-as-user" (if (contains? test-user-services service-id) (str test-user id) "another-user")}))
            (service-id->source-tokens-entries-fn [service-id]
              (when (contains? service-id->source-tokens service-id)
                (let [source-tokens (-> service-id service-id->source-tokens walk/stringify-keys)]
                  #{source-tokens})))]

      (testing "list-services-handler:success-regular-user"
        (let [{:keys [body] :as response}
              (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                     service-id->service-description-fn service-id->metrics-fn
                                     service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
          (assert-successful-json-response response)
          (is (= test-user-services (->> body json/read-str walk/keywordize-keys (map :service-id) set))))
        (let [request (assoc request :authorization/user "test-user1")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1" "service10"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-regular-user-with-filter-for-another-user"
        (let [request (assoc request :query-string "run-as-user=another-user")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= other-user-services (->> body json/read-str walk/keywordize-keys (map :service-id) set)))))
        (let [request (assoc request :authorization/user "test-user1" :query-string "run-as-user=test-user1.*")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1" "service10"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-regular-user-with-filter-for-another-user"
        (let [request (assoc request :query-string "run-as-user=another-user&run-as-user=another-user")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= other-user-services (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-regular-user-with-filter-for-another-user"
        (let [request (assoc request :query-string "run-as-user=another-user&run-as-user=test-user")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= other-user-services (->> body json/read-str walk/keywordize-keys (map :service-id) set)))))
        (let [request (assoc request :query-string "run-as-user=another-user&run-as-user=test-user.*")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= all-services (->> body json/read-str walk/keywordize-keys (map :service-id) set)))))
        (let [request (assoc request :query-string "run-as-user=another-user&run-as-user=test-user1")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= (conj other-user-services "service1")
                   (->> body json/read-str walk/keywordize-keys (map :service-id) set)))))
        (let [request (assoc request :query-string "run-as-user=another-user&run-as-user=test-user1.*")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= (conj other-user-services "service1" "service10")
                   (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-with-filter-for-cpus"
        (let [request (assoc request :query-string "cpus=1")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-with-filter-for-metric-group"
        (let [request (assoc request :query-string "metric-group=mg3")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service3"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-with-filter-for-multiple-metric-groups"
        (let [request (assoc request :query-string "metric-group=mg1&metric-group=mg2")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1" "service2"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-with-filter-for-single-env-variable"
        (let [request (assoc request :query-string "env.E_ID=id-1")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-with-filter-for-multiple-env-variables"
        (let [request (assoc request :query-string "env.E_ID=id-1&env.E_ID=id-2")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1" "service2"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-with-filter-for-single-metadata-variable"
        (let [request (assoc request :query-string "metadata.m-id=id-1")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-with-filter-for-multiple-metadata-variables"
        (let [request (assoc request :query-string "metadata.m-id=id-1&metadata.m-id=id-2")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1" "service2"} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-with-filter-for-multiple-nested-parameters"
        (let [request (assoc request :query-string "env.E_ID=id-1&metadata.m-id=id-1")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{"service1"} (->> body json/read-str walk/keywordize-keys (map :service-id) set)))))
        (let [request (assoc request :query-string "env.E_ID=id-1&metadata.m-id=id-2")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= #{} (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-regular-user-with-filter-for-same-user"
        (let [entitlement-manager (reify authz/EntitlementManager
                                    (authorized? [_ _ _ _]
                                      ; use (constantly true) for authorized? to verify that filter still applies
                                      true))
              request (assoc request :authorization/user "another-user" :query-string "run-as-user=another-user")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= other-user-services (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-regular-user-with-run-as-user-star-filter"
        (let [entitlement-manager (reify authz/EntitlementManager
                                    (authorized? [_ _ _ _]
                                      ; use (constantly true) for authorized? to verify that filter still applies
                                      true))
              request (assoc request :authorization/user "another-user" :query-string "run-as-user=.*")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= all-services (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-regular-user-with-run-as-user-prefix-star-filter"
        (let [entitlement-manager (reify authz/EntitlementManager
                                    (authorized? [_ _ _ _]
                                      ; use (constantly true) for authorized? to verify that filter still applies
                                      true))
              request (assoc request :authorization/user "another-user" :query-string "run-as-user=.*user.*")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= all-services (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-regular-user-with-run-as-user-suffix-star-filter"
        (let [entitlement-manager (reify authz/EntitlementManager
                                    (authorized? [_ _ _ _]
                                      ; use (constantly true) for authorized? to verify that filter still applies
                                      true))
              request (assoc request :authorization/user "another-user" :query-string "run-as-user=another.*")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= other-user-services (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-regular-user-with-different-run-as-user-star-filter"
        (let [entitlement-manager (reify authz/EntitlementManager
                                    (authorized? [_ _ _ _]
                                      ; use (constantly true) for authorized? to verify that filter still applies
                                      true))
              request (assoc request :authorization/user test-user :query-string "run-as-user=another.*")]
          (let [{:keys [body] :as response}
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= other-user-services (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:failure"
        (let [query-state-fn (constantly {:all-available-service-ids #{"service1"}
                                          :service-id->healthy-instances {"service1" []}})
              request {:authorization/user test-user}
              exception-message "Custom message from test case"
              prepend-waiter-url (fn [_] (throw (ex-info exception-message {:status http-400-bad-request})))
              list-services-handler (core/wrap-error-handling
                                      #(list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                                              service-id->service-description-fn service-id->metrics-fn
                                                              service-id->references-fn service-id->source-tokens-entries-fn token->token-hash %))
              {:keys [body headers status]} (list-services-handler request)]
          (is (= http-400-bad-request status))
          (is (= "text/plain" (get headers "content-type")))
          (is (str/includes? (str body) exception-message))))

      (testing "list-services-handler:success-super-user-sees-all-apps"
        (let [entitlement-manager (reify authz/EntitlementManager
                                    (authorized? [_ user action {:keys [service-id]}]
                                      (and (= user test-user)
                                           (= :manage action)
                                           (contains? all-services service-id))))
              {:keys [body] :as response}
              ; without a run-as-user, should return all apps
              (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                     service-id->service-description-fn service-id->metrics-fn
                                     service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
          (assert-successful-json-response response)
          (is (= all-services (->> body json/read-str walk/keywordize-keys (map :service-id) set)))))

      (testing "list-services-handler:success-filter-tokens"
        (doseq [[query-param filter-fn]
                {"t1.com" #(= % "t1.com")
                 "t2.org" #(= % "t2.org")
                 "tn.none" #(= % "tn.none")
                 ".*o.*" #(str/includes? % "o")
                 ".*t.*" #(str/includes? % "t")
                 "t.*" #(str/starts-with? % "t")
                 ".*com" #(str/ends-with? % "com")
                 ".*org" #(str/ends-with? % "org")}]
          (let [request (assoc request :query-string (str "token=" query-param))
                {:keys [body] :as response}
                ; without a run-as-user, should return all apps
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= (->> service-id->source-tokens
                        (filter (fn [[_ source-tokens]]
                                  (->> source-tokens (map :token) (some filter-fn))))
                        keys
                        set
                        (set/intersection test-user-services))
                   (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-filter-version"
        (doseq [[query-param filter-fn]
                {"v1" #(= % "v1")
                 "v2" #(= % "v2")
                 "vn" #(= % "vn")
                 ".*v.*" #(str/includes? % "v")
                 "v.*" #(str/starts-with? % "v")
                 ".*1" #(str/ends-with? % "1")
                 ".*2" #(str/ends-with? % "2")}]
          (let [request (assoc request :query-string (str "token-version=" query-param))
                {:keys [body] :as response}
                ; without a run-as-user, should return all apps
                (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                       service-id->service-description-fn service-id->metrics-fn
                                       service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
            (assert-successful-json-response response)
            (is (= (->> service-id->source-tokens
                        (filter (fn [[_ source-tokens]]
                                  (->> source-tokens (map :version) (some filter-fn))))
                        keys
                        set
                        (set/intersection test-user-services))
                   (->> body json/read-str walk/keywordize-keys (map :service-id) set))))))

      (testing "list-services-handler:success-filter-token-and-version"
        (let [request (assoc request :query-string "token=t1&token-version=v1")
              {:keys [body] :as response}
              ; without a run-as-user, should return all apps
              (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                     service-id->service-description-fn service-id->metrics-fn
                                     service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)]
          (assert-successful-json-response response)
          (is (= (->> service-id->source-tokens
                      (filter (fn [[_ source-tokens]]
                                (and (->> source-tokens (map :token) (some #(= % "t1")))
                                     (->> source-tokens (map :version) (some #(= % "v1"))))))
                      keys
                      set
                      (set/intersection test-user-services))
                 (->> body json/read-str walk/keywordize-keys (map :service-id) set)))))

      (testing "list-services-handler:include-healthy-instances"
        (let [request (assoc request :query-string "include=healthy-instances")
              {:keys [body] :as response}
              (list-services-handler entitlement-manager scheduler query-state-fn query-autoscaler-state-fn prepend-waiter-url retrieve-token-based-fallback-fn
                                     service-id->service-description-fn service-id->metrics-fn
                                     service-id->references-fn service-id->source-tokens-entries-fn token->token-hash request)
              service-id->healthy-instances (->> body
                                              (json/read-str)
                                              (walk/keywordize-keys)
                                              (pc/map-from-vals :service-id)
                                              (pc/map-vals #(get-in % [:instances :healthy-instances])))]
          (assert-successful-json-response response)
          (is (= test-user-services (->> body json/read-str walk/keywordize-keys (map :service-id) set)))
          (doseq [service-id test-user-services]
            (if (contains? healthy-services service-id)
              (let [healthy-instances (get-in (query-state-fn) [:service-id->healthy-instances service-id])]
                (is (= (map #(select-keys % [:host :id :port :started-at]) healthy-instances)
                       (get service-id->healthy-instances service-id))))
              (is (empty? (get service-id->healthy-instances service-id))))))))))

(deftest test-delete-service-handler
  (let [test-user "test-user"
        test-service-id "service-1"
        test-router-id "router-1"
        allowed-to-manage-service?-fn (fn [service-id user] (and (= test-service-id service-id) (= test-user user)))
        make-inter-router-requests-fn (constantly {})
        fallback-state-atom (atom nil)]
    (let [core-service-description {"run-as-user" test-user}
          scheduler-interactions-thread-pool (Executors/newFixedThreadPool 1)]

      (testing "delete-service-handler:success-regular-user"
        (let [scheduler (reify scheduler/ServiceScheduler
                          (delete-service [_ service-id]
                            (is (= test-service-id service-id))
                            {:result :deleted
                             :message "Worked!"}))
              request {:authorization/user test-user}
              {:keys [body headers status]}
              (async/<!!
                (delete-service-handler test-router-id test-service-id core-service-description scheduler allowed-to-manage-service?-fn
                                        scheduler-interactions-thread-pool make-inter-router-requests-fn fallback-state-atom request))]
          (is (= http-200-ok status))
          (is (= "application/json" (get headers "content-type")))
          (is (every? #(str/includes? (str body) (str %)) ["Worked!"]))))

      (testing "delete-service-handler:success-regular-user-deleting-for-another-user"
        (let [scheduler (reify scheduler/ServiceScheduler
                          (delete-service [_ service-id]
                            (is (= test-service-id service-id))
                            {:deploymentId "good"}))
              request {:authorization/user "another-user"}]
          (is (thrown-with-msg?
                ExceptionInfo #"User not allowed to delete service"
                (delete-service-handler test-router-id test-service-id core-service-description scheduler allowed-to-manage-service?-fn
                                        scheduler-interactions-thread-pool make-inter-router-requests-fn fallback-state-atom request)))))

      (.shutdown scheduler-interactions-thread-pool))))

(deftest test-service-await-handler
  (let [handler-name "test-service-await-handler"
        timeout 1000
        request {:route-params {:service-id "s1" :goal-state "deleted"}
                 :basic-authentication {:src-router-id "r2"}
                 :request-method :get
                 :query-string (str "timeout=" 1000)}
        assert-immediate-success
        (fn [goal-state fallback-state-atom request expected-service-exists? expected-service-healthy?]
          (let [request (assoc-in request [:route-params :goal-state] goal-state)
                {:keys [body headers status]} (async/<!! (service-await-handler fallback-state-atom request))
                parsed-body (json/read-str body)]
            (is (= http-200-ok status))
            (is (= "application/json" (get headers "content-type")))
            (is (= (get parsed-body "service-exists?") expected-service-exists?))
            (is (= (get parsed-body "service-healthy?") expected-service-healthy?))
            (is (get parsed-body "goal-success?"))))
        assert-force-timeout
        (fn [timeout goal-state fallback-state-atom request expected-service-exists? expected-service-healthy?]
          (let [request (assoc-in request [:route-params :goal-state] goal-state)
                start-time (System/currentTimeMillis)
                {:keys [body headers status]} (async/<!! (service-await-handler fallback-state-atom request))
                end-time (System/currentTimeMillis)
                parsed-body (json/read-str body)]
            (is (= http-200-ok status))
            (is (= "application/json" (get headers "content-type")))
            (is (= (get parsed-body "service-exists?") expected-service-exists?))
            (is (= (get parsed-body "service-healthy?") expected-service-healthy?))
            (is (not (get parsed-body "goal-success?")))
            (is (>= (- end-time start-time) timeout))))]

    (testing (str handler-name ":immediate-success-deleted")
      (let [fallback-state-atom (atom {:available-service-ids #{"s0"} :healthy-service-ids #{"s0"}})]
        (assert-immediate-success "deleted" fallback-state-atom request false false)))

    (testing (str handler-name ":immediate-success-healthy")
      (let [fallback-state-atom (atom {:available-service-ids #{"s1"} :healthy-service-ids #{"s1"}})]
        (assert-immediate-success "healthy" fallback-state-atom request true true)))

    (testing (str handler-name ":immediate-success-exist")
      (let [fallback-state-atom (atom {:available-service-ids #{"s1"} :healthy-service-ids #{}})]
        (assert-immediate-success "exist" fallback-state-atom request true false)))

    (testing (str handler-name ":force-timeout-deleted")
      (let [fallback-state-atom (atom {:available-service-ids #{"s1"} :healthy-service-ids #{"s1"}})]
        (assert-force-timeout timeout "deleted" fallback-state-atom request true true)))

    (testing (str handler-name ":force-timeout-healthy")
      (let [fallback-state-atom (atom {:available-service-ids #{"s1"} :healthy-service-ids #{}})]
        (assert-force-timeout timeout "healthy" fallback-state-atom request true false)))

    (testing (str handler-name ":force-timeout-exist")
      (let [fallback-state-atom (atom {:available-service-ids #{}} :healthy-service-ids #{})]
        (assert-force-timeout timeout "exist" fallback-state-atom request false false)))

    (testing (str handler-name ":success-with-large-sleep-duration")
      (let [fallback-state-atom (atom {:available-service-ids #{"s1"} :healthy-service-ids #{}})
            goal-fallback-state {:available-service-ids #{} :healthy-service-ids #{}}
            timeout 2000
            update-delay 1000
            request (assoc request :query-string (str "timeout=" timeout "&sleep-duration=" (* 10 timeout)))
            _ (async/go
                (async/<! (async/timeout update-delay))
                (reset! fallback-state-atom goal-fallback-state))
            start-time (System/currentTimeMillis)
            {:keys [body headers status]} (async/<!! (service-await-handler fallback-state-atom request))
            end-time (System/currentTimeMillis)
            parsed-body (json/read-str body)]
        (is (= http-200-ok status))
        (is (= "application/json" (get headers "content-type")))
        (is (= (get parsed-body "service-exists?") false))
        (is (= (get parsed-body "service-healthy?") false))
        (is (get parsed-body "goal-success?"))
        (is (<= update-delay (- timeout 50) (- end-time start-time) (+ timeout 50)))))

    (testing (str handler-name ":success-with-update")
      (let [fallback-state-atom (atom {:available-service-ids #{"s1"} :healthy-service-ids #{}})
            timeout 10000
            update-delay 2000
            request-query (assoc request :query-string (str "timeout=" timeout))
            _ (async/go
                (async/<! (async/timeout update-delay))
                (reset! fallback-state-atom {:available-service-ids #{} :healthy-service-ids #{}}))
            start-time (System/currentTimeMillis)
            {:keys [body headers status]} (async/<!! (service-await-handler fallback-state-atom request-query))
            end-time (System/currentTimeMillis)
            parsed-body (json/read-str body)]
        (is (= http-200-ok status))
        (is (= "application/json" (get headers "content-type")))
        (is (= (get parsed-body "service-exists?") false))
        (is (= (get parsed-body "service-healthy?") false))
        (is (get parsed-body "goal-success?"))
        (is (<= update-delay (- end-time start-time) timeout))))

    (testing (str handler-name ":non-integer-timeout-sleep-duration-params")
      (let [fallback-state-atom (atom {:available-service-ids #{}})
            timeout "Invalid timeout value"
            sleep-duration "Invalid sleep-duration value"
            request (assoc request :query-string (str "timeout=" timeout "&sleep-duration=" sleep-duration))
            {:keys [body headers status]} (async/<!! (service-await-handler fallback-state-atom request))]
        (is (= http-400-bad-request status))
        (is (= "text/plain" (get headers "content-type")))
        (is (re-find #"timeout and sleep-duration must be integers" body))
        (is (re-find (re-pattern (str ":timeout \"" timeout "\"")) body))
        (is (re-find (re-pattern (str ":sleep-duration \"" sleep-duration "\"")) body))))

    (testing (str handler-name ":timeout-required-param")
      (let [fallback-state-atom (atom {:available-service-ids #{}})
            request (assoc request :query-string "")
            {:keys [body headers status]} (async/<!! (service-await-handler fallback-state-atom request))]
        (is (= http-400-bad-request status))
        (is (= "text/plain" (get headers "content-type")))
        (is (re-find #"timeout is a required query parameter" body))))))

(deftest test-work-stealing-handler
  (let [test-service-id "test-service-id"
        test-router-id "router-1"
        instance-rpc-chan-factory (fn [response-status]
                                    (let [instance-rpc-chan (async/chan 1)
                                          work-stealing-chan (async/chan 1)]
                                      (async/go
                                        (let [instance-rpc-content (async/<! instance-rpc-chan)
                                              {:keys [method service-id response-chan]} instance-rpc-content]
                                          (is (= :offer method))
                                          (is (= test-service-id service-id))
                                          (async/>! response-chan work-stealing-chan)))
                                      (async/go
                                        (let [offer-params (async/<! work-stealing-chan)]
                                          (is (= test-service-id (:service-id offer-params)))
                                          (async/>!! (:response-chan offer-params) response-status)))
                                      instance-rpc-chan))
        test-cases [{:name "valid-parameters-rejected-response"
                     :request-body {:cid "cid-1"
                                    :instance {:id "instance-1", :service-id test-service-id}
                                    :request-id "request-1"
                                    :router-id test-router-id
                                    :service-id test-service-id}
                     :response-status :promptly-rejected
                     :expected-status http-200-ok
                     :expected-body-fragments ["cid" "request-id" "response-status" "promptly-rejected"
                                               test-service-id test-router-id]}
                    {:name "valid-parameters-success-response"
                     :request-body {:cid "cid-1"
                                    :instance {:id "instance-1", :service-id test-service-id}
                                    :request-id "request-1"
                                    :router-id test-router-id
                                    :service-id test-service-id}
                     :response-status :success
                     :expected-status http-200-ok
                     :expected-body-fragments ["cid" "request-id" "response-status" "success"
                                               test-service-id test-router-id]}
                    {:name "missing-cid"
                     :request-body {:instance {:id "instance-1", :service-id test-service-id}
                                    :request-id "request-1"
                                    :router-id test-router-id
                                    :service-id test-service-id}
                     :response-status :success
                     :expected-status http-400-bad-request
                     :expected-body-fragments ["Missing one of"]}
                    {:name "missing-instance"
                     :request-body {:cid "cid-1"
                                    :request-id "request-1"
                                    :router-id test-router-id
                                    :service-id test-service-id}
                     :response-status :success
                     :expected-status http-400-bad-request
                     :expected-body-fragments ["Missing one of"]}
                    {:name "missing-request-id"
                     :request-body {:cid "cid-1"
                                    :instance {:id "instance-1", :service-id test-service-id}
                                    :router-id test-router-id
                                    :service-id test-service-id}
                     :response-status :success
                     :expected-status http-400-bad-request
                     :expected-body-fragments ["Missing one of"]}
                    {:name "missing-router-id"
                     :request-body {:cid "cid-1"
                                    :instance {:id "instance-1", :service-id test-service-id}
                                    :request-id "request-1"
                                    :service-id test-service-id}
                     :response-status :success
                     :expected-status http-400-bad-request
                     :expected-body-fragments ["Missing one of"]}
                    {:name "missing-service-id"
                     :request-body {:cid "cid-1"
                                    :instance {:id "instance-1", :service-id test-service-id}
                                    :request-id "request-1"
                                    :router-id test-router-id}
                     :response-status :success
                     :expected-status http-400-bad-request
                     :expected-body-fragments ["Missing one of"]}]]
    (doseq [{:keys [name request-body response-status expected-status expected-body-fragments]} test-cases]
      (testing name
        (let [instance-rpc-chan (instance-rpc-chan-factory response-status)
              populate-maintainer-chan! (make-populate-maintainer-chan! instance-rpc-chan)
              request {:uri (str "/work-stealing")
                       :request-method :post
                       :body (StringBufferInputStream. (utils/clj->json (walk/stringify-keys request-body)))}
              {:keys [status body]} (fa/<?? (work-stealing-handler populate-maintainer-chan! request))]
          (is (= expected-status status))
          (is (every? #(str/includes? (str body) %) expected-body-fragments)))))))

(deftest test-work-stealing-handler-cannot-find-channel
  (let [instance-rpc-chan (async/chan)
        populate-maintainer-chan! (make-populate-maintainer-chan! instance-rpc-chan)
        test-service-id "test-service-id"
        request {:body (StringBufferInputStream.
                         (utils/clj->json
                           {"cid" "test-cid"
                            "instance" {"id" "test-instance-id", "service-id" test-service-id}
                            "request-id" "test-request-id"
                            "router-id" "test-router-id"
                            "service-id" test-service-id}))}
        response-chan (work-stealing-handler populate-maintainer-chan! request)]
    (async/thread
      (let [{:keys [cid method response-chan service-id]} (async/<!! instance-rpc-chan)]
        (is (= :offer method))
        (is (= test-service-id service-id))
        (is cid)
        (is (instance? ManyToManyChannel response-chan))
        (async/close! response-chan)))
    (let [{:keys [status]} (async/<!! response-chan)]
      (is (= http-404-not-found status)))))

(deftest test-work-stealing-handler-channel-put-failed
  (let [instance-rpc-chan (async/chan)
        populate-maintainer-chan! (make-populate-maintainer-chan! instance-rpc-chan)
        test-service-id "test-service-id"
        request {:body (StringBufferInputStream.
                         (utils/clj->json
                           {"cid" "test-cid"
                            "instance" {"id" "test-instance-id", "service-id" test-service-id}
                            "request-id" "test-request-id"
                            "router-id" "test-router-id"
                            "service-id" test-service-id}))}
        response-chan (work-stealing-handler populate-maintainer-chan! request)]
    (async/thread
      (let [{:keys [cid method response-chan service-id]} (async/<!! instance-rpc-chan)
            work-stealing-chan (async/chan)]
        (is (= :offer method))
        (is (= test-service-id service-id))
        (is cid)
        (is (instance? ManyToManyChannel response-chan))
        (async/close! work-stealing-chan)
        (async/put! response-chan work-stealing-chan)
        (async/close! response-chan)))
    (let [{:keys [status]} (async/<!! response-chan)]
      (is (= http-500-internal-server-error status)))))

(deftest test-get-router-state
  (let [state-atom (atom nil)
        query-state-fn (fn [] @state-atom)
        custom-components {:component-1 {}
                           :component-2 {}}
        test-fn (fn [router-id query-state-fn request]
                  (let [handler (wrap-handler-json-response get-router-state)]
                    (handler router-id query-state-fn custom-components request)))
        router-id "test-router-id"]

    (reset! state-atom {:state-data {}})

    (testing "Getting router state"
      (testing "should handle exceptions gracefully"
        (let [bad-request {:scheme 1} ;; integer scheme will throw error
              {:keys [status body]} (test-fn router-id query-state-fn bad-request)]
          (is (str/includes? (str body) "Internal error"))
          (is (= http-500-internal-server-error status))))

      (testing "display router state"
        (let [{:keys [status body]} (test-fn router-id query-state-fn {})]
          (is (every? #(str/includes? (str body) %1)
                      ["autoscaler" "autoscaling-multiplexer" "codahale-reporters"
                       "custom-components:component-1"  "custom-components:component-2"
                       "fallback" "interstitial" "kv-store" "leader" "local-usage"
                       "maintainer" "router-metrics" "scheduler" "statsd"])
              (str "Body did not include necessary JSON keys:\n" body))
          (is (every? #(str/includes? (str body) %1)
                      ["custom-components/component-1"  "custom-components/component-2"])
              (str "Body did not include custom component paths:\n" body))
          (is (= http-200-ok status)))))))

(deftest test-get-query-fn-state
  (let [router-id "test-router-id"
        test-fn (wrap-handler-json-response get-query-fn-state)]
    (testing "successful response"
      (let [state {"autoscaler" "state"}
            query-state-fn (constantly state)
            {:keys [body status]} (test-fn router-id query-state-fn {})]
        (is (= http-200-ok status))
        (is (= (json/read-str body) {"router-id" router-id, "state" state}))))

    (testing "exception response"
      (let [query-state-fn (fn [] (throw (Exception. "from test")))
            {:keys [body status]} (test-fn router-id query-state-fn {})]
        (is (= http-500-internal-server-error status))
        (is (str/includes? body "Waiter Error 500"))))))

(deftest test-get-custom-component-state
  (let [router-id "test-router-id"
        custom-components {:component-1 {:query-state (fn [include-flags]
                                                        {:include-flags include-flags
                                                         :name "component-1"})}
                           :component-2 {}
                           :component-3 (Object.)
                           :component-4 {:query-state (fn [] {:name "component-4"})}}
        test-fn (wrap-handler-json-response get-custom-component-state)]
    (testing "successful response"
      (let [state {"include-flags" [] "name" "component-1"}
            request {:route-params {:component-name "component-1"}}
            {:keys [body status]} (test-fn router-id custom-components request)]
        (is (= http-200-ok status))
        (is (= {"router-id" router-id, "state" state} (json/read-str body)))))

    (testing "exception response"
      (let [state {"component-name" "component-2" "message" "State unavailable"}
            request {:route-params {:component-name "component-2"}}
            {:keys [body status]} (test-fn router-id custom-components request)]
        (is (= http-200-ok status))
        (is (= {"router-id" router-id, "state" state} (json/read-str body))))

      (let [state {"component-name" "component-3" "message" "State unavailable"}
            request {:route-params {:component-name "component-3"}}
            {:keys [body status]} (test-fn router-id custom-components request)]
        (is (= http-200-ok status))
        (is (= {"router-id" router-id, "state" state} (json/read-str body))))

      (let [request {:route-params {:component-name "component-4"}}
            {:keys [body status]} (test-fn router-id custom-components request)]
        (is (= http-200-ok status))
        (is (str/includes? (str body) "Wrong number of args"))))))

(deftest test-get-kv-store-state
  (let [router-id "test-router-id"
        include-flags #{}
        test-fn (wrap-handler-json-response get-kv-store-state)]
    (testing "successful response"
      (let [kv-store (kv/new-local-kv-store {})
            state (walk/stringify-keys (kv/state kv-store include-flags))
            {:keys [body status]} (test-fn router-id kv-store {})]
        (is (= http-200-ok status))
        (is (= (json/read-str body) {"router-id" router-id, "state" state}))))

    (testing "exception response"
      (let [kv-store (Object.)
            {:keys [body status]} (test-fn router-id kv-store {})]
        (is (= http-500-internal-server-error status))
        (is (str/includes? body "Waiter Error 500"))))))

(deftest test-get-local-usage-state
  (let [router-id "test-router-id"
        test-fn (wrap-handler-json-response get-local-usage-state)]
    (testing "successful response"
      (let [last-request-time-state {"foo" 1234, "bar" 7890}
            last-request-time-agent (agent last-request-time-state)
            {:keys [body status]} (test-fn router-id last-request-time-agent {})]
        (is (= http-200-ok status))
        (is (= (json/read-str body) {"router-id" router-id, "state" last-request-time-state}))))

    (testing "exception response"
      (let [handler (core/wrap-error-handling #(test-fn router-id nil %))
            {:keys [body status]} (handler {})]
        (is (= http-500-internal-server-error status))
        (is (str/includes? body "Waiter Error 500"))))))

(deftest test-get-leader-state
  (let [router-id "test-router-id"
        leader-id-fn (constantly router-id)
        test-fn (wrap-handler-json-response get-leader-state)]
    (testing "successful response"
      (let [leader?-fn (constantly true)
            state {"leader?" (leader?-fn), "leader-id" (leader-id-fn)}
            {:keys [body status]} (test-fn router-id leader?-fn leader-id-fn {})]
        (is (= http-200-ok status))
        (is (= (json/read-str body) {"router-id" router-id, "state" state}))))

    (testing "exception response"
      (let [leader?-fn (fn [] (throw (Exception. "Test Exception")))
            {:keys [body status]} (test-fn router-id leader?-fn leader-id-fn {})]
        (is (= http-500-internal-server-error status))
        (is (str/includes? body "Waiter Error 500"))))))

(deftest test-get-chan-latest-state-handler
  (let [router-id "test-router-id"
        test-fn (wrap-handler-json-response get-chan-latest-state-handler)]
    (testing "successful response"
      (let [state-atom (atom nil)
            query-state-fn (fn [] @state-atom)
            state {"foo" "bar"}
            _ (reset! state-atom state)
            {:keys [body status]} (test-fn router-id query-state-fn {})]
        (is (= http-200-ok status))
        (is (= (json/read-str body) {"router-id" router-id, "state" state}))))))

(deftest test-get-router-metrics-state
  (let [router-id "test-router-id"
        test-fn (wrap-handler-json-response get-router-metrics-state)]
    (testing "successful response"
      (let [state {"router-metrics" "foo"}
            router-metrics-state-fn (constantly state)
            {:keys [body status]} (test-fn router-id router-metrics-state-fn {})]
        (is (= http-200-ok status))
        (is (= {"router-id" router-id "state" state}
               (json/read-str body)))))

    (testing "exception response"
      (let [router-metrics-state-fn (fn [] (throw (Exception. "Test Exception")))
            {:keys [body status]} (test-fn router-id router-metrics-state-fn {})]
        (is (= http-500-internal-server-error status))
        (is (str/includes? body "Waiter Error 500"))))))

(deftest test-get-query-chan-state-handler
  (let [router-id "test-router-id"
        test-fn (wrap-async-handler-json-response get-query-chan-state-handler)]
    (testing "successful response"
      (let [scheduler-chan (async/promise-chan)
            state {"foo" "bar"}
            _ (async/go
                (let [{:keys [response-chan]} (async/<! scheduler-chan)]
                  (async/>! response-chan state)))
            {:keys [body status]} (async/<!! (test-fn router-id scheduler-chan {}))]
        (is (= http-200-ok status))
        (is (= (json/read-str body) {"router-id" router-id, "state" state}))))))

(deftest test-get-scheduler-state
  (let [router-id "test-router-id"
        scheduler (reify scheduler/ServiceScheduler
                    (state [_ _]
                      {:scheduler "state"}))
        test-fn (wrap-handler-json-response get-scheduler-state)]
    (testing "successful response"
      (let [state (walk/stringify-keys (scheduler/state scheduler #{}))
            {:keys [body status]} (test-fn router-id scheduler {})]
        (is (= http-200-ok status))
        (is (= (json/read-str body) {"router-id" router-id, "state" state}))))))

(deftest test-get-statsd-state
  (let [router-id "test-router-id"
        test-fn (wrap-handler-json-response get-statsd-state)]
    (testing "successful response"
      (let [state (walk/stringify-keys (statsd/state))
            {:keys [body status]} (test-fn router-id {})]
        (is (= http-200-ok status))
        (is (= (json/read-str body) {"router-id" router-id, "state" state}))))))

(deftest test-get-service-state
  (let [router-id "router-id"
        service-id "service-1"
        local-usage-agent (agent {service-id {"last-request-time" "foo"}})
        enable-work-stealing-support? (constantly true)]
    (testing "returns 400 for missing service id"
      (is (= http-400-bad-request
             (:status (async/<!! (get-service-state router-id enable-work-stealing-support? nil local-usage-agent "" {} {}))))))
    (let [instance-rpc-chan (async/chan 1)
          populate-maintainer-chan! (make-populate-maintainer-chan! instance-rpc-chan)
          query-state-chan (async/chan 1)
          query-work-stealing-chan (async/chan 1)
          maintainer-state-chan (async/chan 1)
          responder-state {:state "responder state"}
          work-stealing-state {:state "work-stealing state"}
          maintainer-state {:state "maintainer state"}
          start-instance-rpc-fn (fn []
                                  (async/go
                                    (dotimes [_ 2]
                                      (let [{:keys [method response-chan]} (async/<! instance-rpc-chan)]
                                        (condp = method
                                          :query-state (async/>! response-chan query-state-chan)
                                          :query-work-stealing (async/>! response-chan query-work-stealing-chan))))))
          start-query-chan-fn (fn []
                                (async/go
                                  (let [{:keys [response-chan]} (async/<! query-state-chan)]
                                    (async/>! response-chan responder-state)))
                                (async/go
                                  (let [{:keys [response-chan]} (async/<! query-work-stealing-chan)]
                                    (async/>! response-chan work-stealing-state))))
          start-maintainer-fn (fn []
                                (async/go (let [{:keys [service-id response-chan]} (async/<! maintainer-state-chan)]
                                            (async/>! response-chan (assoc maintainer-state :service-id service-id)))))
          get-service-state (wrap-async-handler-json-response get-service-state)]
      (start-instance-rpc-fn)
      (start-query-chan-fn)
      (start-maintainer-fn)
      (let [query-sources {:autoscaler-state (fn [{:keys [service-id]}]
                                               {:service-id service-id :source "autoscaler"})
                           :keyword-state :disabled
                           :maintainer-state maintainer-state-chan
                           :map-state {:foo "bar"}}
            response (async/<!! (get-service-state router-id enable-work-stealing-support? populate-maintainer-chan!
                                                   local-usage-agent service-id query-sources {}))
            service-state (json/read-str (:body response) :key-fn keyword)]
        (is (= router-id (get-in service-state [:router-id])))
        (is (= {:service-id service-id :source "autoscaler"} (get-in service-state [:state :autoscaler-state])))
        (is (= (name :disabled) (get-in service-state [:state :keyword-state])))
        (is (= {:last-request-time "foo"} (get-in service-state [:state :local-usage])))
        (is (= (assoc maintainer-state :service-id service-id) (get-in service-state [:state :maintainer-state])))
        (is (= {:foo "bar"} (get-in service-state [:state :map-state])))
        (is (= responder-state (get-in service-state [:state :responder-state])))
        (is (= work-stealing-state (get-in service-state [:state :work-stealing-state])))))))

(deftest test-acknowledge-consent-handler
  (let [current-time-ms (System/currentTimeMillis)
        clock (constantly current-time-ms)
        test-token "www.example.com"
        test-service-description {"cmd" "some-cmd", "cpus" 1, "mem" 1024}
        token->service-description-template (fn [token]
                                              (when (= token test-token)
                                                (assoc test-service-description
                                                  "source-tokens" [(sd/source-tokens-entry test-token test-service-description)])))
        token->token-metadata (fn [token] (when (= token test-token) {"owner" "user"}))
        service-description->service-id (fn [service-description]
                                          (str "service-" (count service-description) "." (count (str service-description))))
        test-user "test-user"
        test-service-id (-> test-service-description
                            (assoc "permitted-user" test-user
                                   "run-as-user" test-user
                                   "source-tokens" [(sd/source-tokens-entry test-token test-service-description)])
                            service-description->service-id)
        request->consent-service-id (fn [request]
                                      (if (some-> request (utils/request->host) (utils/authority->host) (= test-token))
                                        test-service-id
                                        "service-123.123456789"))
        add-encoded-cookie (fn [response cookie-name cookie-value consent-expiry-days]
                             (assoc-in response [:cookie cookie-name] {:value cookie-value :age consent-expiry-days}))
        consent-expiry-days 1
        consent-cookie-value (fn consent-cookie-value [mode service-id token {:strs [owner]}]
                               (when mode
                                 (-> [mode (clock)]
                                     (concat (case mode
                                               "service" (when service-id [service-id])
                                               "token" (when (and owner token) [token owner])
                                               nil))
                                     vec)))
        acknowledge-consent-handler-fn (fn [request]
                                         (let [request' (-> request
                                                            (update :authorization/user #(or %1 test-user))
                                                            (update :request-method #(or %1 :post))
                                                            (update :scheme #(or %1 :http)))]
                                           (acknowledge-consent-handler
                                             token->service-description-template token->token-metadata
                                             request->consent-service-id consent-cookie-value add-encoded-cookie
                                             consent-expiry-days request')))]
    (testing "unsupported request method"
      (let [request {:request-method :get}
            {:keys [body headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-405-method-not-allowed status))
        (is (= expected-text-response-headers headers))
        (is (str/includes? body "Only POST supported"))))

    (testing "host and origin mismatch"
      (let [request {:headers {"host" "www.example2.com"
                               "origin" (str "http://" test-token)}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-400-bad-request status))
        (is (= expected-text-response-headers headers))
        (is (nil? cookie))
        (is (str/includes? body "Origin is not the same as the host"))))

    (testing "referer and origin mismatch"
      (let [request {:headers {"host" test-token
                               "origin" (str "http://" test-token)
                               "referer" "http://www.example2.com/consent"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-400-bad-request status))
        (is (= expected-text-response-headers headers))
        (is (nil? cookie))
        (is (str/includes? body "Referer does not start with origin"))))

    (testing "mismatch in x-requested-with"
      (let [request {:headers {"host" test-token
                               "origin" (str "http://" test-token)
                               "referer" (str "http://" test-token "/consent")
                               "x-requested-with" "AJAX"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-400-bad-request status))
        (is (= expected-text-response-headers headers))
        (is (nil? cookie))
        (is (str/includes? body "Header x-requested-with does not match expected value"))))

    (testing "missing mode param"
      (let [request {:headers {"host" test-token
                               "origin" (str "http://" test-token)
                               "referer" (str "http://" test-token "/consent")
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"service-id" "service-id-1"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-400-bad-request status))
        (is (= expected-text-response-headers headers))
        (is (nil? cookie))
        (is (str/includes? body "Missing or invalid mode"))))

    (testing "invalid mode param"
      (let [request {:authorization/user test-user
                     :headers {"host" test-token
                               "origin" (str "http://" test-token)
                               "referer" (str "http://" test-token "/consent")
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"mode" "unsupported", "service-id" "service-id-1"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-400-bad-request status))
        (is (= expected-text-response-headers headers))
        (is (nil? cookie))
        (is (str/includes? body "Missing or invalid mode"))))

    (testing "missing service-id param"
      (let [request {:authorization/user test-user
                     :headers {"host" test-token
                               "origin" (str "http://" test-token)
                               "referer" (str "http://" test-token "/consent")
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"mode" "service"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-400-bad-request status))
        (is (= expected-text-response-headers headers))
        (is (nil? cookie))
        (is (str/includes? body "Missing service-id"))))

    (testing "missing service description for token"
      (let [test-host (str test-token ".test2")
            request {:authorization/user test-user
                     :headers {"host" test-host
                               "origin" (str "http://" test-host)
                               "referer" (str "http://" test-host "/consent")
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"mode" "service", "service-id" "service-id-1"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-400-bad-request status))
        (is (= expected-text-response-headers headers))
        (is (nil? cookie))
        (is (str/includes? body "Unable to load description for token"))))

    (testing "invalid service-id param"
      (let [request {:authorization/user test-user
                     :headers {"host" (str test-token ":1234")
                               "origin" "http://www.example.com:1234"
                               "referer" "http://www.example.com:1234/consent"
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"mode" "service", "service-id" "service-id-1"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-400-bad-request status))
        (is (= expected-text-response-headers headers))
        (is (nil? cookie))
        (is (str/includes? body "Invalid service-id for specified token"))))

    (testing "valid service mode request"
      (let [request {:authorization/user test-user
                     :headers {"host" (str test-token ":1234")
                               "origin" "http://www.example.com:1234"
                               "referer" "http://www.example.com:1234/consent"
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"mode" "service", "service-id" test-service-id}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-200-ok status))
        (is (= {"x-waiter-consent" {:value ["service" current-time-ms test-service-id], :age consent-expiry-days}} cookie))
        (is (= {} headers))
        (is (str/includes? body "Added cookie x-waiter-consent") (str body))))

    (testing "valid service mode request with missing origin"
      (let [request {:authorization/user test-user
                     :headers {"host" (str test-token ":1234")
                               "referer" "http://www.example.com:1234/consent"
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"mode" "service", "service-id" test-service-id}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-200-ok status))
        (is (= {"x-waiter-consent" {:value ["service" current-time-ms test-service-id], :age consent-expiry-days}} cookie))
        (is (= {} headers))
        (is (str/includes? body "Added cookie x-waiter-consent") (str body))))

    (testing "valid token mode request"
      (let [request {:authorization/user test-user
                     :headers {"host" (str test-token ":1234")
                               "origin" "http://www.example.com:1234"
                               "referer" "http://www.example.com:1234/consent"
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"mode" "token"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-200-ok status))
        (is (= {"x-waiter-consent" {:value ["token" current-time-ms test-token "user"], :age consent-expiry-days}} cookie))
        (is (= {} headers))
        (is (str/includes? body "Added cookie x-waiter-consent"))))

    (testing "valid token mode request with missing origin"
      (let [request {:authorization/user test-user
                     :headers {"host" (str test-token ":1234")
                               "referer" "http://www.example.com:1234/consent"
                               "x-requested-with" "XMLHttpRequest"}
                     :params {"mode" "token"}}
            {:keys [body cookie headers status]} (acknowledge-consent-handler-fn request)]
        (is (= http-200-ok status))
        (is (= {"x-waiter-consent" {:value ["token" current-time-ms test-token "user"], :age consent-expiry-days}} cookie))
        (is (= {} headers))
        (is (str/includes? body "Added cookie x-waiter-consent"))))))

(deftest test-render-consent-template
  (let [context {:auth-user "test-user"
                 :consent-expiry-days 1
                 :service-description-template {"cmd" "some-cmd", "cpus" 1, "mem" 1024}
                 :service-id "service-5.97"
                 :target-url "http://www.example.com:6789/some-path"
                 :token "www.example.com"}
        body (render-consent-template context)]
    (is (str/includes? body "Run Web App? - Waiter"))
    (is (str/includes? body "http://www.example.com:6789/some-path"))))

(deftest test-request-consent-handler
  (let [request-time (t/now)
        basic-service-description {"cmd" "some-cmd" "cpus" 1 "mem" 1024}
        token->service-description-template
        (fn [token]
          (let [service-description (condp = token
                                      "www.example.com" basic-service-description
                                      "www.example-i0.com" (assoc basic-service-description
                                                             "interstitial-secs" 0)
                                      "www.example-i10.com" (assoc basic-service-description
                                                              "interstitial-secs" 10)
                                      nil)]
            (cond-> service-description
              (seq service-description)
              (assoc "source-tokens" [(sd/source-tokens-entry token service-description)]))
            service-description))
        service-description->service-id (fn [service-description]
                                          (str "service-" (count service-description) "." (count (str service-description))))
        consent-expiry-days 1
        test-user "test-user"
        request->consent-service-id (fn [request]
                                      (or (some-> request
                                            (utils/request->host)
                                            (utils/authority->host)
                                            (token->service-description-template)
                                            (assoc "permitted-user" test-user "run-as-user" test-user)
                                            (service-description->service-id))
                                          "service-123.123456789"))
        request-consent-handler-fn (fn [request]
                                     (let [request' (-> request
                                                        (update :authorization/user #(or %1 test-user))
                                                        (update :request-method #(or %1 :get)))]
                                       (request-consent-handler
                                         token->service-description-template request->consent-service-id
                                         consent-expiry-days request')))
        io-resource-fn (fn [file-path]
                         (is (= "web/consent.html" file-path))
                         (StringReader. "some-content"))
        expected-service-id (fn [token]
                              (-> (token->service-description-template token)
                                  (assoc "permitted-user" test-user "run-as-user" test-user)
                                  service-description->service-id))
        template-eval-factory (fn [scheme]
                                (fn [{:keys [token] :as data}]
                                  (let [service-description-template (token->service-description-template token)]
                                    (is (= {:auth-user test-user
                                            :consent-expiry-days 1
                                            :service-description-template service-description-template
                                            :service-id (expected-service-id token)
                                            :target-url (str scheme "://" token ":6789/some-path"
                                                             (when (some-> (get service-description-template "interstitial-secs") pos?)
                                                               (str "?" (interstitial/request-time->interstitial-param-string request-time))))
                                            :token token}
                                           data)))
                                  "template:some-content"))]
    (testing "unsupported request method"
      (let [request {:authorization/user test-user
                     :request-method :post
                     :request-time request-time
                     :scheme :http}
            {:keys [body headers status]} (request-consent-handler-fn request)]
        (is (= http-405-method-not-allowed status))
        (is (= expected-text-response-headers headers))
        (is (str/includes? body "Only GET supported"))))

    (testing "token without service description"
      (let [request {:authorization/user test-user
                     :headers {"host" "www.example2.com:6789"}
                     :request-method :get
                     :request-time request-time
                     :route-params {:path "some-path"}
                     :scheme :http}
            {:keys [body headers status]} (request-consent-handler-fn request)]
        (is (= http-404-not-found status))
        (is (= expected-text-response-headers headers))
        (is (str/includes? body "Unable to load description for token"))))

    (with-redefs [io/resource io-resource-fn
                  render-consent-template (template-eval-factory "http")]
      (testing "token without service description - http scheme"
        (let [request {:authorization/user test-user
                       :headers {"host" "www.example.com:6789"}
                       :request-time request-time
                       :route-params {:path "some-path"}
                       :scheme :http}
              {:keys [body headers status]} (request-consent-handler-fn request)]
          (is (= http-200-ok status))
          (is (= {"content-type" "text/html"} headers))
          (is (= body "template:some-content")))))

    (with-redefs [io/resource io-resource-fn
                  render-consent-template (template-eval-factory "https")]
      (testing "token without service description - https scheme"
        (let [request {:authorization/user test-user
                       :headers {"host" "www.example.com:6789"}
                       :request-time request-time
                       :route-params {:path "some-path"}
                       :scheme :https}
              {:keys [body headers status]} (request-consent-handler-fn request)]
          (is (= http-200-ok status))
          (is (= {"content-type" "text/html"} headers))
          (is (= body "template:some-content")))))

    (with-redefs [io/resource io-resource-fn
                  render-consent-template (template-eval-factory "https")]
      (testing "token without service description - https x-forwarded-proto"
        (let [request {:authorization/user test-user
                       :headers {"host" "www.example.com:6789", "x-forwarded-proto" "https"}
                       :request-time request-time
                       :route-params {:path "some-path"}
                       :scheme :http}
              {:keys [body headers status]} (request-consent-handler-fn request)]
          (is (= http-200-ok status))
          (is (= {"content-type" "text/html"} headers))
          (is (= body "template:some-content")))))

    (with-redefs [io/resource io-resource-fn
                  render-consent-template (template-eval-factory "https")]
      (testing "token without service description - https x-forwarded-proto"
        (let [request {:authorization/user test-user
                       :headers {"host" "www.example-i0.com:6789", "x-forwarded-proto" "https"}
                       :request-time request-time
                       :route-params {:path "some-path"}
                       :scheme :http}
              {:keys [body headers status]} (request-consent-handler-fn request)]
          (is (= http-200-ok status))
          (is (= {"content-type" "text/html"} headers))
          (is (= body "template:some-content")))))

    (with-redefs [io/resource io-resource-fn
                  render-consent-template (template-eval-factory "https")]
      (testing "token without service description - https x-forwarded-proto"
        (let [request {:authorization/user test-user
                       :headers {"host" "www.example-i10.com:6789", "x-forwarded-proto" "https"}
                       :request-time request-time
                       :route-params {:path "some-path"}
                       :scheme :http}
              {:keys [body headers status]} (request-consent-handler-fn request)]
          (is (= http-200-ok status))
          (is (= {"content-type" "text/html"} headers))
          (is (= body "template:some-content")))))

    (with-redefs [io/resource io-resource-fn
                  render-consent-template (template-eval-factory "https")]
      (testing "token without service description - https x-forwarded-proto"
        (let [request {:authorization/user test-user
                       :headers {"host" "www.example.com:6789", "x-forwarded-proto" "https"}
                       :request-time request-time
                       :route-params {:path "some-path"}
                       :scheme :http}
              {:keys [body headers status]} (request-consent-handler-fn request)]
          (is (= http-200-ok status))
          (is (= {"content-type" "text/html"} headers))
          (is (= body "template:some-content")))))))

(deftest test-eject-instance-cannot-find-channel
  (let [notify-instance-killed-fn (fn [instance] (throw (ex-info "Unexpected call" {:instance instance})))
        instance-rpc-chan (async/chan)
        populate-maintainer-chan! (make-populate-maintainer-chan! instance-rpc-chan)
        test-service-id "test-service-id"
        request {:body (StringBufferInputStream.
                         (utils/clj->json
                           {"instance" {"id" "test-instance-id", "service-id" test-service-id}
                            "period-in-ms" 1000
                            "reason" "eject"}))}
        response-chan (eject-instance notify-instance-killed-fn populate-maintainer-chan! request)]
    (async/thread
      (let [{:keys [cid method response-chan service-id]} (async/<!! instance-rpc-chan)]
        (is (= :eject method))
        (is (= test-service-id service-id))
        (is cid)
        (is (instance? ManyToManyChannel response-chan))
        (async/close! response-chan)))
    (let [{:keys [status]} (async/<!! response-chan)]
      (is (= http-400-bad-request status)))))

(deftest test-eject-prepare-to-kill-instance
  (let [notify-instance-killed-fn (fn [instance] (throw (ex-info "Unexpected call" {:instance instance})))
        instance-rpc-chan (async/chan)
        populate-maintainer-chan! (make-populate-maintainer-chan! instance-rpc-chan)
        test-service-id "test-service-id"
        test-instance-id "test-instance-id"
        instance {:id test-instance-id
                  :service-id test-service-id
                  :started-at nil}
        request {:body (StringBufferInputStream.
                         (utils/clj->json
                           {"instance" instance
                            "period-in-ms" 1000
                            "reason" "prepare-to-kill"}))}
        track-kill-candidates-atom! (atom #{})]
    (with-redefs [scheduler/track-kill-candidate! (fn [instance-id reason duration-ms]
                                                    (swap! track-kill-candidates-atom! conj
                                                           {:duration-ms duration-ms :instance-id instance-id :reason reason}))]
      (let [response-chan (eject-instance notify-instance-killed-fn populate-maintainer-chan! request)
            eject-chan (async/promise-chan)]
        (async/thread
          (let [{:keys [cid method response-chan service-id]} (async/<!! instance-rpc-chan)]
            (is (= :eject method))
            (is (= test-service-id service-id))
            (is cid)
            (is (instance? ManyToManyChannel response-chan))
            (async/>!! response-chan eject-chan)))
        (async/thread
          (let [[{:keys [eject-period-ms instance-id]} response-chan] (async/<!! eject-chan)]
            (is (= 1000 eject-period-ms))
            (is (= (:id instance) instance-id))
            (async/>!! response-chan :ejected)))
        (let [{:keys [status]} (async/<!! response-chan)]
          (is (= http-200-ok status)))
        (is (= #{{:duration-ms 1000 :instance-id test-instance-id :reason :prepare-to-kill}}
               @track-kill-candidates-atom!))))))

(deftest test-eject-killed-instance
  (let [notify-instance-chan (async/promise-chan)
        notify-instance-killed-fn (fn [instance] (async/>!! notify-instance-chan instance))
        instance-rpc-chan (async/chan)
        populate-maintainer-chan! (make-populate-maintainer-chan! instance-rpc-chan)
        test-service-id "test-service-id"
        test-instance-id "test-instance-id"
        instance {:id test-instance-id
                  :service-id test-service-id
                  :started-at nil}
        request {:body (StringBufferInputStream.
                         (utils/clj->json
                           {"instance" instance
                            "period-in-ms" 1000
                            "reason" "killed"}))}
        track-kill-candidates-atom! (atom #{})]
    (with-redefs [scheduler/track-kill-candidate! (fn [instance-id reason duration-ms]
                                                    (swap! track-kill-candidates-atom! conj
                                                           {:duration-ms duration-ms :instance-id instance-id :reason reason}))]
      (let [response-chan (eject-instance notify-instance-killed-fn populate-maintainer-chan! request)
            eject-chan (async/promise-chan)]
        (async/thread
          (let [{:keys [cid method response-chan service-id]} (async/<!! instance-rpc-chan)]
            (is (= :eject method))
            (is (= test-service-id service-id))
            (is cid)
            (is (instance? ManyToManyChannel response-chan))
            (async/>!! response-chan eject-chan)))
        (async/thread
          (let [[{:keys [eject-period-ms instance-id]} response-chan] (async/<!! eject-chan)]
            (is (= 1000 eject-period-ms))
            (is (= (:id instance) instance-id))
            (async/>!! response-chan :ejected)))
        (let [{:keys [status]} (async/<!! response-chan)]
          (is (= http-200-ok status))
          (is (= instance (async/<!! notify-instance-chan))))
        (is (= #{{:duration-ms 1000 :instance-id test-instance-id :reason :killed}}
               @track-kill-candidates-atom!))))))

(deftest test-get-ejected-instances-cannot-find-channel
  (let [instance-rpc-chan (async/chan)
        populate-maintainer-chan! (make-populate-maintainer-chan! instance-rpc-chan)
        test-service-id "test-service-id"
        response-chan (get-ejected-instances populate-maintainer-chan! test-service-id {})]
    (async/thread
      (let [{:keys [cid method response-chan service-id]} (async/<!! instance-rpc-chan)]
        (is (= :query-state method))
        (is (= test-service-id service-id))
        (is cid)
        (is (instance? ManyToManyChannel response-chan))
        (async/close! response-chan)))
    (let [{:keys [status]} (async/<!! response-chan)]
      (is (= http-500-internal-server-error status)))))
