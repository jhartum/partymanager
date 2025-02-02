(ns partymanager.state-test
  (:require [clojure.test :refer :all]
            [partymanager.state :as state]
            [malli.core :as m])
  (:import (java.util UUID)))

(deftest test-init-storage!
  (testing "Successfully validates empty state"
    (let [valid-state {:groups []
                       :users {}}]
      (is (nil? (m/explain state/AppState-schema valid-state)))))
  
  (testing "Successfully validates complex state"
    (let [user-id 123
          group-id (UUID/randomUUID)
          valid-state {:groups [{:id group-id
                                :name "Test Group"
                                :creator-id user-id
                                :invite-code "ABC123"
                                :members [{:id user-id
                                          :ready false
                                          :first-name "Test"
                                          :username "test_user"
                                          :language-code "en"
                                          :registration-date 1234567890}]
                                :threshold 2
                                :max-members 5
                                :created-at 1234567890
                                :last-ready-time 0}]
                       :users {user-id {:chat-id user-id
                                      :first-name "Test"
                                      :username "test_user"
                                      :language-code "en"
                                      :registration-date 1234567890}}}]
      (is (nil? (m/explain state/AppState-schema valid-state)))))

  (testing "Throws exception for invalid state format"
    (is (thrown? clojure.lang.ExceptionInfo
                 (state/init-storage! {:invalid "state"}))))
  
  (testing "Throws exception for invalid group format"
    (is (thrown? clojure.lang.ExceptionInfo
                 (state/init-storage! 
                  {:groups [{:id "not-a-uuid" ;; должен быть UUID
                            :name 123 ;; должна быть строка
                            :creator-id "not-a-number"}] ;; должно быть число
                   :users {}}))))
  
  (testing "Throws exception for invalid user format"
    (is (thrown? clojure.lang.ExceptionInfo
                 (state/init-storage!
                  {:groups []
                   :users {123 {:chat-id "not-a-number" ;; должно быть число
                               :first-name 123 ;; должна быть строка
                               :language-code 123}}}))))) ;; должна быть строка

(deftest test-get-group-by-id
  (testing "Successfully gets existing group"
    (let [group-id (UUID/randomUUID)
          test-group {:id group-id :name "Test Group"}]
      (reset! state/app-state {:groups [test-group]})
      (is (= test-group (state/get-group-by-id group-id)))))
  
  (testing "Throws exception for non-existent group"
    (reset! state/app-state {:groups []})
    (is (thrown-with-msg? 
         clojure.lang.ExceptionInfo 
         #"Group not found"
         (state/get-group-by-id (UUID/randomUUID)))))
  
  (testing "Throws exception for invalid UUID"
    (is (thrown-with-msg? 
         clojure.lang.ExceptionInfo 
         #"Invalid group id format"
         (state/get-group-by-id "not-a-uuid")))))

(deftest test-get-user
  (testing "Successfully gets existing user"
    (let [user-id 123456789
          test-user {:chat-id 123456789
                     :first-name "Test"
                     :username "test_user"
                     :language-code "en"
                     :registration-date (System/currentTimeMillis)}]
      (reset! state/app-state {:users {user-id test-user}})
      (is (= test-user (state/get-user user-id)))))
  
  (testing "Throws exception for non-existent user"
    (reset! state/app-state {:users {}})
    (is (thrown-with-msg? 
         clojure.lang.ExceptionInfo 
         #"User not found"
         (state/get-user 987654321)))))

(deftest test-generate-code
  (testing "Generated code format"
    ;; Проверка длины кода
    (is (= 6 (count (state/generate-code))))
    ;; Проверка что код состоит только из цифр
    (is (re-matches #"\d{6}" (state/generate-code))))
    
  (testing "Code uniqueness"
    ;; Генерация множества кодов и проверка их уникальности
    (let [codes (repeatedly 100 state/generate-code)]
      (is (= (count codes) (count (set codes))))))
    
  (testing "Handling existing codes"
    ;; Создаем группу с известным кодом и проверяем, что следующий код будет отличаться
    (let [initial-code "123456"
          _ (reset! state/app-state {:groups [{:invite-code initial-code}]})
          new-code (state/generate-code)]
      (is (not= initial-code new-code))))

  (testing "Code range validation"
    ;; Проверка что код всегда 6-значный (не меньше 100000)
    (let [codes (repeatedly 100 state/generate-code)]
      (is (every? #(>= (Integer/parseInt %) 100000) codes))
      (is (every? #(<= (Integer/parseInt %) 999999) codes))))

  (testing "Leading zeros preservation"
    ;; Проверка сохранения ведущих нулей
    (with-redefs [rand-int (constantly 0)] ; всегда возвращает 0
      (is (= "100000" (state/generate-code)))))

  (testing "Multiple groups with codes"
    ;; Проверка работы с несколькими существующими группами
    (let [existing-codes ["123456" "234567" "345678"]
          _ (reset! state/app-state {:groups 
                                    (mapv #(hash-map :invite-code %) existing-codes)})
          new-code (state/generate-code)]
      (is (not (contains? (set existing-codes) new-code)))))

  (testing "Concurrent code generation"
    ;; Проверка параллельной генерации кодов
    (let [codes (doall 
                 (pmap (fn [_] (state/generate-code)) 
                      (range 10)))]
      (is (= (count codes) 
             (count (set codes)))))))
