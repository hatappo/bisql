SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%end */
AND status = /*$status*/'active'
