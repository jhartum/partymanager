(ns partymanager.state-test
  (:require
   [clojure.test :refer :all]
   [malli.core :as m]
   [partymanager.state :as state])
  (:import
   (java.util UUID)))

(deftest test-init-storage!
  (testing "Successfully validates empty state"
    (let [valid-state {:parties [] :users {}}]
      (is (nil? (m/explain state/AppState-schema valid-state)))))

  (testing "Successfully validates complex state"
    (let [user-id 123
          party-id (UUID/randomUUID)
          valid-state {:parties [{:id party-id
                                  :name "Test Party"
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
      (is (nil? (m/explain state/AppState-schema valid-state))))))

(testing "Throws exception for invalid state format"
  (is (thrown? clojure.lang.ExceptionInfo
               (state/init-storage! {:invalid "state"}))))

(testing "Throws exception for invalid party format"
  (is (thrown? clojure.lang.ExceptionInfo
               (state/init-storage!
                {:parties [{:id "not-a-uuid" ;; должен быть UUID
                            :name 123 ;; должна быть строка
                            :creator-id "not-a-number"}] ;; должно быть число
                 :users {}}))))

(testing "Throws exception for invalid user format"
  (is (thrown? clojure.lang.ExceptionInfo
               (state/init-storage!
                {:parties []
                 :users {123 {:chat-id "not-a-number" ;; должно быть число
                              :first-name 123 ;; должна быть строка
                              :language-code 123}}}))))

(deftest test-get-party-by-id
  (testing "Successfully gets existing party"
    (let [party-id (UUID/randomUUID)
          test-party {:id party-id :name "Test Party"}]
      (reset! state/app-state {:parties [test-party]})
      (is (= test-party (state/get-party-by-id party-id)))))

  (testing "Throws exception for non-existent party"
    (reset! state/app-state {:parties []})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Party not found"
         (state/get-party-by-id (UUID/randomUUID)))))

  (testing "Throws exception for invalid UUID"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid party id format"
         (state/get-party-by-id "not-a-uuid")))))

(deftest test-get-user
  (testing "Возвращает данные пользователя"
    (reset! state/app-state {:users {123456789 {:chat-id 123456789
                                                :first-name "Test"
                                                :language-code "ru"
                                                :registration-date 1234567890}}})
    (is (= {:chat-id 123456789
            :first-name "Test"
            :language-code "ru"
            :registration-date 1234567890}
           (state/get-user 123456789))))

  (testing "Выбрасывает исключение для несуществующего пользователя"
    (reset! state/app-state {:users {}})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"User not found"
         (state/get-user 987654321)))))

(deftest test-generate-code
  (testing "Формат сгенерированного кода"
    ;; Проверка длины кода
    (is (= 6 (count (state/generate-code))))
    ;; Проверка что код состоит только из цифр
    (is (re-matches #"\d{6}" (state/generate-code))))

  (testing "Уникальность кодов"
    ;; Генерация множества кодов и проверка их уникальности
    (let [codes (repeatedly 100 state/generate-code)]
      (is (= (count codes) (count (set codes))))))

  (testing "Обработка существующих кодов"
    ;; Создаем пати с известным кодом и проверяем, что следующий код будет отличаться
    (let [initial-code "123456"
          _ (reset! state/app-state {:parties [{:invite-code initial-code}]})
          new-code (state/generate-code)]
      (is (not= initial-code new-code)))))

(deftest test-party-operations
  (testing "Создание пати"
    ;; Создаём тестового пользователя перед созданием пати
    (reset! state/app-state {:parties []
                             :users {123456789 {:chat-id 123456789
                                                :first-name "Test User"
                                                :username "test_user"
                                                :language-code "en"
                                                :registration-date 1234567890}}})
    (let [party (state/add-party "Test Party" 123456789 "3" "10")]
      (is (= "Test Party" (:name party)))
      (is (= 3 (:threshold party)))
      (is (= 10 (:max-members party)))))

  (testing "Добавление участника в пати"
    (let [party-id (UUID/randomUUID)]
      (reset! state/app-state {:parties [{:id party-id
                                          :members []
                                          :max-members 10}]
                               :users {123456789 {:first-name "Test User"}}})
      (let [updated-state (state/add-member-to-party party-id 123456789)]
        (is (= 1 (count (get-in updated-state [:parties 0 :members])))
            "Количество участников должно быть равно 1")
        (is (= "Test User" (get-in updated-state [:parties 0 :members 0 :first-name])))))

    (testing "Удаление пати"
      (let [party-id (UUID/randomUUID)
            creator-id 123456789]
        (reset! state/app-state {:parties [{:id party-id
                                            :creator-id creator-id
                                            :members []}]
                                 :users {}})
        (state/delete-party party-id creator-id)
        (is (empty? (:parties @state/app-state)))))))
