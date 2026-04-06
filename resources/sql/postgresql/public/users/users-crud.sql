/*:name delete-by-email */
DELETE FROM users
WHERE email = /*$email*/'user@example.com'

/*:name delete-by-id */
DELETE FROM users
WHERE id = /*$id*/1

/*:name get-by-email */
SELECT * FROM users
WHERE email = /*$email*/'user@example.com'

/*:name get-by-id */
SELECT * FROM users
WHERE id = /*$id*/1

/*:name insert */
INSERT INTO users (
  email,
  display_name,
  status,
  created_at
)
VALUES (
  /*$email*/'user@example.com',
  /*$display-name*/'sample',
  /*$status*/'sample',
  /*$created-at*/CURRENT_TIMESTAMP
)
RETURNING *

/*:name list-by-status */
SELECT * FROM users
WHERE status = /*$status*/'sample'
LIMIT /*$limit*/100

/*:name update-by-email */
UPDATE users
SET display_name = /*$display-name*/'sample'
  , status = /*$status*/'sample'
  , created_at = /*$created-at*/CURRENT_TIMESTAMP
WHERE email = /*$email*/'user@example.com'
RETURNING *

/*:name update-by-id */
UPDATE users
SET email = /*$email*/'user@example.com'
  , display_name = /*$display-name*/'sample'
  , status = /*$status*/'sample'
  , created_at = /*$created-at*/CURRENT_TIMESTAMP
WHERE id = /*$id*/1
RETURNING *