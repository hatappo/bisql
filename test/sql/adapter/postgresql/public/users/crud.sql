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
  id,
  email,
  display_name,
  status,
  created_at
)
VALUES (
  /*$id*/1,
  /*$email*/'user@example.com',
  /*$display-name*/'sample',
  /*$status*/'sample',
  /*$created-at*/CURRENT_TIMESTAMP
)
RETURNING *

/*:name crud.insert-many */
/*:cardinality :many */
INSERT INTO users (
  id,
  email,
  display_name,
  status,
  created_at
)
VALUES
/*%for row in rows separating , */
(
  /*$row.id*/1,
  /*$row.email*/'user@example.com',
  /*$row.display-name*/'sample',
  /*$row.status*/'sample',
  /*$row.created-at*/CURRENT_TIMESTAMP
)
/*%end */
RETURNING *

/*:name crud.list-by-status */
/*:cardinality :many */
SELECT * FROM users
WHERE status = /*$status*/'sample'
LIMIT /*$limit*/100

/*:name crud.update-by-email */
/*:cardinality :one */
UPDATE users
SET
/*%for item in updates separating , */
  /*!item.name*/created_at = /*$item.value*/CURRENT_TIMESTAMP
/*%end */
WHERE email = /*$where.email*/'user@example.com'
RETURNING *

/*:name crud.update-by-id */
/*:cardinality :one */
UPDATE users
SET
/*%for item in updates separating , */
  /*!item.name*/created_at = /*$item.value*/CURRENT_TIMESTAMP
/*%end */
WHERE id = /*$where.id*/1
RETURNING *
