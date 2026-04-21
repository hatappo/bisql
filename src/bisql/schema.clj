(ns bisql.schema
  (:require [bisql.query :as query]))

(defn uuid-value?
  [value]
  (instance? java.util.UUID value))

(defn local-date?
  [value]
  (instance? java.time.LocalDate value))

(defn local-time?
  [value]
  (instance? java.time.LocalTime value))

(defn offset-time?
  [value]
  (instance? java.time.OffsetTime value))

(defn local-date-time?
  [value]
  (instance? java.time.LocalDateTime value))

(defn offset-date-time?
  [value]
  (instance? java.time.OffsetDateTime value))

(def malli-default-sentinel
  [:fn #(identical? % query/DEFAULT)])

(def malli-all-sentinel
  [:fn #(identical? % query/ALL)])

(def malli-limit
  [:or int? malli-all-sentinel])

(def malli-offset
  int?)
