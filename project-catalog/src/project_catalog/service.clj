(ns project-catalog.service
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor.helpers :refer [definterceptor defhandler]]
            [ring.util.response :as ring-resp]
            [clj-http.client :as client]
            [monger.core :as mg]
            [clojure.data.json :as json]
            [monger.collection :as mc]
            [monger.json]))

;(println ((client/get "https://api.github.com/search/repositories?q=music+language:clojure"
;  {:debug false
;   :content-type :json
;   :accept :json
;   }:body))



(defn auth0-token []
  (let [ret
        (client/post "url"
              {:debug false
               :content-type :json
               :form-params {:client_id (System/getenv "AUTH")
                             :client_secret (System/getenv "AUTH_SE")
                             :grant_type "client_cred"}
               })]
               (json/read-str (ret :body))))

(defn auth0-connections [tok]
  (let [ret
        (client/get "url"
              {:debug false
               :content-type :json
               :accept :json
               :headers {"Authorization" (format "Bearer %s" tok)}})]
    (ret :body)))

(defn git-search [q]
  (let [ret
    (client/get
      (format "https://api.github.com/search/repositories?q=%s+language:clojure" q)
      {:debug false
       :content-type :json
       :accept :json
       })]
    (json/read-str (ret :body))))

(defn git-get
  [request]
  (http/json-response (git-search (get-in request [:query-params :q]))))

(defhandler token-check [request]
  (let [token (get-in request [:headers "x-catalog-token"])]
    (if (not (= token "xico-token"))
      (assoc (ring-resp/response {:body "access denied"}) :status 403))))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

;; MONGO_CONNECTION is of this form
;; mongodb://username:password@staff.mongohq.com:port/dbname
(defn get-projects
  [request]
  (let [uri (System/getenv "MONGO_CONNECTION")
        {:keys [conn db]} (mg/connect-via-uri uri)]
          (http/json-response (mc/find-maps db "project-catalog"))))

(defn add-project
  [request]
  (let [incoming (:json-params request)
        connect-string (System/getenv "MONGO_CONNECTION")
        {:keys [conn db]} (mg/connect-via-uri connect-string)]
    (ring-resp/created
      "http://my-created-resource-url"
      (mc/insert-and-return db "project-catalog" incoming))))



(defn db-get-project [proj-name]
  (let [connect-string (System/getenv "MONGO_CONNECTION")
        {:keys [conn db]} (mg/connect-via-uri connect-string)]
    (mc/find-maps db "project-catalog" {:proj-name proj-name})))

(defn get-project
  [request]
  (db-get-project
    (get-in request [:path-params :proj-name])))

(defn home-page
  [request]
  (ring-resp/response "hi xico!"))


(def mock-project-collection
  {:learning-clojure {:name "Learning Clojure" :status "WIP"}
   :learning-rust {:name "Learning Rust" :status "DO"}
   :learning-python {:name "Python" :status "Love it"}})

(defn get-projects-memory
  [request]
  (http/json-response mock-project-collection))


(defn get-project-memory
  [request]
  (let [projname (get-in request [:path-params :project-name])]
    (http/json-response ((keyword projname) mock-project-collection))))

(defn add-project-memory
  [request]
  (prn (:json-params request))
       (ring-resp/created "http://fake-201-url" "fake 201 in the body"))
;; Defines "/" and "/about" routes with their associated :get handlers.
;; The interceptors defined after the verb map (e.g., {:get home-page}
;; apply to / and its children (/about).
(def common-interceptors [(body-params/body-params) http/html-body token-check])

;; Tabular routes
(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/projects-memory" :get (conj common-interceptors `get-projects-memory)]
              ["/projects-memory" :post (conj common-interceptors `add-project-memory)]
              ["/projects-memory/:project-name" :get (conj common-interceptors `get-project-memory)]
              ["/see-also3" :get (conj common-interceptors `git-get)]
              ["/projects" :get (conj common-interceptors `get-projects)]
              ["/projects" :post (conj common-interceptors `add-project)]
              ["/projects/:proj-name" :get (conj common-interceptors `get-project)]
              ["/about" :get (conj common-interceptors `about-page)]})

;; Map-based routes
;(def routes `{"/" {:interceptors [(body-params/body-params) http/html-body]
;                   :get home-page
;                   "/about" {:get about-page}}})

;; Terse/Vector-based routes
;(def routes
;  `[[["/" {:get home-page}
;      ^:interceptors [(body-params/body-params) http/html-body]
;      ["/about" {:get about-page}]]]])


;; Consumed by project-catalog.server/create-server
;; See http/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::http/allowed-origins ["scheme://host:port"]

              ;; Tune the Secure Headers
              ;; and specifically the Content Security Policy appropriate to your service/application
              ;; For more information, see: https://content-security-policy.com/
              ;;   See also: https://github.com/pedestal/pedestal/issues/499
              ;;::http/secure-headers {:content-security-policy-settings {:object-src "'none'"
              ;;                                                          :script-src "'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:"
              ;;                                                          :frame-ancestors "'none'"}}

              ;; Root for resource interceptor that is available by default.
              ::http/resource-path "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
              ;;  This can also be your own chain provider/server-fn -- http://pedestal.io/reference/architecture-overview#_chain_provider
              ::http/type :jetty
              ;;::http/host "localhost"
              ::http/port 8080
              ;; Options to pass to the container (Jetty)
              ::http/container-options {:h2c? true
                                        :h2? false
                                        ;:keystore "test/hp/keystore.jks"
                                        ;:key-password "password"
                                        ;:ssl-port 8443
                                        :ssl? false
                                        ;; Alternatively, You can specify you're own Jetty HTTPConfiguration
                                        ;; via the `:io.pedestal.http.jetty/http-configuration` container option.
                                        ;:io.pedestal.http.jetty/http-configuration (org.eclipse.jetty.server.HttpConfiguration.)
                                        }})
