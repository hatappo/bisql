SELECT u.id,
       u.email,
       o.id AS order_id,
       o.state,
       o.created_at
FROM users u
JOIN orders o
  ON o.user_id = u.id
WHERE u.status = /*$status*/'active'
  AND o.state = /*$state*/'pending'
ORDER BY o.created_at DESC
LIMIT /*$limit*/100
