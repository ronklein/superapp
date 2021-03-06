(ns my.company.superapp.main
    (:require [neko.activity :refer [defactivity set-content-view!]]
              [neko.debug :refer [*a]]
              [neko.notify :refer [toast]]
              [neko.resource :as res]
              [neko.find-view :refer [find-view]]
              [neko.threading :refer [on-ui]]
              [neko.log :as log]
              [neko.ui :as ui]
              [neko.action-bar :as action-bar :refer [setup-action-bar tab-listener]]
              [clojure.core.async
                :as a
                :refer [>! <! >!! <!! go chan go-loop put! tap mult close! thread
                        alts! alts!! timeout]]
              )
    (:import android.widget.EditText
             fi.iki.elonen.NanoHTTPD
             fi.iki.elonen.NanoWSD
             fi.iki.elonen.NanoWSD$WebSocket
             ))

;; We execute this function to import all subclasses of R class. This gives us
;; access to all application resources.
(res/import-all)

(defn notify-from-edit
  "Finds an EditText element with ID ::user-input in the given activity. Gets
  its contents and displays them in a toast if they aren't empty. We use
  resources declared in res/values/strings.xml."
  [activity]
  (let [^EditText input (.getText (find-view activity ::user-input))]
    (toast (if (empty? input)
             (res/get-string R$string/input_is_empty)
             (res/get-string R$string/your_input_fmt input))
           :long)))


(defn init-httpd
  [bind-address port]
  (let [httpd (proxy [NanoHTTPD] [bind-address port]
                (serve [session]
                  (let [msg "<html><body><h1>Hello from server!</h1>\n<h2>Session info:</h2>"
                        uri (.getUri session)
                        method (.getMethod session)
                        headers (.getHeaders session)]
                    (log/i "Session info:")
                    (log/i (str "URI: " uri))
                    (log/i (str "Method: " method))
                    (log/i (str "Headers: " headers))
                    (NanoHTTPD/newFixedLengthResponse 
                     (str msg "<h3>URI: " uri "</h3>\n" 
                          "<h3>METHOD: " method "</h3>\n" 
                          "<h3>HEADERS: " headers "</h3>\n" 
                          "</body></html>\n"))
                    )
                  )
                )]
    httpd
    )
  )

(defn init-wsd 
  [bind-address port wsd-sessions]
  (let [wsd (proxy [NanoWSD] [bind-address port]
              (openWebSocket 
                [handshake]
                (log/i "openWebSocket")
                (let [web-socket (proxy [NanoWSD$WebSocket] [handshake]
                                   (onOpen []
                                     (log/i "onOpen")
                                     ;; TODO - need to check how to get session-id
                                     (swap! wsd-sessions :sessiond this))
                                   (onClose [code, reason, initiated-by-remote]
                                     (log/i "onClose"))
                                   (onMessage [message]
                                     (log/i "onMessage")
                                     (.send this "thanks going underground...")
                                     (on-ui (dotimes [n 8]
                                              (do (Thread/sleep 3000)
                                                  (.send this (str "sending message:" n)))))
                                     (log/i "done with this session..."))
                                   (onPong [pong]
                                     (log/i "onPong"))
                                   (onException [exception]
                                     (log/i "onException")
                                     (log/i exception)
                                     ))]
                  web-socket)
                )
              )]
    wsd
    )
)

;; --API-- ;;
(defprotocol I-HTTPD-WSD
  "HTTPD and WSD APIs"
  (init-servers
    [this])
  (start-servers
    [this])
)

(defrecord HTTPD-WSD
    ;; :httpd-port     PORT number that will be use for HTTPD server
    ;; :wsd-port       PORT number that will be use for WSD server
    ;; :bind-address   IP address for binding both HTTPD and WSD servers
    ;; :httpd          Reference to httpd instance
    ;; :wsd            Reference to wsd instance
    ;; :wsd-sessions   MAP wsc to rooms for easy broadcasing

  [httpd-port wsd-port bind-address httpd wsd wsd-sessions] 
  I-HTTPD-WSD
 
  (start-servers
      [this]
    (if (.isAlive httpd) 
      (do
        (toast "starting HTTPD server")
        (log/i (str "starting HTTPD server: " bind-address ":" httpd-port))
        (.start httpd))
      (log/i "HTTPD server is already running..."))
    (if (.isAlive wsd)
      (do 
        (toast "starting wsd server")
        (log/i (str "starting WSD server: " bind-address ":" wsd-port))
        (.start wsd -1))
      (log/i "WSD server is already running"))
    )
)

(defn new-httpd-wsd
 "Constractor for HTTPD-WSD
  :httpd-port     PORT number that will be use for HTTPD server
  :wsd-port       PORT number that will be use for WSD server
  :bind-address   IP address for binding both HTTPD and WSD servers"
 
 [httpd-port wsd-port bind-address]
 
 (let [wsd-sessions (atom {})]

   (map->HTTPD-WSD{:httpd-port httpd-port
                                 :wsd-port wsd-port
                                 :bind-address bind-address
                                 :wsd-sessions wsd-sessions
                                 :httpd (init-httpd bind-address httpd-port)
                                 :wsd (init-wsd bind-address wsd-port wsd-sessions)
                                 })
   )
)

;; Create HTTPD-WSD instance
(def httpd-wsd (new-httpd-wsd (int 5557) (int 5558) "0.0.0.0"))

;; This is how an Activity is defined. We create one and specify its onCreate
;; method. Inside we create a user interface that consists of an edit and a
;; button. We also give set callback to the button.
(defactivity my.company.superapp.MyActivity
  :key :main

  (onCreate [this bundle]
    (.superOnCreate this bundle)
    (setup-action-bar this
                      {:title "Custom title"
                       :icon R$drawable/ic_launcher
                       :display-options [:show-home :show-title :home-as-up]
                       :subtitle "Custom subtitle"
                       :navigation-mode :tabs
                       :tabs [
                              [:tab 
                               {:text "Player"
                                :icon R$drawable/ic_launcher
                                :tab-listener (tab-listener
                                               :on-tab-selected (fn [tab ft]
                                                                  (toast "Player was presed")))}]
                              [:tab {:text "Settings"
                                     :icon R$drawable/ic_launcher                                    
                                     :tab-listener (tab-listener
                                                    :on-tab-selected (fn [tab ft]
                                                                       (toast "Settings was presed")))}]]})

    ;; starting servers
    ;; (toast "starting HTTPD-WSD servers" :long)
    (start-servers httpd-wsd)
    (neko.debug/keep-screen-on this)
    (on-ui
      (set-content-view! (*a)
        [:linear-layout {:orientation :vertical
                         :layout-width :fill
                         :layout-height :wrap}
         [:edit-text {:id ::user-input
                      :hint "Type text here"
                      :layout-width :fill}]
         [:button {:text R$string/touch_me ;; We use resource here, but could
                                           ;; have used a plain string too.
                   :on-click (fn [_] (notify-from-edit (*a)))}]]))))
