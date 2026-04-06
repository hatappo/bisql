/*:name delete-by-id */
DELETE FROM user_devices
WHERE id = /*$id*/1

/*:name delete-by-user-id-and-device-identifier */
DELETE FROM user_devices
WHERE user_id = /*$user-id*/1
  AND device_identifier = /*$device-identifier*/'sample'

/*:name get-by-id */
SELECT * FROM user_devices
WHERE id = /*$id*/1

/*:name get-by-user-id-and-device-identifier */
SELECT * FROM user_devices
WHERE user_id = /*$user-id*/1
  AND device_identifier = /*$device-identifier*/'sample'

/*:name insert */
INSERT INTO user_devices (
  user_id,
  device_type,
  device_identifier,
  status,
  last_seen_at
)
VALUES (
  /*$user-id*/1,
  /*$device-type*/'sample',
  /*$device-identifier*/'sample',
  /*$status*/'sample',
  /*$last-seen-at*/CURRENT_TIMESTAMP
)
RETURNING *

/*:name list-by-status-and-device-type */
SELECT * FROM user_devices
WHERE status = /*$status*/'sample'
  AND device_type = /*$device-type*/'sample'
ORDER BY last_seen_at
LIMIT /*$limit*/100

/*:name list-by-status-and-device-type-and-last-seen-at */
SELECT * FROM user_devices
WHERE status = /*$status*/'sample'
  AND device_type = /*$device-type*/'sample'
  AND last_seen_at = /*$last-seen-at*/CURRENT_TIMESTAMP
LIMIT /*$limit*/100

/*:name list-by-status-and-last-seen-at */
SELECT * FROM user_devices
WHERE status = /*$status*/'sample'
  AND last_seen_at = /*$last-seen-at*/CURRENT_TIMESTAMP
LIMIT /*$limit*/100

/*:name list-by-status-order-by-device-type-and-last-seen-at */
SELECT * FROM user_devices
WHERE status = /*$status*/'sample'
ORDER BY device_type, last_seen_at
LIMIT /*$limit*/100

/*:name list-by-status-order-by-last-seen-at */
SELECT * FROM user_devices
WHERE status = /*$status*/'sample'
ORDER BY last_seen_at
LIMIT /*$limit*/100

/*:name list-by-user-id-and-last-seen-at */
SELECT * FROM user_devices
WHERE user_id = /*$user-id*/1
  AND last_seen_at = /*$last-seen-at*/CURRENT_TIMESTAMP
LIMIT /*$limit*/100

/*:name list-by-user-id-order-by-device-identifier */
SELECT * FROM user_devices
WHERE user_id = /*$user-id*/1
ORDER BY device_identifier
LIMIT /*$limit*/100

/*:name list-by-user-id-order-by-last-seen-at */
SELECT * FROM user_devices
WHERE user_id = /*$user-id*/1
ORDER BY last_seen_at
LIMIT /*$limit*/100

/*:name update-by-id */
UPDATE user_devices
SET user_id = /*$user-id*/1
  , device_type = /*$device-type*/'sample'
  , device_identifier = /*$device-identifier*/'sample'
  , status = /*$status*/'sample'
  , last_seen_at = /*$last-seen-at*/CURRENT_TIMESTAMP
WHERE id = /*$id*/1
RETURNING *

/*:name update-by-user-id-and-device-identifier */
UPDATE user_devices
SET device_type = /*$device-type*/'sample'
  , status = /*$status*/'sample'
  , last_seen_at = /*$last-seen-at*/CURRENT_TIMESTAMP
WHERE user_id = /*$user-id*/1
  AND device_identifier = /*$device-identifier*/'sample'
RETURNING *