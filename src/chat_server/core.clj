(ns chat-server.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [<! >! <!! >!! timeout chan alt! alts!! go]]
            [clojure.tools.cli :as cli]
            [beckon :as b]
            [potemkin :as p]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [lamina.core :as l]
            [lamina.trace.probe :as probe]
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
                                                         data-str (apply str (map char (drop 1 byte-seq)))]
                                                     [cmd (gio/decode (cmd-frame cmd) (gio/to-byte-buffer data-str))]))
                       proto-frame [(g/string :ascii :delimiters [" "]) (g/repeated :byte :prefix int-ascii)]]
                   (g/compile-frame proto-frame trf-input-before-encoding trf-output-after-decoding)))

(defn start-chat-server [server-host server-port]
  (let [clients (agent {})
        count-words #(loop [[x & xs] % num-words 0 state :space]
                       (if-not x num-words
                               (if (= x \ )
                                 (recur xs num-words :space)
                                 (recur xs (if (= state :space) (unchecked-inc num-words) num-words) :word))))
        message-chan (probe/probe-channel :chat-server-message-log)
        error-chan (probe/probe-channel :chat-server-error-log)
        terminate-connections #(doseq [[name ch] @clients] (l/enqueue-and-close ch [:bye " "]))
        server-handler (fn [ch client-info]
                         (l/receive-all ch
                                        (let [my-name (atom nil)]
                                          (fn [[cmd {:keys [to message name]} :as data]]
                                            (case cmd
                                              :reg (if-not @my-name
                                                     (do
                                                       (l/enqueue message-chan [:registered name])
                                                       (reset! my-name name)
                                                       (send clients (fn [clients-map]
                                                                       (l/enqueue message-chan [:clients-map-updated
                                                                                                :clients-count (count clients-map)
                                                                                                :name name])
                                                                       (assoc clients-map name ch))))
                                                     (l/enqueue error-chan [:my-name @my-name :reg-request-ignored name]))
                                              :snd (if-not @my-name (l/enqueue ch [:rcv {:from "chat-server" :word-count 4
                                                                                         :message "please register before messaging"}])
                                                           (if-let [to-chan (or (@clients to) (if (= my-name to) ch))]
                                                             (l/enqueue to-chan [:rcv {:from @my-name :message message
                                                                                       :word-count (count-words message)}])
                                                             (l/enqueue error-chan [:from @my-name :user-not-registered to
                                                                                    :message-not-sent message]))))))))]
    (println (str "starting server on port " server-port))
    (tcp/start-tcp-server server-handler {:host server-host :port server-port :frame frame-codec :name "Sunil's Clojure chat-server"})
    (reset! (b/signal-atom "INT") #{#(do (println :handling-sigint)
                                         (terminate-connections)
                                         (b/raise! "TERM"))})))

(defn now [] (c/to-long (t/now)))

(let [mname #(format "%014d" %)
      permitted-alphabets "abcdefghijklmnopqrstuvwxyz"
      rand-word #(apply str (repeatedly (rand-int 15) (fn [] (rand-nth permitted-alphabets))))
      random-alpha-numeric-string #(s/join " " (repeatedly (rand-int 10) rand-word))
      msg-fmt [(g/string-integer :ascii :length 14 :suffix \ ) (g/string :ascii)]]
  (defn clients-simulation [server-host server-port num-clients num-test-messages]
    (let [msg-activity-chan (probe/probe-channel :clients-simulation-message-activity-log)
          client-reg-chan (probe/probe-channel :clients-simulation-registration-log)
          server-config {:host server-host :port server-port :frame frame-codec}
          clients (vec (pmap (fn [cid]
                               (let [name (mname cid)]
                                 (l/run-pipeline (tcp/tcp-client (assoc server-config :name name))
                                                 (fn [c]
                                                   (l/enqueue c [:reg {:name name}])
                                                   (l/receive-all c (fn [[cmd {:keys [from message word-count]} :as whole]]
                                                                      (case cmd
                                                                        :bye (l/close c)
                                                                        :rcv (let [[message-id msg] (gio/decode msg-fmt
                                                                                                                (gio/to-byte-buffer message))]
                                                                               (l/enqueue msg-activity-chan [:rcv {:message msg :from from
                                                                                                                   :to (format "%014d" cid)
                                                                                                                   :recieved-time (now)
                                                                                                                   :message-id message-id}])))))
                                                   c))))
                             (range num-clients)))
          message (fn [id1 id2 msg msg-id]
                    (l/run-pipeline (clients id1)
                                    (fn [ch1]
                                      (let [msg-with-id (format "%014d %s" msg-id msg)]
                                        (l/enqueue ch1 [:snd {:to (mname id2) :message msg-with-id}]))))
                    (l/enqueue msg-activity-chan [:snd {:message msg :message-id msg-id :to (mname id2) :from (mname id1) :sent-time (now)}]))]
      (Thread/sleep 4000)
      (doseq [test-msg-id (range num-test-messages)]
        (message (rand-int num-clients) (rand-int num-clients) (random-alpha-numeric-string) test-msg-id)))))
