/*:name find-user-by-id */
SELECT * FROM users WHERE id = /*$id*/1

/*:name find-user-by-email */
SELECT * FROM users WHERE email = /*$email*/'user@example.com'
