/*:name delete-by-user-id-and-role-code */
DELETE FROM user_roles
WHERE user_id = /*$user-id*/1
  AND role_code = /*$role-code*/'sample'

/*:name get-by-user-id-and-role-code */
SELECT * FROM user_roles
WHERE user_id = /*$user-id*/1
  AND role_code = /*$role-code*/'sample'

/*:name insert */
INSERT INTO user_roles (
  user_id,
  role_code,
  granted_at,
  granted_by
)
VALUES (
  /*$user-id*/1,
  /*$role-code*/'sample',
  /*$granted-at*/CURRENT_TIMESTAMP,
  /*$granted-by*/1
)
RETURNING *

/*:name list-by-user-id */
SELECT * FROM user_roles
WHERE user_id = /*$user-id*/1
ORDER BY role_code
LIMIT /*$limit*/100

/*:name update-by-user-id-and-role-code */
UPDATE user_roles
SET granted_at = /*$granted-at*/CURRENT_TIMESTAMP
  , granted_by = /*$granted-by*/1
WHERE user_id = /*$user-id*/1
  AND role_code = /*$role-code*/'sample'
RETURNING *