(ns org.panchromatic.mokuhan2.parser.internal
  (:require [org.panchromatic.mokuhan2.ast2 :as ast]
            [org.panchromatic.mokuhan2.reader :as reader]
            [org.panchromatic.mokuhan2.util.string :as ustr]
            [org.panchromatic.mokuhan2.zip2 :as mzip]))

(defn make-initial-state
  [{:keys [delimiters] :as options}]
  {:ast (mzip/ast-zip)
   :template-context (ast/template-context {:delimiters delimiters})})

(defn lookahead-and-matched? [reader s]
  (let [read (reader/read-chars reader (ustr/length s))
        _ (reader/unread-chars reader read)]
    (= s (apply str read))))

(defn- read-text [reader open-delim]
  (loop [sb (ustr/string-builder)
         text? true]
    (if-let [c (and text? (reader/read-char reader))]
      (cond
        (contains? #{\space \tab \return \newline} c)
        (do
          (reader/unread-char reader c)
          (recur sb false))

        (= (ustr/char-at open-delim 0) c)
        (if (lookahead-and-matched? reader (subs open-delim 1))
          (do
            (reader/unread-char reader c)
            (recur sb false))
          (recur (ustr/append sb c) text?))

        :else
        (recur (ustr/append sb c) text?))
      (str sb))))

(defn parse-text [reader {:keys [template-context] :as state}]
  (let [text (read-text reader (get-in template-context [:delimiters :open]))
        text-node (ast/text text (ast/template-context template-context))]
    (-> state
        (update-in [:ast] mzip/append-primitive text-node)
        (update-in [:template-context :column] + (ustr/length text))
        (assoc-in [:template-context :standalone?] false))))

(defn- read-whitespace [reader]
  (loop [sb (ustr/string-builder)
         whitespace? true]
    (let [c (and whitespace? (reader/read-char reader))]
      (case c
        (\space \tab \　)
        (recur (.append sb c) whitespace?)
        (do
          (when c (reader/unread-char reader c))
          (str sb))))))

(defn parse-whitespace [reader {:keys [template-context] :as state}]
  (let [whitespaces (read-whitespace reader)
        ws-node (ast/whitespace whitespaces (ast/template-context template-context))]
    (-> state
        (update-in [:ast] mzip/append-primitive ws-node)
        (update-in [:template-context :column] + (ustr/length whitespaces)))))

(defn- read-newline [reader]
  (loop [^StringBuilder sb (ustr/string-builder)
         saw-cr false]
    (let [c (reader/read-char reader)]
      (case c
        \return
        (recur sb true)
        \newline
        (-> (cond-> sb saw-cr (ustr/append \return))
            (ustr/append c)
            str)))))

(defn parse-newline [reader {:keys [template-context] :as state}]
  (let [newln (read-newline reader)
        newln-node (ast/newline newln (ast/template-context template-context))]
    (-> state
        (update-in [:ast] mzip/append-primitive newln-node)
        (assoc-in [:template-context :column] 1)
        (update-in [:template-context :row] inc)
        (assoc-in [:template-context :standalone?] true))))

(let [state {:begin {:read-ws :before-read-keys
                     :read-char :read-keys}
             :before-read-keys {:read-ws :before-read-keys
                                :read-char :read-keys}
             :read-keys {:read-char :read-keys
                         :read-ws :after-read-keys
                         :read-delim :end}
             :after-read-keys {:read-ws :after-read-keys
                               :read-delim :end}
             :end {}}]
  (defn- next-read-keys-state [current action]
    (get-in state [current action])))

(def ^:private valid-read-keys-state
  #{:begin
    :before-read-keys
    :read-keys
    :after-read-keys
    :end})

