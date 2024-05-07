(ns throttler.core-copy
  (:require [clojure.core.async :refer [chan <!! >!! >! <! timeout go close! dropping-buffer alts!]]))


(def ^{:no-doc true} min-sleep-time 10)

(defn- round [n] (Math/round (double n)))

(def ^{:no-doc true} unit->ms
  {:microsecond 0.001 :millisecond 1
   :second 1000 :minute 60000
   :hour 3600000 :day 86400000
   :month 2678400000})

(defmacro pipe
  "Pipes an element from the from channel and supplies it to the to
  channel. The to channel will be closed when the from channel closes.
  Must be called within a go block."
  [from to]
  `(let [v# (<! ~from)]
     (if (nil? v#)
       (close! ~to)
       (>! ~to v#))))

(defn- throttle-chan [input-chan rate unit bucket-size]
  (let [rate-ms (/ rate (unit->ms unit))
        sleep-time (round (max (/ rate-ms) min-sleep-time))
        token-value (round (* sleep-time rate-ms))   ; how many messages to pipe per token
        bucket (chan (dropping-buffer bucket-size))] ; we model the bucket with a buffered channel 
    (go
      (while (>! bucket :token)
        (<! (timeout (int sleep-time)))))
    (let [output-chan (chan)] ; the throttled chan
      (go
        (while true
          (let [[v _]  (alts! [bucket] :default false)]
            (if v
              (dotimes [_ token-value]
                (when-not (pipe input-chan output-chan)
                  (close! bucket)))
              (>! output-chan :rejected)))))
      output-chan)))



(defn throttle-fn
  "Takes a function, a goal rate and a time unit and returns a
  function that is equivalent to the original but that will have a maximum
  throughput of 'rate'.

  Optionally accepts a burst rate, in which case the resulting function
  will behave like a bursty channel. See throttle-chan for details."


  [f failure-f rate unit bucket-size]
  (let [in (chan (dropping-buffer 1))
        out (throttle-chan in rate unit bucket-size)] 
    (fn [& args]
      (>!! in :eval-request)
      (case
       (<!! out)
        :eval-request (apply f args)
        (apply failure-f args)))))



(comment


  (def +# (throttle-fn + (fn [& _] (println "failure")) 1 :second 1))


  ;;  (fn [f]
  ;;   (fn [& args]
  ;;           ;; The approach is simple: pipe a bogus message through a
  ;;           ;; throttled channel before evaluating the original function.
  ;;     (>!! in :eval-request)
  ;;     (<!! out)
  ;;     (apply f args)))
  ;; (fn [& args]
  ;;     (>!! in :eval-request)
  ;;     (<!! out)
  ;;     (apply + args))
  
  (def tomato-echo (chan 2))
  (>!! tomato-echo :sth)
  (<!! tomato-echo)
  (close! tomato-echo)
  (when-not nil (print "lol"))
  (+# 1 1) ; => 2
  (dotimes [_ 10] (print (+# 1 1) "\n"))
  (time (dotimes [_ 1] (+# 1 1)))
  (apply str `(1 2 3))

  )
