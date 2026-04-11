/*:name crud.delete-by-email */
/*:cardinality :one */
DELETE FROM users
WHERE email = /*$email*/'user@example.com'
RETURNING *

/*:name crud.delete-by-id */
/*:cardinality :one */
DELETE FROM users
WHERE id = /*$id*/1
RETURNING *

/*:name crud.get-by-email */
/*:cardinality :one */
SELECT * FROM users
WHERE email = /*$email*/'user@example.com'

/*:name crud.get-by-id */
/*:cardinality :one */
SELECT * FROM users
WHERE id = /*$id*/1

/*:name crud.insert */
/*:cardinality :one */
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

/*:name crud.list-by-status */
/*:cardinality :many */
SELECT * FROM users
WHERE status = /*$status*/'sample'
LIMIT /*$limit*/100

/*:name crud.update-by-email */
/*:cardinality :one */
UPDATE users
SET display_name = /*$display-name*/'sample'
  , status = /*$status*/'sample'
  , created_at = /*$created-at*/CURRENT_TIMESTAMP
WHERE email = /*$email*/'user@example.com'
RETURNING *

/*:name crud.update-by-id */
/*:cardinality :one */
UPDATE users
SET email = /*$email*/'user@example.com'
  , display_name = /*$display-name*/'sample'
  , status = /*$status*/'sample'
  , created_at = /*$created-at*/CURRENT_TIMESTAMP
WHERE id = /*$id*/1
RETURNING *
