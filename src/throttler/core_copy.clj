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

(defn- chan-throttler* [rate-ms bucket-size]
  (let [sleep-time (round (max (/ rate-ms) min-sleep-time))
        token-value (round (* sleep-time rate-ms))   ; how many messages to pipe per token
        bucket (chan (dropping-buffer bucket-size))] ; we model the bucket with a buffered channel

    ;; The bucket filler thread. Puts a token in the bucket every
    ;; sleep-time seconds. If the bucket is full the token is dropped
    ;; since the bucket channel uses a dropping buffer.
    (go
      (while (>! bucket :token)
        (<! (timeout (int sleep-time)))))

    ;; The piping thread. Takes a token from the bucket (blocking until
    ;; one is ready if the bucket is empty), and forwards token-value
    ;; messages from the source channel to the output channel.

    ;; For high frequencies, we leave sleep-time fixed to
    ;; min-sleep-time, and we increase token-value, the number of
    ;; messages to pipe per token. For low frequencies, the token-value
    ;; is 1 and we adjust sleep-time to obtain the desired rate.

    (fn [c]
      (let [c' (chan)] ; the throttled chan
        (go
          ;; (while (<! bucket) ; block for a token
          (while true
            (let [[v _]  (alts! [bucket] :default false)]
              (if v
                (dotimes [_ token-value]
                  (when-not (pipe c c')
                    (close! bucket)))
                (>! c' :rejected)))))
        c'))))

(defn chan-throttler
  "Returns a function that will take an input channel and return an
   output channel with the desired rate. Optionally acceps a bucket size
   for bursty channels.

   If the throttling function returned here is used on more than one
   channel, they will all share the same token-bucket. This means their
   overall output rate combined will be equal to the provided rate. In
   other words, they will all share the alloted bandwith using
   statistical multiplexing.

   See fn-throttler for an example that can trivially be extrapolated to
   chan-throttler."

  ([rate unit]
   (chan-throttler rate unit 1))
  ([rate unit bucket-size]
   (let [rate-ms (/ rate (unit->ms unit))]
     (chan-throttler* rate-ms bucket-size))))

(defn throttle-chan
  "Takes a write channel, a goal rate and a unit and returns a read
      channel. Messages written to the input channel can be read from
      the throttled output channel at a rate that will be at most the
      provided goal rate.

      Optionally takes a bucket size, which will correspond to the
      maximum number of burst messages.

      As an example, the channel produced by calling:

      (throttle-chan (chan) 1 :second 9)

      Will transmit 1 message/second on average but can transmit up to
      10 messages on a single second (9 burst messages + 1
      message/second).

      Note that after the burst messages have been consumed they have to
      be refilled in a quiescent period at the provided rate, so the
      overall goal rate is not affected in the long term.

      The throttled channel will be closed when the input channel
      closes."

  ([c rate unit]
   (throttle-chan c rate unit 1))

  ([c rate unit bucket-size]
   ((chan-throttler rate unit bucket-size) c)))



(defn throttle-fn
  "Takes a function, a goal rate and a time unit and returns a
  function that is equivalent to the original but that will have a maximum
  throughput of 'rate'.

  Optionally accepts a burst rate, in which case the resulting function
  will behave like a bursty channel. See throttle-chan for details."


  [f failure-f rate unit bucket-size]
  (((fn [rate unit bucket-size]
      (let [in (chan (dropping-buffer 1))
            out (throttle-chan in rate unit bucket-size)]
        (fn [f failure]
          (fn [& args]
            (>!! in :eval-request)
            (case
             (<!! out)
              :eval-request (apply f args)
              (apply failure args)))))) rate unit bucket-size) f failure-f))

(defn experiment []
  (fn[x] (println x)))


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
  (def ex (experiment))
  (ex 123)
  
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