(let [messages-in-transit-count (agent 0)
      messages-in-transit (agent {})
      message-latencies (probe/probe-channel :message-latencies)
      messages-unprocessed-recieved-messages (atom {})]
  (defn message-latency [[cmd {:keys [from to message message-id sent-time recieved-time] :as message-data}]]
    (case cmd
      :snd (do
             (send messages-in-transit-count inc)
             (if-let [recieved-time (@messages-unprocessed-recieved-messages message-id)]
               (do
                 (swap! messages-unprocessed-recieved-messages dissoc message-id)
                 (l/enqueue message-latencies (- recieved-time sent-time)))
               (send messages-in-transit assoc message-id sent-time)))
      :rcv (do
             (send messages-in-transit-count dec)
             (send messages-in-transit (fn [mp]
                                         (if-let [sent-time (mp message-id)]
                                           (do
                                             (send messages-in-transit dissoc message-id)
                                             (l/enqueue message-latencies (- recieved-time sent-time)))
                                           (swap! messages-unprocessed-recieved-messages assoc message-id recieved-time))))))))
(let [connections-in-progress-count (agent 0)
      connections-in-progress (agent {})
      connection-latencies (probe/probe-channel :connection-latencies)
      connection-unprocessed-finish-messages (atom {})]
  (defn connection-latency [[cmd {:keys [name start-time finish-time] :as conn-data}]]
    (case cmd
      :start  (do
                (send connections-in-progress-count inc)
                (if-let [finish-time (@connection-unprocessed-finish-messages name)]
                  (do
                    (swap! connection-unprocessed-finish-messages dissoc name)
                    (l/enqueue connection-latencies (- finish-time start-time)))
                  (send connections-in-progress assoc name start-time)))
      :finish (do
                (send connections-in-progress-count dec)
                (send connections-in-progress (fn [mp]
                                                (if-let [start-time (mp name)]
                                                  (do
                                                    (send connections-in-progress dissoc name)
                                                    (l/enqueue connection-latencies (- finish-time start-time)))
                                                  (swap! connection-unprocessed-finish-messages assoc name finish-time))))))))
(let [cli-options [["-p" "--port PORT" "Port number" :default 5000 :parse-fn #(Integer/parseInt %)
                    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
                   ["-H"    "--host hostname" "host name on which the chat-server is running" :default "localhost"]
                   ["-n"    "--num-clients number-of-clients" "number of clients" :default 2 :parse-fn #(Integer/parseInt %)]
                   ["-m"    "--num-test-messages number-of-test-messages" :default 10 :parse-fn #(Integer/parseInt %)]
                   ["-r"    "--message-rate" "number of messages to be sent per second" :default 1000]
                   ["-s"    "--server" "run as server"]
                   ["-c"    "--client" "run as clients simulator"]
                   ["-j"    "--judge" "provide metrics to judge the chat-server"]
                   ["-mlhi" "--message-latency-histogram-interval" "message histogram interval in milli-seconds" :default 250]
                   ["-clhi" "--connect-latency-histogram-interval" "connect latency histobram interval in milli-seconds":default 250]
                   ["-h"    "--help" "show help"]
                   ["-l"    "--log" "print log messages to stdout"]]]
  (defn -main [& args]
    (let [{{:keys [log judge host port server client help num-clients num-test-messages
                   message-latency-histogram-interval connect-latency-histogram-interval] :as options}
           :options summary :summary} (cli/parse-opts args cli-options)]
      (println options)
      (when judge
        (let [connection-latency-histogram (agent {})]
          (l/receive-all (probe/probe-channel :connection-latencies)
                         (fn [x]
                           (send connection-latency-histogram update-in
                                 [(quot x connect-latency-histogram-interval)] #(if % (inc %) 1)))))
        (let [message-latency-histogram (agent {})]
          (l/receive-all (probe/probe-channel :message-latencies)
                         (fn [x]
                           (send message-latency-histogram update-in
                                 [(quot x message-latency-histogram-interval)] #(if % (inc %) 1))))))
      (if log (l/receive-all (probe/probe-channel :log) println))
      (cond
       help (println summary)
       server (do
                (if log
                  (l/siphon (probe/probe-channel :chat-server-error-log)
                            (probe/probe-channel :chat-server-message-log)
                            (probe/probe-channel :log)))
                (start-chat-server host port))
       client (do
                (if log
                  (l/siphon (probe/probe-channel :clients-simulation-message-activity-log)
                            (probe/probe-channel :clients-simulation-registration-log)
                            (probe/probe-channel :log)))
                (when judge
                  (l/receive-in-order (probe/probe-channel :clients-simulation-message-activity-log) message-latency)
                  (l/receive-in-order (probe/probe-channel :clients-simulation-registration-log) connection-latency))
                (clients-simulation host port num-clients num-test-messages))
       :default (println summary)))))
