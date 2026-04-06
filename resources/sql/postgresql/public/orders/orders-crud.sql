/*:name delete-by-id */
DELETE FROM orders
WHERE id = /*$id*/1

/*:name delete-by-order-number */
DELETE FROM orders
WHERE order_number = /*$order-number*/'sample'

/*:name get-by-id */
SELECT * FROM orders
WHERE id = /*$id*/1

/*:name get-by-order-number */
SELECT * FROM orders
WHERE order_number = /*$order-number*/'sample'

/*:name insert */
INSERT INTO orders (
  user_id,
  order_number,
  state,
  total_amount,
  created_at
)
VALUES (
  /*$user-id*/1,
  /*$order-number*/'sample',
  /*$state*/'sample',
  /*$total-amount*/1,
  /*$created-at*/CURRENT_TIMESTAMP
)
RETURNING *

/*:name list-by-state */
SELECT * FROM orders
WHERE state = /*$state*/'sample'
ORDER BY created_at
LIMIT /*$limit*/100

/*:name list-by-state-and-created-at */
SELECT * FROM orders
WHERE state = /*$state*/'sample'
  AND created_at = /*$created-at*/CURRENT_TIMESTAMP
LIMIT /*$limit*/100

/*:name list-by-user-id */
SELECT * FROM orders
WHERE user_id = /*$user-id*/1
LIMIT /*$limit*/100

/*:name update-by-id */
UPDATE orders
SET user_id = /*$user-id*/1
  , order_number = /*$order-number*/'sample'
  , state = /*$state*/'sample'
  , total_amount = /*$total-amount*/1
  , created_at = /*$created-at*/CURRENT_TIMESTAMP
WHERE id = /*$id*/1
RETURNING *

/*:name update-by-order-number */
UPDATE orders
SET user_id = /*$user-id*/1
  , state = /*$state*/'sample'
  , total_amount = /*$total-amount*/1
  , created_at = /*$created-at*/CURRENT_TIMESTAMP
WHERE order_number = /*$order-number*/'sample'
RETURNING *