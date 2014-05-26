(ns chat-server.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [<! >! <!! >!! timeout chan alt! alts!! go]]
            [clojure.tools.cli :as cli]
            [clj-time.core :as t]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [lamina.core :as l]
            [lamina.viz :as lv]
            [aleph.tcp :as tcp]
            [aleph.http :as h]
            [gloss.core :as g]
            [gloss.io :as gio]))

(def frame-codec (let [int-ascii (g/string-integer :ascii :length 4)
                       int-ascii-with-space-suffix (g/string-integer :ascii :length 4 :suffix \ )
                       space-delimited-string (g/string :ascii :delimiters [" "])
                       unbounded-string (g/string :ascii)
                       cmd-frame {:snd (g/ordered-map :to space-delimited-string
                                                      :message unbounded-string)
                                  :rcv (g/ordered-map :from space-delimited-string
                                                      :word-count int-ascii-with-space-suffix
                                                      :message unbounded-string)
                                  :reg (g/ordered-map :name unbounded-string)
                                  :bye unbounded-string}
                       trf-input-before-encoding (fn [[cmd data]]
                                                   [(s/upper-case (name cmd))
                                                    (cons \  (.array (gio/contiguous (gio/encode (cmd-frame cmd) data))))])
                       trf-output-after-decoding (fn [[cmd-str byte-seq]]
                                                   (let [cmd (keyword (s/lower-case cmd-str))
                                                         data-str (apply str (map char (drop 1 byte-seq)))
                                                         ret [cmd (gio/decode (cmd-frame cmd) (gio/to-byte-buffer data-str))]]
                                                     ret))
                       proto-frame [(g/string :ascii :delimiters [" "]) (g/repeated :byte :prefix int-ascii)]]
                   (g/compile-frame proto-frame trf-input-before-encoding trf-output-after-decoding)))
(let [clients (agent {})]
  (defn server-handler [ch client-info]
    (l/receive-all ch (let [my-name (atom nil)]
                        (fn [[cmd {:keys [to message name]} :as data]]
                          (case cmd
                            :reg (if-not @my-name
                                   (do
                                     (println name " registered")
                                     (reset! my-name name)
                                     (send clients (fn [clients-map]
                                                     (let [ret (assoc clients-map name ch)]
                                                       (println (count clients-map) "clients-map update for user " name) ret))))
                                   (println @my-name " this connection has already registered. request to reg as " name " ignored"))
                            :snd (if-not @my-name (println (l/enqueue ch [:rcv {:from "chat-server" :word-count 4
                                                                                :message "please register before messaging"}]))
                                         (if-let [to-chan (@clients to)]
                                           (let [resp [:rcv {:from @my-name :word-count (count (s/split message #" +")) :message message}]]
                                             (l/enqueue to-chan resp))
                                           (println @my-name " user : " to " has not registered yet " data " request not successful")))))))))

(let [mname #(format "%014d" %)
      permitted-alphabets "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%&*"
      rand-word #(apply str (repeatedly (rand-int 15) (fn [] (rand-nth permitted-alphabets))))
      random-alpha-numeric-string #(s/join " " (repeatedly (rand-int 10) rand-word))]
  (defn clients-simulation [num-clients server-host server-port]
    (let [ch (fn [cid] (tcp/tcp-client {:host server-host :port server-port
                                       :frame frame-codec :name (str (mname cid) "'s client")}))]
      (l/enqueue ch [:reg {:name name}])
      (l/wait-for-message ch))))

(let [cli-options [["-p" "--port PORT" "Port number" :default 5000 :parse-fn #(Integer/parseInt %)
                    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
                   ["-H" "--host hostname" "host name on which the chat-server is running" :default "localhost"]
                   ["-n" "--num-clients number-of-clients" "number of clients" :default 2 :parse-fn #(Integer/parseInt %)]
                   ["-s" "--server"] ["-c" "--client"] ["-h" "--help"]]]
  (defn -main [& args]
    (let [{{:keys [host port server client num-clients] :as options} :options} (cli/parse-opts args cli-options)]
      (cond
       server (do (println (str "starting server on port " port))
                  (println (tcp/start-tcp-server server-handler {:host host :port port :frame frame-codec :name "Sunil's Clojure chat-server"}))
                  (println "tcp/start-tcp-server returned!!"))
       client (do (println (str "starting client spawner pounding chat-server on " port))
                  (clients-simulation num-clients host port))))))