(defn- read-keys [reader close-delim]
  (loop [sb (ustr/string-builder)
         state :begin
         result {:ks [] :read-cnt 0}]
    (let [c (reader/read-char reader)]
      (cond
        (= :end state)
        (do
          (reader/unread-char reader c)
          result)

        (nil? state)
        {:err :invalid-tag-name}

        (nil? c)
        {:err :unclosed-tag}

        (contains? #{\space \tab} c)
        (recur sb
               (next-read-keys-state state :read-ws)
               (update result :read-cnt inc))

        (= (ustr/char-at close-delim 0) c)
        (let [red (reader/read-chars reader (ustr/length (subs close-delim 1)))
              close-delim' (apply str c red)]
          (if (= close-delim' close-delim)
            (do
              (reader/unread-chars reader close-delim')
              (recur nil
                     (next-read-keys-state state :read-delim)
                     (update result :ks conj (str sb))))
            (do
              (reader/unread-chars reader red)
              (recur (ustr/append sb c)
                     (next-read-keys-state state :read-char)
                     (update result :read-cnt inc)))))

        (= \. c)
        (recur (ustr/string-builder)
               (next-read-keys-state state :read-char)
               (-> result
                   (update :ks conj (str sb))
                   (update :read-cnt inc)))

        :else
        (recur (ustr/append sb c)
               (next-read-keys-state state :read-char)
               (update result :read-cnt inc))))))

(defn- read-delimiter [reader delim]
  (->> (ustr/length delim)
       (reader/read-chars reader)
       (keep identity)
       (apply str)))

(defn- parse-error [cause template-context]
  {:error {:type :org.panchromatic.mokuhan2/parse-error
           :cause cause
           :occurred (select-keys template-context [:row :column :contexts])}})

(defn parse-variable-tag [reader {:keys [template-context] :as state}]
  (let [{open-delim :open close-delim :close} (:delimiters template-context)
        _ (read-delimiter reader open-delim)
        {:keys [ks read-cnt err]} (read-keys reader close-delim)
        _ (read-delimiter reader close-delim)]
    (if-not err
      (let [variable-tag-node (ast/variable-tag ks (ast/template-context template-context))]
        (-> state
            (update-in [:ast] mzip/append-tag variable-tag-node)
            (update-in [:template-context :column] + (ustr/length open-delim) read-cnt (ustr/length close-delim))
            (assoc-in [:template-context :standalone?] false)))

      (parse-error err template-context))))

(defn parse-unescaped-variable-tag [reader {:keys [template-context] :as state}]
  (let [{open-delim :open close-delim :close} (:delimiters template-context)
        _ (read-delimiter reader open-delim)
        ensure-unescaped-variable? (= \& (reader/read-char reader))
        {:keys [ks read-cnt err]} (read-keys reader close-delim)
        _ (read-delimiter reader close-delim)]
    (if (and ensure-unescaped-variable? (nil? err))
      (let [unescaped-variable-tag-node (ast/unescaped-variable-tag ks (ast/template-context template-context))]
        (-> state
            (update-in [:ast] mzip/append-tag unescaped-variable-tag-node)
            (update-in [:template-context :column] + (ustr/length open-delim) 1 read-cnt (ustr/length close-delim))
            (assoc-in [:template-context :standalone?] false)))

      (parse-error err template-context))))

(defn parse-open-section-tag [reader {:keys [template-context] :as state}]
  (let [{open-delim :open close-delim :close} (:delimiters template-context)
        _ (read-delimiter reader open-delim)
        ensure-open-section? (= \# (reader/read-char reader))
        {:keys [ks read-cnt err]} (read-keys reader close-delim)
        _ (read-delimiter reader close-delim)]
    (if (and ensure-open-section? (nil? err))
      (let [open-section-tag-node (ast/open-section-tag ks (ast/template-context template-context))]
        (-> state
            (update-in [:ast] mzip/append&into-section)
            (update-in [:ast] mzip/assoc-open-section-tag open-section-tag-node)
            (update-in [:template-context :contexts] conj ks)
            (update-in [:template-context :column] + (ustr/length open-delim) 1 read-cnt (ustr/length close-delim))
            (assoc-in [:template-context :standalone?] false)))

      (parse-error err template-context))))

(defn parse-close-section-tag [reader {:keys [template-context] :as state}]
  (let [{open-delim :open close-delim :close} (get-in state [:template-context :delimiters])
        [current-context & rest-contexts] (get-in state [:template-context :contexts])
        _ (read-delimiter reader open-delim)
        ensure-close-section? (= \/ (reader/read-char reader))
        {:keys [ks read-cnt err]} (read-keys reader close-delim)
        _ (read-delimiter reader close-delim)]
    (cond
      (and ensure-close-section? (= current-context ks) (nil? err))
      (let [close-section-tag-node (ast/close-section-tag ks (ast/template-context (update template-context :contexts pop)))]
        (-> state
            (update-in [:ast] mzip/assoc-close-section-tag close-section-tag-node)
            (update-in [:ast] mzip/out-section)
            (update-in [:template-context :contexts] pop)
            (update-in [:template-context :column] + (ustr/length open-delim) 1 read-cnt (ustr/length close-delim))
            (assoc-in [:template-context :standalone?] false)))

      (and (nil? err) (not= current-context ks))
      (parse-error :unclosed-section template-context)

      :else
      (parse-error err template-context))))

(let [ws #{\space \tab \　}]
  (defn- whitespace? [c]
    (contains? ws c)))

(let [nl #{\return \newline}]
  (defn- newline? [c]
    (contains? nl c)))

(defn parse [^java.io.PushbackReader reader state]
  (loop [reader reader
         {:keys [error] :as state} state]
    (if error
      state
      (let [open-delim (get-in state [:template-context :delimiters :open])
            ;; for choosing a parser
            c (reader/read-char reader)
            _ (reader/unread-char reader c)]
        (cond
          (whitespace? c)
          (recur reader (parse-whitespace reader state))

          (newline? c)
          (recur reader (parse-newline reader state))

          (nil? c)
          (update state :ast mzip/complete)

          (= (ustr/char-at open-delim 0) c)
          (let [open-delim' (read-delimiter reader open-delim)
                sigil (reader/read-char reader)
                _ (reader/unread-chars reader (cond-> open-delim' sigil (str sigil)))]
            (if (= open-delim open-delim')
              (case sigil
                \&
                (recur reader (parse-unescaped-variable-tag reader state))
                \#
                (recur reader (parse-open-section-tag reader state))
                \/
                (recur reader (parse-close-section-tag reader state))
                (recur reader (parse-variable-tag reader state)))

              (recur reader (parse-text reader state))))

          :else
          (recur reader (parse-text reader state)))))))
